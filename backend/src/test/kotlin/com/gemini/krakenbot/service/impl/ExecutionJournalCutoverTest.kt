package com.gemini.krakenbot.service.impl

import com.gemini.krakenbot.TestFixtures
import com.gemini.krakenbot.config.Allocation
import com.gemini.krakenbot.config.DatabaseConfig
import com.gemini.krakenbot.config.ExecutionDatabase
import com.gemini.krakenbot.config.ExecutionJournalBootstrap
import com.gemini.krakenbot.config.KrakenCredentials
import com.gemini.krakenbot.config.appModule
import com.gemini.krakenbot.domain.OrderResult
import com.gemini.krakenbot.domain.RebalancerEngine
import com.gemini.krakenbot.model.Asset
import com.gemini.krakenbot.model.OrderIntent
import com.gemini.krakenbot.model.OrderIntentState
import com.gemini.krakenbot.model.OrderSubmissionState
import com.gemini.krakenbot.model.TradeRecord
import com.gemini.krakenbot.repository.ExecutionOrderIntentRepository
import com.gemini.krakenbot.repository.PortfolioStatsRepository
import com.gemini.krakenbot.repository.impl.SqliteExecutionOrderIntentRepositoryImpl
import com.gemini.krakenbot.repository.impl.SqliteOrderIntentRepositoryImpl
import com.gemini.krakenbot.repository.impl.SqliteTradeRepositoryImpl
import com.gemini.krakenbot.service.ConfigService
import com.gemini.krakenbot.service.ExecutionAccountBindingVerifier
import com.gemini.krakenbot.service.FakeKrakenService
import com.gemini.krakenbot.service.KrakenService
import com.gemini.krakenbot.service.OrderExecutor
import com.gemini.krakenbot.service.OrderIntentService
import com.gemini.krakenbot.service.ReportingDispatcher
import com.gemini.krakenbot.service.TradeHistoryService
import com.gemini.krakenbot.service.impl.ConfigServiceImpl
import com.gemini.krakenbot.service.impl.history.HistoryEvidenceCoordinator
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.comparables.shouldBeEqualComparingTo
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.sql.DriverManager
import java.sql.SQLException
import java.time.Instant
import java.util.Comparator

class ExecutionJournalCutoverTest : StringSpec() {
    override fun isolationMode() = IsolationMode.InstancePerTest

    init {
        "execution intent and outcome commit while reporting SQLite and its history lock are held" {
            withTempDirectory { directory ->
                val reportingPath = directory.resolve("reporting.db")
                val executionPath = directory.resolve("execution.db")
                DatabaseConfig.init(reportingPath.toString())
                val database = ExecutionDatabase.init(executionPath.toString(), reportingPath.toString())
                val repository = SqliteExecutionOrderIntentRepositoryImpl(database)
                runTest {
                    ExecutionJournalBootstrap(database, repository, reportingPath.toString()).initialize()
                }
                DriverManager.getConnection("jdbc:sqlite:$reportingPath").use { connection ->
                    connection.createStatement().use { it.execute("CREATE TABLE reporting_lock_probe (id INTEGER)") }
                }
                val reportLock = DriverManager.getConnection("jdbc:sqlite:$reportingPath")
                reportLock.autoCommit = false
                reportLock.createStatement().use { it.execute("INSERT INTO reporting_lock_probe VALUES (1)") }

                try {
                    var timings = Triple(0L, 0L, 0)
                    runBlocking {
                        suspend fun persistIntentAndOutcome(key: String): Int {
                            val id = repository.savePending(
                                newIntent().copy(cycleId = key, clientOrderId = "client-$key"),
                            )
                            repository.recordOutcome(
                                id = id,
                                state = OrderIntentState.CONFIRMED,
                                orderTxid = "TX-$key",
                                errorMessage = null,
                                resolvedAt = Instant.now(),
                                outcomeVolume = newIntent().volume,
                            ) shouldBe true
                            return id
                        }

                        val baselineStartedAt = System.nanoTime()
                        persistIntentAndOutcome("uncontended")
                        val baseline = (System.nanoTime() - baselineStartedAt) / 1_000_000

                        val coordinator = HistoryEvidenceCoordinator()
                        val lockEntered = CompletableDeferred<Unit>()
                        val releaseCoordinator = CompletableDeferred<Unit>()
                        val lockJob = launch {
                            coordinator.withLock("reporting-lock-test") {
                                lockEntered.complete(Unit)
                                releaseCoordinator.await()
                            }
                        }
                        try {
                            lockEntered.await()
                            val startedAt = System.nanoTime()
                            val contendedId = withTimeout(4_000) {
                                persistIntentAndOutcome("reporting-locked")
                            }
                            val elapsed = (System.nanoTime() - startedAt) / 1_000_000

                            reportLock.isClosed shouldBe false
                            reportLock.autoCommit shouldBe false
                            DriverManager.getConnection("jdbc:sqlite:$reportingPath").use { observer ->
                                observer.createStatement().use { it.execute("PRAGMA busy_timeout=100") }
                                shouldThrow<SQLException> {
                                    observer.createStatement().use {
                                        it.executeUpdate("INSERT INTO reporting_lock_probe VALUES (2)")
                                    }
                                }
                            }
                            readSingleString(
                                executionPath,
                                "SELECT state FROM execution_order_intents WHERE id=$contendedId",
                            ) shouldBe OrderIntentState.CONFIRMED.name
                            readSingleString(
                                executionPath,
                                "SELECT order_txid FROM execution_order_intents WHERE id=$contendedId",
                            ) shouldBe "TX-reporting-locked"
                            (elapsed < 4_000) shouldBe true
                            timings = Triple(baseline, elapsed, contendedId)
                        } finally {
                            releaseCoordinator.complete(Unit)
                            lockJob.join()
                        }
                    }
                    val (uncontendedMillis, contendedMillis, intentId) = timings
                    println(
                        "Execution DB pending+outcome writes: ${uncontendedMillis}ms without reporting lock, " +
                            "${contendedMillis}ms with reporting DB transaction and coordinator lock held.",
                    )
                    intentId shouldBe 2
                } finally {
                    reportLock.rollback()
                    reportLock.close()
                }
            }
        }

        "live placement and execution outcomes survive reporting failure after initialization" {
            withTempDirectory { directory ->
                val reportingPath = directory.resolve("reporting.db")
                val reportDatabase = DatabaseConfig.init(reportingPath.toString())
                val executionPath = directory.resolve("execution.db")
                val database = ExecutionDatabase.init(executionPath.toString(), reportingPath.toString())
                val repository = SqliteExecutionOrderIntentRepositoryImpl(database)
                runTest {
                    ExecutionJournalBootstrap(
                        database,
                        repository,
                        reportingPath.toString(),
                    ).initialize()
                }
                val tradeRepository = SqliteTradeRepositoryImpl(reportDatabase)
                val fake = FakeKrakenService()
                val executor = OrderExecutorImpl(
                    fake,
                    tradeHistoryService = null,
                    orderIntentService = orderIntentService(repository),
                )
                DriverManager.getConnection("jdbc:sqlite:$reportingPath").use { connection ->
                    connection.createStatement().use {
                        it.execute(
                            "CREATE TRIGGER injected_report_failure BEFORE INSERT ON trades " +
                                "BEGIN SELECT RAISE(ABORT, 'injected report failure'); END",
                        )
                    }
                }

                runTest {
                    executeOneLiveBuy(executor)
                    repository.hasUnresolvedIntents() shouldBe false
                }
                fake.executedOrders.size shouldBe 1
                runTest {
                    shouldThrow<Exception> { TradeProjectionServiceImpl(repository, tradeRepository).projectPending() }
                }
                database.connect().use { connection ->
                    connection.createStatement().use { statement ->
                        statement.executeQuery(
                            "SELECT state FROM execution_order_intents ORDER BY id DESC LIMIT 1",
                        ).use { rows ->
                            rows.next() shouldBe true
                            rows.getString(1) shouldBe OrderIntentState.CONFIRMED.name
                        }
                    }
                }
                readSingleLong(Path.of(database.path), "SELECT COUNT(*) FROM execution_projection_outbox") shouldBe 1L

                Files.move(reportingPath, directory.resolve("reporting-unavailable.db"))
                Files.createDirectory(reportingPath)
                val restartedDatabase = ExecutionDatabase.init(database.path, reportingPath.toString())
                restartedDatabase.journalId() shouldBe database.journalId()
                val restartedRepository = SqliteExecutionOrderIntentRepositoryImpl(restartedDatabase)
                runTest {
                    ExecutionJournalBootstrap(
                        restartedDatabase,
                        restartedRepository,
                        reportingPath.toString(),
                    ).initialize()
                    val retainedOutcome = restartedRepository.loadProjectionEvents(0, 10).single()
                    retainedOutcome.intent.state shouldBe OrderIntentState.CONFIRMED
                }
                shouldThrow<Exception> {
                    runTest {
                        TradeProjectionServiceImpl(restartedRepository, tradeRepository).projectPending()
                    }
                }
                runTest {
                    restartedRepository.loadProjectionEvents(0, 10).single().intent.state shouldBe
                        OrderIntentState.CONFIRMED
                }
                readSingleLong(Path.of(database.path), "SELECT COUNT(*) FROM execution_projection_outbox") shouldBe 1L

                val offlineFake = FakeKrakenService()
                val offlineExecutor = OrderExecutorImpl(
                    offlineFake,
                    tradeHistoryService = null,
                    orderIntentService = orderIntentService(restartedRepository),
                )
                runTest {
                    executeOneLiveBuy(offlineExecutor, "reporting-unavailable")
                    restartedRepository.hasUnresolvedIntents() shouldBe false
                }
                offlineFake.executedOrders.size shouldBe 1
            }
        }

        "execution journal failure prevents AddOrder before it reaches the exchange adapter" {
            withTempDirectory { directory ->
                val (database, repository) = readyJournal(directory.resolve("execution.db"))
                val dbPath = Path.of(database.path)
                val displaced = directory.resolve("execution.db.displaced")
                Files.move(dbPath, displaced)
                listOf("-wal", "-shm").forEach { suffix ->
                    val sidecar = Path.of("${database.path}$suffix")
                    if (Files.exists(sidecar)) Files.move(sidecar, directory.resolve("displaced$suffix"))
                }
                Files.createDirectory(dbPath)
                val fake = FakeKrakenService()
                val executor = OrderExecutorImpl(
                    fake,
                    tradeHistoryService = null,
                    orderIntentService = orderIntentService(repository),
                )

                runTest { shouldThrow<Exception> { executeOneLiveBuy(executor) } }

                fake.executedOrders.size shouldBe 0
            }
        }

        "live order executor fails closed when its execution journal dependency is missing" {
            val fake = FakeKrakenService()
            val executor = OrderExecutorImpl(fake, tradeHistoryService = null)

            runTest { shouldThrow<IllegalStateException> { executeOneLiveBuy(executor) } }

            fake.executedOrders.size shouldBe 0
        }

        "legacy reporting order intents cannot authorize a live placement" {
            withTempDirectory { directory ->
                val reportingPath = directory.resolve("reporting.db")
                val fake = FakeKrakenService()
                val legacyService = OrderIntentServiceImpl(
                    SqliteOrderIntentRepositoryImpl(DatabaseConfig.init(reportingPath.toString())),
                )
                val executor = OrderExecutorImpl(fake, tradeHistoryService = null, orderIntentService = legacyService)

                runTest {
                    shouldThrow<IllegalStateException> { executeOneLiveBuy(executor, "legacy-journal") }
                }

                fake.executedOrders.size shouldBe 0
            }
        }

        "accepted exchange order with failed outcome commit becomes uncertain after restart" {
            withTempDirectory { directory ->
                val reportingPath = directory.resolve("reporting.db")
                val executionPath = directory.resolve("execution.db")
                DatabaseConfig.init(reportingPath.toString())
                val database = ExecutionDatabase.init(executionPath.toString(), reportingPath.toString())
                val repository = SqliteExecutionOrderIntentRepositoryImpl(database)
                runTest {
                    ExecutionJournalBootstrap(database, repository, reportingPath.toString()).initialize()
                }
                DriverManager.getConnection("jdbc:sqlite:$executionPath").use { connection ->
                    connection.createStatement().use {
                        it.execute(
                            "CREATE TRIGGER reject_outcome BEFORE UPDATE OF state ON execution_order_intents " +
                                "BEGIN SELECT RAISE(ABORT, 'injected outcome persistence failure'); END",
                        )
                    }
                }
                val fake = FakeKrakenService()
                val executor = OrderExecutorImpl(
                    fake,
                    tradeHistoryService = null,
                    orderIntentService = orderIntentService(repository),
                )

                runTest {
                    shouldThrow<Exception> { executeOneLiveBuy(executor, "outcome-write-failure") }
                }

                fake.executedOrders.size shouldBe 1
                readSingleString(executionPath, "SELECT state FROM execution_order_intents") shouldBe
                    OrderIntentState.PENDING.name
                DriverManager.getConnection("jdbc:sqlite:$executionPath").use { connection ->
                    connection.createStatement().use { it.execute("DROP TRIGGER reject_outcome") }
                }

                val restartedDatabase = ExecutionDatabase.init(executionPath.toString(), reportingPath.toString())
                val restartedRepository = SqliteExecutionOrderIntentRepositoryImpl(restartedDatabase)
                runTest {
                    ExecutionJournalBootstrap(
                        restartedDatabase,
                        restartedRepository,
                        reportingPath.toString(),
                    ).initialize()
                    restartedRepository.loadUnresolvedIntents().single().state shouldBe OrderIntentState.UNCERTAIN
                }
            }
        }

        "full stopped reporting queue cannot block a durable live order projection" {
            withTempDirectory { directory ->
                val (database, repository) = readyJournal(
                    directory.resolve("execution.db"),
                    directory.resolve("reporting.db"),
                )
                val dispatcher = ReportingDispatcher(
                    historyServiceProvider = { error("The stopped reporting worker must not be called") },
                    projectionServiceProvider = { error("The stopped reporting worker must not be called") },
                )
                val queuedReport = TradeRecord(
                    timestamp = Instant.parse("2026-10-08T12:00:00Z"),
                    pair = "XBTUSD",
                    side = "BUY",
                    symbol = Asset.BTC,
                    volume = java.math.BigDecimal("0.02500000"),
                    usdAmount = java.math.BigDecimal("25.00"),
                    success = true,
                    dryRun = true,
                )
                repeat(257) { dispatcher.enqueueTrade(queuedReport) }
                val fake = FakeKrakenService()
                val executor = OrderExecutorImpl(
                    fake,
                    tradeHistoryService = null,
                    orderIntentService = orderIntentService(repository),
                    reportingDispatcher = dispatcher,
                )

                runTest { executeOneLiveBuy(executor, "full-report-queue") }

                fake.executedOrders.size shouldBe 1
                runTest {
                    val projected = repository.loadProjectionEvents(0, 10).single()
                    projected.intent.state shouldBe OrderIntentState.CONFIRMED
                    projected.journalId shouldBe database.journalId()
                }
            }
        }

        "a missing initialized execution database is not silently recreated" {
            withTempDirectory { directory ->
                val path = directory.resolve("execution.db")
                val (database, repository) = readyJournal(path)
                runTest { repository.savePending(newIntent()) }
                Files.delete(Path.of(database.path))
                listOf("-wal", "-shm").forEach { suffix ->
                    Files.deleteIfExists(Path.of("${database.path}$suffix"))
                }

                shouldThrow<IOException> { ExecutionDatabase.init(path.toString()) }

                Files.exists(path) shouldBe false
            }
        }

        "the reporting-side witness catches loss of the execution database and identity marker" {
            withTempDirectory { directory ->
                val path = directory.resolve("execution.db")
                val reportingPath = directory.resolve("reporting.db")
                readyJournal(path, reportingPath)
                Files.delete(path)
                Files.delete(Path.of("$path.journal-id"))
                listOf("-wal", "-shm").forEach { suffix ->
                    Files.deleteIfExists(Path.of("$path$suffix"))
                }

                shouldThrow<IOException> { ExecutionDatabase.init(path.toString(), reportingPath.toString()) }

                Files.exists(path) shouldBe false
            }
        }

        "a missing identity sidecar is rejected for a non-empty execution journal" {
            withTempDirectory { directory ->
                val path = directory.resolve("execution.db")
                val reportPath = directory.resolve("reporting.db")
                val (database, repository) = readyJournal(path, reportPath)
                runTest { repository.savePending(newIntent()) }
                val identityPath = path.resolveSibling("${path.fileName}.journal-id")
                Files.delete(identityPath)

                shouldThrow<IOException> { ExecutionDatabase.init(path.toString(), reportPath.toString()) }

                Files.exists(identityPath) shouldBe false
                Files.isRegularFile(Path.of(database.path)) shouldBe true
            }
        }

        "an empty execution journal can recreate its sidecar before live state exists" {
            withTempDirectory { directory ->
                val path = directory.resolve("execution.db")
                val reportingPath = directory.resolve("reporting.db")
                val (database, _) = readyJournal(path, reportingPath)
                val identityPath = path.resolveSibling("${path.fileName}.journal-id")
                Files.delete(identityPath)

                val recovered = ExecutionDatabase.init(path.toString(), reportingPath.toString())

                Files.isRegularFile(identityPath) shouldBe true
                recovered.journalId() shouldBe database.journalId()
            }
        }

        "a current reporting schema permits first journal creation before its cutover marker exists" {
            withTempDirectory { directory ->
                val reportingPath = directory.resolve("reporting.db")
                val executionPath = directory.resolve("execution.db")
                DatabaseConfig.init(reportingPath.toString())

                val database = ExecutionDatabase.init(executionPath.toString(), reportingPath.toString())

                Files.isRegularFile(executionPath) shouldBe true
                database.journalId().isNotBlank() shouldBe true
            }
        }

        "loss of the database and identity sidecar after legacy migration is caught by its report marker" {
            withTempDirectory { directory ->
                val reportingPath = directory.resolve("reporting.db")
                val executionPath = directory.resolve("execution.db")
                val reportDatabase = DatabaseConfig.init(reportingPath.toString())
                val legacyRepository = SqliteOrderIntentRepositoryImpl(reportDatabase)
                runTest { legacyRepository.savePending(newIntent()) }
                val database = ExecutionDatabase.init(executionPath.toString(), reportingPath.toString())
                val executionRepository = SqliteExecutionOrderIntentRepositoryImpl(database)
                runTest {
                    ExecutionJournalBootstrap(database, executionRepository, reportingPath.toString()).initialize()
                }
                readSingleLong(
                    reportingPath,
                    "SELECT COUNT(*) FROM history_sync_metadata WHERE key LIKE 'execution-cutover:%'",
                ) shouldBe 1L

                Files.delete(Path.of(database.path))
                Files.delete(Path.of("${database.path}.journal-id"))
                listOf("-wal", "-shm").forEach { suffix ->
                    Files.deleteIfExists(Path.of("${database.path}$suffix"))
                }

                shouldThrow<IOException> {
                    ExecutionDatabase.init(executionPath.toString(), reportingPath.toString())
                }

                Files.exists(executionPath) shouldBe false
            }
        }

        "empty-source startup retains an independent witness after reporting initializes later" {
            withTempDirectory { directory ->
                val reportingPath = directory.resolve("reporting.db")
                val executionPath = directory.resolve("execution.db")
                val database = ExecutionDatabase.init(executionPath.toString(), reportingPath.toString())
                val repository = SqliteExecutionOrderIntentRepositoryImpl(database)
                runTest {
                    ExecutionJournalBootstrap(database, repository, reportingPath.toString()).initialize()
                }
                DatabaseConfig.init(reportingPath.toString())
                val independentWitness = Path.of("$reportingPath.execution-journal-witness")
                Files.isRegularFile(independentWitness) shouldBe true
                Files.readString(independentWitness).trim() shouldBe database.journalId()

                Files.delete(Path.of(database.path))
                Files.delete(Path.of("${database.path}.journal-id"))
                listOf("-wal", "-shm").forEach { suffix ->
                    Files.deleteIfExists(Path.of("${database.path}$suffix"))
                }

                shouldThrow<IOException> {
                    ExecutionDatabase.init(executionPath.toString(), reportingPath.toString())
                }

                Files.exists(executionPath) shouldBe false
            }
        }

        "an in-memory execution journal cannot authorize live placement" {
            val database = ExecutionDatabase.init(":memory:", ":memory:")
            val repository = SqliteExecutionOrderIntentRepositoryImpl(database)
            database.markReadyForSubmission()
            val fake = FakeKrakenService()
            val executor = OrderExecutorImpl(
                fake,
                tradeHistoryService = null,
                orderIntentService = orderIntentService(repository),
            )

            runTest { shouldThrow<IllegalStateException> { executeOneLiveBuy(executor) } }

            fake.executedOrders.size shouldBe 0
        }

        "in-memory bootstrap records the empty source once and remains restartable" {
            val database = ExecutionDatabase.init(":memory:", ":memory:")
            val repository = SqliteExecutionOrderIntentRepositoryImpl(database)
            val bootstrap = ExecutionJournalBootstrap(database, repository, ":memory:")

            runTest {
                bootstrap.initialize()
                bootstrap.initialize()
                shouldThrow<IllegalStateException> { database.ensureReadyForSubmission() }
            }

            database.connect().use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeQuery("SELECT COUNT(*) FROM execution_legacy_migrations").use {
                        it.next() shouldBe true
                        it.getLong(1) shouldBe 1L
                    }
                    statement.executeQuery(
                        "SELECT backup_sha256, cutover_guard_written FROM execution_legacy_migrations",
                    ).use {
                        it.next() shouldBe true
                        it.getString(1) shouldBe null
                        it.getLong(2) shouldBe 1L
                    }
                }
            }
        }

        "PENDING is committed before AddOrder and a crash at that boundary blocks restart retry" {
            withTempDirectory { directory ->
                val path = directory.resolve("execution.db")
                val reportingPath = directory.resolve("reporting-unused.db")
                val (firstDatabase, firstProcessRepository) = readyJournal(path, reportingPath)
                val delegate = orderIntentService(firstProcessRepository)
                val crashAfterCommit = object : OrderIntentService by delegate {
                    override suspend fun savePending(intent: OrderIntent): Int {
                        delegate.savePending(intent)
                        throw SimulatedProcessCrash()
                    }
                }
                val firstFake = FakeKrakenService()
                val firstExecutor = OrderExecutorImpl(
                    firstFake,
                    tradeHistoryService = null,
                    orderIntentService = crashAfterCommit,
                )

                runTest { shouldThrow<SimulatedProcessCrash> { executeOneLiveBuy(firstExecutor, "crash-window") } }
                firstFake.executedOrders.size shouldBe 0
                readSingleString(path, "SELECT state FROM execution_order_intents ORDER BY id DESC LIMIT 1") shouldBe
                    OrderIntentState.PENDING.name

                val restartedDatabase = ExecutionDatabase.init(path.toString(), reportingPath.toString())
                val restartedRepository = SqliteExecutionOrderIntentRepositoryImpl(restartedDatabase)
                runTest {
                    ExecutionJournalBootstrap(
                        database = restartedDatabase,
                        repository = restartedRepository,
                        reportingDbPath = reportingPath.toString(),
                    ).initialize()
                }
                val fake = FakeKrakenService()
                val executor = OrderExecutorImpl(
                    fake,
                    tradeHistoryService = null,
                    orderIntentService = orderIntentService(restartedRepository),
                )

                runTest {
                    executeOneLiveBuy(executor)
                    restartedRepository.loadUnresolvedIntents().single().state shouldBe OrderIntentState.UNCERTAIN
                }
                fake.executedOrders.size shouldBe 0
                firstDatabase.journalId() shouldBe restartedDatabase.journalId()
            }
        }

        "successful AddOrder without an order id becomes uncertain and blocks another placement" {
            withTempDirectory { directory ->
                val (_, repository) = readyJournal(directory.resolve("execution.db"))
                val fake = FakeKrakenService().apply {
                    orderResultFactory = { pair, _, side, volume ->
                        OrderResult(
                            success = true,
                            pair = pair,
                            side = side,
                            volume = volume,
                            dryRun = false,
                            orderTxid = null,
                        )
                    }
                }
                val executor = OrderExecutorImpl(
                    fake,
                    tradeHistoryService = null,
                    orderIntentService = orderIntentService(repository),
                )

                runTest {
                    executeOneLiveBuy(executor, "successful-missing-id")
                    repository.loadUnresolvedIntents().single().state shouldBe OrderIntentState.UNCERTAIN
                    executeOneLiveBuy(executor, "successful-missing-id-retry")
                    repository.loadUnresolvedIntents().single().state shouldBe OrderIntentState.UNCERTAIN
                }

                fake.executedOrders.size shouldBe 1
            }
        }

        "cancellation during AddOrder durably records uncertainty and prevents a retry" {
            withTempDirectory { directory ->
                val (_, repository) = readyJournal(directory.resolve("execution.db"))
                val fake = FakeKrakenService().apply {
                    executeOrderAction = { _, _, _, _ ->
                        throw kotlinx.coroutines.CancellationException("cancelled after submission began")
                    }
                }
                val executor = OrderExecutorImpl(
                    fake,
                    tradeHistoryService = null,
                    orderIntentService = orderIntentService(repository),
                )

                runTest {
                    shouldThrow<kotlinx.coroutines.CancellationException> {
                        executeOneLiveBuy(executor, "cancelled-placement")
                    }
                    repository.loadUnresolvedIntents().single().state shouldBe OrderIntentState.UNCERTAIN
                    executeOneLiveBuy(executor, "cancelled-placement-retry")
                    repository.loadUnresolvedIntents().single().state shouldBe OrderIntentState.UNCERTAIN
                }

                fake.executedOrders.size shouldBe 1
            }
        }

        "file-backed execution connections enforce the configured SQLite durability settings" {
            withTempDirectory { directory ->
                val database = ExecutionDatabase.init(
                    directory.resolve("execution.db").toString(),
                    directory.resolve("reporting.db").toString(),
                )

                repeat(2) {
                    database.connect().use { connection ->
                        connection.createStatement().use { statement ->
                            statement.executeQuery("PRAGMA foreign_keys").use {
                                it.next() shouldBe true
                                it.getInt(1) shouldBe
                                    1
                            }
                            statement.executeQuery("PRAGMA busy_timeout").use {
                                it.next() shouldBe true
                                it.getInt(1) shouldBe
                                    5_000
                            }
                            statement.executeQuery("PRAGMA synchronous").use {
                                it.next() shouldBe true
                                it.getInt(1) shouldBe
                                    2
                            }
                            statement.executeQuery("PRAGMA journal_mode").use {
                                it.next() shouldBe true
                                it.getString(1) shouldBe
                                    "wal"
                            }
                        }
                    }
                }

                shouldThrow<SQLException> {
                    database.connect().use { connection ->
                        connection.createStatement().use {
                            it.execute("INSERT INTO execution_projection_outbox(intent_id, revision) VALUES (999, 1)")
                        }
                    }
                }
            }
        }

        "ambiguous AddOrder response is durable and blocks a later placement" {
            withTempDirectory { directory ->
                val (_, repository) = readyJournal(directory.resolve("execution.db"))
                val fake = FakeKrakenService().apply {
                    orderResultFactory = { pair, _, side, volume ->
                        OrderResult(
                            success = false,
                            pair = pair,
                            side = side,
                            volume = volume,
                            errorMessage = "response lost after placement",
                            submissionUncertain = true,
                        )
                    }
                }
                val executor = OrderExecutorImpl(
                    fake,
                    tradeHistoryService = null,
                    orderIntentService = orderIntentService(repository),
                )

                runTest {
                    executeOneLiveBuy(executor, cycleId = "ambiguous-first")
                    executeOneLiveBuy(executor, cycleId = "ambiguous-retry")
                    repository.loadUnresolvedIntents().single().state shouldBe OrderIntentState.UNCERTAIN
                }

                fake.executedOrders.size shouldBe 1
            }
        }

        "AddOrder IOException persists uncertainty in the execution journal and blocks retries" {
            withTempDirectory { directory ->
                val (_, repository) = readyJournal(directory.resolve("execution.db"))
                var placementAttempts = 0
                val fake = FakeKrakenService().apply {
                    executeOrderAction = { _, _, _, _ ->
                        placementAttempts += 1
                        throw IOException("connection reset after submission")
                    }
                }
                val executor = OrderExecutorImpl(
                    fake,
                    tradeHistoryService = null,
                    orderIntentService = orderIntentService(repository),
                )

                runTest {
                    val thrown = shouldThrow<IOException> { executeOneLiveBuy(executor, "io-first") }
                    thrown.message shouldBe "connection reset after submission"
                    repository.loadUnresolvedIntents().single().state shouldBe OrderIntentState.UNCERTAIN

                    executeOneLiveBuy(executor, "io-retry")
                    repository.loadUnresolvedIntents().single().state shouldBe OrderIntentState.UNCERTAIN
                }

                placementAttempts shouldBe 1
            }
        }

        "legacy import failure after an earlier row remains atomic and retry preserves source rows" {
            withTempDirectory { directory ->
                val reportingPath = directory.resolve("reporting.db")
                val reportDb = DatabaseConfig.init(reportingPath.toString())
                val legacyRepository = SqliteOrderIntentRepositoryImpl(reportDb)
                var firstLegacyId = 0
                var secondLegacyId = 0
                runTest {
                    firstLegacyId = legacyRepository.savePending(newIntent())
                    secondLegacyId = legacyRepository.savePending(
                        newIntent().copy(cycleId = "migration-cycle-2", clientOrderId = "client-migration-2"),
                    )
                }
                DriverManager.getConnection("jdbc:sqlite:$reportingPath").use { connection ->
                    connection.createStatement().use {
                        it.execute(
                            "UPDATE order_intents SET error_message='preserved legacy note' " +
                                "WHERE id=$secondLegacyId",
                        )
                        it.execute("DELETE FROM schema_migrations WHERE version=16")
                    }
                }
                val originalIntentState = readSingleString(
                    reportingPath,
                    "SELECT state FROM order_intents WHERE id=$firstLegacyId",
                )
                val secondOriginalIntentState = readSingleString(
                    reportingPath,
                    "SELECT state FROM order_intents WHERE id=$secondLegacyId",
                )
                val databasePath = directory.resolve("execution.db")
                val executionDatabase = ExecutionDatabase.init(databasePath.toString(), reportingPath.toString())
                val repository = SqliteExecutionOrderIntentRepositoryImpl(executionDatabase)
                DriverManager.getConnection("jdbc:sqlite:$databasePath").use { connection ->
                    connection.createStatement().use {
                        it.execute(
                            "CREATE TRIGGER injected_import_failure BEFORE INSERT ON execution_order_intents " +
                                "WHEN NEW.legacy_intent_id = $secondLegacyId " +
                                "BEGIN SELECT RAISE(ABORT, 'injected import failure'); END",
                        )
                    }
                }
                val beforeFailureHash = sha256(Files.readAllBytes(reportingPath))
                val bootstrap = ExecutionJournalBootstrap(executionDatabase, repository, reportingPath.toString())

                runTest { shouldThrow<Exception> { bootstrap.initialize() } }

                readSingleLong(
                    databasePath,
                    "SELECT COUNT(*) FROM execution_order_intents WHERE state IN ('PENDING','UNCERTAIN')",
                ) shouldBe
                    0L
                readSingleLong(databasePath, "SELECT COUNT(*) FROM execution_legacy_migrations") shouldBe 0L
                readSingleString(reportingPath, "SELECT state FROM order_intents WHERE id=$firstLegacyId") shouldBe
                    originalIntentState
                readSingleString(reportingPath, "SELECT state FROM order_intents WHERE id=$secondLegacyId") shouldBe
                    secondOriginalIntentState
                readSingleLong(reportingPath, "SELECT COUNT(*) FROM schema_migrations WHERE version=16") shouldBe 0L
                val retainedBackup = directory.resolve("reporting.db.pre-execution-journal.bak")
                Files.isRegularFile(retainedBackup) shouldBe true
                readSingleString(retainedBackup, "PRAGMA integrity_check") shouldBe "ok"
                sha256(Files.readAllBytes(reportingPath)) shouldBe beforeFailureHash

                DriverManager.getConnection("jdbc:sqlite:$databasePath").use { connection ->
                    connection.createStatement().use { it.execute("DROP TRIGGER injected_import_failure") }
                }
                runTest {
                    bootstrap.initialize()
                    val imported = repository.loadUnresolvedIntents().associateBy { it.legacyIntentId }
                    imported.keys shouldBe setOf(firstLegacyId, secondLegacyId)
                    imported.values.forEach {
                        it.state shouldBe OrderIntentState.UNCERTAIN
                        it.legacySourceState shouldBe "PENDING"
                    }
                    imported.getValue(secondLegacyId).errorMessage shouldBe "preserved legacy note"
                }
                readSingleLong(reportingPath, "SELECT COUNT(*) FROM schema_migrations WHERE version=16") shouldBe 1L
                readSingleLong(databasePath, "SELECT COUNT(*) FROM execution_order_intents") shouldBe 2L
                readSingleLong(databasePath, "SELECT cutover_guard_written FROM execution_legacy_migrations") shouldBe
                    1L

                Files.delete(reportingPath)
                listOf("-wal", "-shm").forEach { suffix ->
                    Files.deleteIfExists(Path.of("$reportingPath$suffix"))
                }
                runTest { bootstrap.initialize() }
            }
        }

        "legacy cutover preserves terminal intent outcomes and leaves them out of the recovery queue" {
            withTempDirectory { directory ->
                val reportingPath = directory.resolve("reporting.db")
                val reportDatabase = DatabaseConfig.init(reportingPath.toString())
                val legacyRepository = SqliteOrderIntentRepositoryImpl(reportDatabase)
                val tradeRepository = SqliteTradeRepositoryImpl(reportDatabase)
                var confirmedId = 0
                var rejectedId = 0
                runTest {
                    val confirmedIntent = newIntent()
                    val confirmedTradeId = tradeRepository.saveTrade(legacyPendingTrade(confirmedIntent))
                    confirmedId = legacyRepository.savePending(confirmedIntent.copy(localTradeId = confirmedTradeId))
                    legacyRepository.recordOutcome(
                        confirmedId,
                        OrderIntentState.CONFIRMED,
                        "TX-LEGACY-CONFIRMED",
                        null,
                        Instant.parse("2026-10-08T12:05:00Z"),
                        java.math.BigDecimal("0.02400000"),
                    )
                    val rejectedIntent = newIntent().copy(
                        cycleId = "rejected-cycle",
                        clientOrderId = "client-rejected",
                    )
                    val rejectedTradeId = tradeRepository.saveTrade(legacyPendingTrade(rejectedIntent))
                    rejectedId = legacyRepository.savePending(rejectedIntent.copy(localTradeId = rejectedTradeId))
                    legacyRepository.recordOutcome(
                        rejectedId,
                        OrderIntentState.REJECTED,
                        null,
                        "Kraken rejected the order",
                        Instant.parse("2026-10-08T12:06:00Z"),
                    )
                }
                val executionPath = directory.resolve("execution.db")
                val database = ExecutionDatabase.init(executionPath.toString(), reportingPath.toString())
                val repository = SqliteExecutionOrderIntentRepositoryImpl(database)

                runTest {
                    ExecutionJournalBootstrap(database, repository, reportingPath.toString()).initialize()
                }

                readSingleString(
                    executionPath,
                    "SELECT state FROM execution_order_intents WHERE legacy_intent_id=$confirmedId",
                ) shouldBe OrderIntentState.CONFIRMED.name
                readSingleString(
                    executionPath,
                    "SELECT order_txid FROM execution_order_intents WHERE legacy_intent_id=$confirmedId",
                ) shouldBe "TX-LEGACY-CONFIRMED"
                readSingleString(
                    executionPath,
                    "SELECT state FROM execution_order_intents WHERE legacy_intent_id=$rejectedId",
                ) shouldBe OrderIntentState.REJECTED.name
                readSingleString(
                    executionPath,
                    "SELECT error_message FROM execution_order_intents WHERE legacy_intent_id=$rejectedId",
                ) shouldBe "Kraken rejected the order"
                readSingleLong(executionPath, "SELECT COUNT(*) FROM execution_projection_outbox") shouldBe 0L
                runTest { repository.hasUnresolvedIntents() shouldBe false }
            }
        }

        "unknown legacy intent state keeps migration incomplete and live placement blocked" {
            withTempDirectory { directory ->
                val reportingPath = directory.resolve("reporting.db")
                val reportDatabase = DatabaseConfig.init(reportingPath.toString())
                val legacyRepository = SqliteOrderIntentRepositoryImpl(reportDatabase)
                var legacyId = 0
                runTest { legacyId = legacyRepository.savePending(newIntent()) }
                DriverManager.getConnection("jdbc:sqlite:$reportingPath").use { connection ->
                    connection.createStatement().use {
                        it.execute("UPDATE order_intents SET state='NOT_A_STATE' WHERE id=$legacyId")
                    }
                }
                val sourceHash = sha256(Files.readAllBytes(reportingPath))
                val executionPath = directory.resolve("execution.db")
                val database = ExecutionDatabase.init(executionPath.toString(), reportingPath.toString())
                val repository = SqliteExecutionOrderIntentRepositoryImpl(database)
                val bootstrap = ExecutionJournalBootstrap(database, repository, reportingPath.toString())

                runTest { shouldThrow<IllegalStateException> { bootstrap.initialize() } }

                sha256(Files.readAllBytes(reportingPath)) shouldBe sourceHash
                readSingleLong(executionPath, "SELECT COUNT(*) FROM execution_order_intents") shouldBe 0L
                readSingleLong(executionPath, "SELECT COUNT(*) FROM execution_legacy_migrations") shouldBe 0L
                val fake = FakeKrakenService()
                val executor = OrderExecutorImpl(
                    fake,
                    tradeHistoryService = null,
                    orderIntentService = orderIntentService(repository),
                )
                runTest { shouldThrow<IllegalStateException> { executeOneLiveBuy(executor, "unknown-legacy-state") } }
                fake.executedOrders.size shouldBe 0
            }
        }

        "legacy database without cutover metadata cannot be marked safe for live startup" {
            withTempDirectory { directory ->
                val reportingPath = directory.resolve("reporting.db")
                DriverManager.getConnection("jdbc:sqlite:$reportingPath").use { connection ->
                    connection.createStatement().use {
                        it.execute(
                            "CREATE TABLE schema_migrations (version INTEGER PRIMARY KEY, name TEXT NOT NULL, " +
                                "applied_at INTEGER NOT NULL)",
                        )
                        it.execute(
                            "INSERT INTO schema_migrations(version, name, applied_at) VALUES (15, 'legacy', 1)",
                        )
                    }
                }
                val executionPath = directory.resolve("execution.db")
                val database = ExecutionDatabase.init(executionPath.toString(), reportingPath.toString())
                val repository = SqliteExecutionOrderIntentRepositoryImpl(database)

                runTest {
                    shouldThrow<IllegalStateException> {
                        ExecutionJournalBootstrap(database, repository, reportingPath.toString()).initialize()
                    }
                }

                readSingleLong(executionPath, "SELECT COUNT(*) FROM execution_legacy_migrations") shouldBe 1L
                readSingleLong(executionPath, "SELECT cutover_guard_written FROM execution_legacy_migrations") shouldBe
                    0L
                shouldThrow<IllegalStateException> { database.ensureReadyForSubmission() }
            }
        }

        "unknown legacy trade submission guard is never silently discarded" {
            withTempDirectory { directory ->
                val reportingPath = directory.resolve("reporting.db")
                val reportDatabase = DatabaseConfig.init(reportingPath.toString())
                val tradeRepository = SqliteTradeRepositoryImpl(reportDatabase)
                val legacyTrade = TradeRecord(
                    timestamp = Instant.parse("2026-10-08T12:00:00Z"),
                    pair = "XBTUSD",
                    side = "BUY",
                    symbol = "BTC",
                    volume = java.math.BigDecimal("0.02500000"),
                    usdAmount = java.math.BigDecimal("25.00"),
                    success = false,
                    dryRun = false,
                    submissionState = OrderSubmissionState.UNCERTAIN,
                )
                var tradeId = 0
                runTest { tradeId = tradeRepository.saveTrade(legacyTrade) }
                DriverManager.getConnection("jdbc:sqlite:$reportingPath").use { connection ->
                    connection.createStatement().use {
                        it.execute("UPDATE trades SET submission_state='NOT_A_STATE' WHERE id=$tradeId")
                    }
                }
                val executionPath = directory.resolve("execution.db")
                val database = ExecutionDatabase.init(executionPath.toString(), reportingPath.toString())
                val repository = SqliteExecutionOrderIntentRepositoryImpl(database)

                runTest {
                    shouldThrow<IllegalStateException> {
                        ExecutionJournalBootstrap(database, repository, reportingPath.toString()).initialize()
                    }
                }

                readSingleLong(executionPath, "SELECT COUNT(*) FROM execution_order_intents") shouldBe 0L
                readSingleString(reportingPath, "SELECT submission_state FROM trades WHERE id=$tradeId") shouldBe
                    "NOT_A_STATE"
                val fake = FakeKrakenService()
                val executor = OrderExecutorImpl(
                    fake,
                    tradeHistoryService = null,
                    orderIntentService = orderIntentService(repository),
                )
                runTest { shouldThrow<IllegalStateException> { executeOneLiveBuy(executor, "unknown-legacy-guard") } }
                fake.executedOrders.size shouldBe 0
            }
        }

        "unmatched legacy live guard remains fail-closed after restart" {
            withTempDirectory { directory ->
                val reportingPath = directory.resolve("reporting.db")
                val reportDatabase = DatabaseConfig.init(reportingPath.toString())
                val tradeRepository = SqliteTradeRepositoryImpl(reportDatabase)
                val orphan = legacyPendingTrade(newIntent()).copy(
                    cycleId = null,
                    clientOrderId = null,
                    submissionState = OrderSubmissionState.UNCERTAIN,
                )
                var tradeId = 0
                runTest { tradeId = tradeRepository.saveTrade(orphan) }
                val executionPath = directory.resolve("execution.db")
                val database = ExecutionDatabase.init(executionPath.toString(), reportingPath.toString())
                val repository = SqliteExecutionOrderIntentRepositoryImpl(database)

                runTest {
                    shouldThrow<IllegalStateException> {
                        ExecutionJournalBootstrap(database, repository, reportingPath.toString()).initialize()
                    }
                }

                readSingleLong(executionPath, "SELECT COUNT(*) FROM execution_order_intents") shouldBe 0L
                readSingleLong(executionPath, "SELECT COUNT(*) FROM execution_legacy_migrations") shouldBe 0L
                readSingleString(reportingPath, "SELECT submission_state FROM trades WHERE id=$tradeId") shouldBe
                    OrderSubmissionState.UNCERTAIN.name
                shouldThrow<IllegalStateException> { database.ensureReadyForSubmission() }

                val restartedDatabase = ExecutionDatabase.init(executionPath.toString(), reportingPath.toString())
                val restartedRepository = SqliteExecutionOrderIntentRepositoryImpl(restartedDatabase)
                runTest {
                    shouldThrow<IllegalStateException> {
                        ExecutionJournalBootstrap(
                            restartedDatabase,
                            restartedRepository,
                            reportingPath.toString(),
                        ).initialize()
                    }
                }

                readSingleLong(executionPath, "SELECT COUNT(*) FROM execution_order_intents") shouldBe 0L
                readSingleLong(executionPath, "SELECT COUNT(*) FROM execution_legacy_migrations") shouldBe 0L
                readSingleString(reportingPath, "SELECT submission_state FROM trades WHERE id=$tradeId") shouldBe
                    OrderSubmissionState.UNCERTAIN.name
                shouldThrow<IllegalStateException> { restartedDatabase.ensureReadyForSubmission() }
                val fake = FakeKrakenService()
                val executor = OrderExecutorImpl(
                    fake,
                    tradeHistoryService = null,
                    orderIntentService = orderIntentService(restartedRepository),
                )
                runTest {
                    shouldThrow<IllegalStateException> {
                        executeOneLiveBuy(executor, "unmatched-legacy-after-restart")
                    }
                }
                fake.executedOrders.size shouldBe 0
            }
        }

        "changed retained backup prevents restart from enabling live placement" {
            withTempDirectory { directory ->
                val reportingPath = directory.resolve("reporting.db")
                DatabaseConfig.init(reportingPath.toString())
                val executionPath = directory.resolve("execution.db")
                val firstDatabase = ExecutionDatabase.init(executionPath.toString(), reportingPath.toString())
                val firstRepository = SqliteExecutionOrderIntentRepositoryImpl(firstDatabase)
                runTest {
                    ExecutionJournalBootstrap(firstDatabase, firstRepository, reportingPath.toString()).initialize()
                }
                val retainedBackup = Path.of("$reportingPath.pre-execution-journal.bak")
                DriverManager.getConnection("jdbc:sqlite:$retainedBackup").use { connection ->
                    connection.createStatement().use { it.execute("CREATE TABLE injected_change (id INTEGER)") }
                }

                val restartedDatabase = ExecutionDatabase.init(executionPath.toString(), reportingPath.toString())
                val restartedRepository = SqliteExecutionOrderIntentRepositoryImpl(restartedDatabase)
                runTest {
                    shouldThrow<IllegalStateException> {
                        ExecutionJournalBootstrap(
                            restartedDatabase,
                            restartedRepository,
                            reportingPath.toString(),
                        ).initialize()
                    }
                }
                val fake = FakeKrakenService()
                val executor = OrderExecutorImpl(
                    fake,
                    tradeHistoryService = null,
                    orderIntentService = orderIntentService(restartedRepository),
                )
                runTest { shouldThrow<IllegalStateException> { executeOneLiveBuy(executor, "backup-changed") } }
                fake.executedOrders.size shouldBe 0
            }
        }

        "corrupt retained backup prevents startup from enabling live placement" {
            withTempDirectory { directory ->
                val reportingPath = directory.resolve("reporting.db")
                DatabaseConfig.init(reportingPath.toString())
                val executionPath = directory.resolve("execution.db")
                val firstDatabase = ExecutionDatabase.init(executionPath.toString(), reportingPath.toString())
                val firstRepository = SqliteExecutionOrderIntentRepositoryImpl(firstDatabase)
                runTest {
                    ExecutionJournalBootstrap(firstDatabase, firstRepository, reportingPath.toString()).initialize()
                }
                Files.writeString(Path.of("$reportingPath.pre-execution-journal.bak"), "not a SQLite database")

                val restartedDatabase = ExecutionDatabase.init(executionPath.toString(), reportingPath.toString())
                val restartedRepository = SqliteExecutionOrderIntentRepositoryImpl(restartedDatabase)
                runTest {
                    shouldThrow<Exception> {
                        ExecutionJournalBootstrap(
                            restartedDatabase,
                            restartedRepository,
                            reportingPath.toString(),
                        ).initialize()
                    }
                }

                shouldThrow<IllegalStateException> { restartedDatabase.ensureReadyForSubmission() }
            }
        }

        "failed reporting cutover marker leaves imported intents durable but live orders blocked" {
            withTempDirectory { directory ->
                val reportingPath = directory.resolve("reporting.db")
                val reportDatabase = DatabaseConfig.init(reportingPath.toString())
                val legacyRepository = SqliteOrderIntentRepositoryImpl(reportDatabase)
                runTest { legacyRepository.savePending(newIntent()) }
                DriverManager.getConnection("jdbc:sqlite:$reportingPath").use { connection ->
                    connection.createStatement().use { statement ->
                        statement.execute("DELETE FROM schema_migrations WHERE version=16")
                        statement.execute(
                            "CREATE TRIGGER injected_cutover_marker_failure BEFORE INSERT ON history_sync_metadata " +
                                "WHEN NEW.key LIKE 'execution-cutover:%' " +
                                "BEGIN SELECT RAISE(ABORT, 'injected cutover marker failure'); END",
                        )
                    }
                }
                val executionDatabase = ExecutionDatabase.init(
                    directory.resolve("execution.db").toString(),
                    reportingPath.toString(),
                )
                val repository = SqliteExecutionOrderIntentRepositoryImpl(executionDatabase)
                val bootstrap = ExecutionJournalBootstrap(executionDatabase, repository, reportingPath.toString())

                runTest { shouldThrow<Exception> { bootstrap.initialize() } }

                readSingleString(
                    Path.of(executionDatabase.path),
                    "SELECT state FROM execution_order_intents ORDER BY id DESC LIMIT 1",
                ) shouldBe OrderIntentState.UNCERTAIN.name
                readSingleLong(reportingPath, "SELECT COUNT(*) FROM schema_migrations WHERE version=16") shouldBe 0L
                readSingleLong(
                    reportingPath,
                    "SELECT COUNT(*) FROM history_sync_metadata WHERE key LIKE 'execution-cutover:%'",
                ) shouldBe 0L
                readSingleLong(
                    Path.of(executionDatabase.path),
                    "SELECT cutover_guard_written FROM execution_legacy_migrations",
                ) shouldBe
                    0L
                val fake = FakeKrakenService()
                val executor = OrderExecutorImpl(
                    fake,
                    tradeHistoryService = null,
                    orderIntentService = orderIntentService(repository),
                )
                runTest { shouldThrow<IllegalStateException> { executeOneLiveBuy(executor, "cutover-blocked") } }
                fake.executedOrders.size shouldBe 0

                DriverManager.getConnection("jdbc:sqlite:$reportingPath").use { connection ->
                    connection.createStatement().use { it.execute("DROP TRIGGER injected_cutover_marker_failure") }
                }
                runTest { bootstrap.initialize() }
                readSingleLong(
                    reportingPath,
                    "SELECT COUNT(*) FROM history_sync_metadata WHERE key LIKE 'execution-cutover:%'",
                ) shouldBe 1L
                readSingleLong(
                    Path.of(executionDatabase.path),
                    "SELECT cutover_guard_written FROM execution_legacy_migrations",
                ) shouldBe 1L
            }
        }

        "failed execution cutover flag update stays blocked and retries without reimporting" {
            withTempDirectory { directory ->
                val reportingPath = directory.resolve("reporting.db")
                val reportDatabase = DatabaseConfig.init(reportingPath.toString())
                var legacyId = 0
                runTest { legacyId = SqliteOrderIntentRepositoryImpl(reportDatabase).savePending(newIntent()) }
                DriverManager.getConnection("jdbc:sqlite:$reportingPath").use { connection ->
                    connection.createStatement().use { it.execute("DELETE FROM schema_migrations WHERE version=16") }
                }
                val executionPath = directory.resolve("execution.db")
                val database = ExecutionDatabase.init(executionPath.toString(), reportingPath.toString())
                val repository = SqliteExecutionOrderIntentRepositoryImpl(database)
                DriverManager.getConnection("jdbc:sqlite:$executionPath").use { connection ->
                    connection.createStatement().use {
                        it.execute(
                            "CREATE TRIGGER injected_cutover_flag_failure " +
                                "BEFORE UPDATE ON execution_legacy_migrations " +
                                "WHEN NEW.cutover_guard_written=1 " +
                                "BEGIN SELECT RAISE(IGNORE); END",
                        )
                    }
                }
                val bootstrap = ExecutionJournalBootstrap(database, repository, reportingPath.toString())

                runTest { shouldThrow<IllegalStateException> { bootstrap.initialize() } }

                readSingleLong(executionPath, "SELECT COUNT(*) FROM execution_order_intents") shouldBe 1L
                readSingleLong(executionPath, "SELECT cutover_guard_written FROM execution_legacy_migrations") shouldBe
                    0L
                shouldThrow<IllegalStateException> { database.ensureReadyForSubmission() }

                DriverManager.getConnection("jdbc:sqlite:$executionPath").use { connection ->
                    connection.createStatement().use { it.execute("DROP TRIGGER injected_cutover_flag_failure") }
                }
                runTest { bootstrap.initialize() }

                readSingleLong(executionPath, "SELECT COUNT(*) FROM execution_order_intents") shouldBe 1L
                readSingleString(
                    executionPath,
                    "SELECT state FROM execution_order_intents WHERE legacy_intent_id=$legacyId",
                ) shouldBe OrderIntentState.UNCERTAIN.name
                readSingleLong(executionPath, "SELECT cutover_guard_written FROM execution_legacy_migrations") shouldBe
                    1L
                database.ensureReadyForSubmission()
            }
        }

        "missing reporting database cannot finish an interrupted cutover" {
            withTempDirectory { directory ->
                val pathSegment = directory.resolve("path-segment")
                Files.createDirectory(pathSegment)
                val reportingPath = pathSegment.resolve("..").resolve("reporting.db")
                val reportDatabase = DatabaseConfig.init(reportingPath.toString())
                SqliteOrderIntentRepositoryImpl(reportDatabase).let { legacyRepository ->
                    runTest { legacyRepository.savePending(newIntent()) }
                }
                DriverManager.getConnection("jdbc:sqlite:$reportingPath").use { connection ->
                    connection.createStatement().use { statement ->
                        statement.execute("DELETE FROM schema_migrations WHERE version=16")
                        statement.execute(
                            "CREATE TRIGGER injected_cutover_marker_failure BEFORE INSERT ON history_sync_metadata " +
                                "WHEN NEW.key LIKE 'execution-cutover:%' " +
                                "BEGIN SELECT RAISE(ABORT, 'injected cutover marker failure'); END",
                        )
                    }
                }
                val executionDatabase = ExecutionDatabase.init(
                    directory.resolve("execution.db").toString(),
                    reportingPath.toString(),
                )
                val repository = SqliteExecutionOrderIntentRepositoryImpl(executionDatabase)
                val bootstrap = ExecutionJournalBootstrap(executionDatabase, repository, reportingPath.toString())

                runTest { shouldThrow<Exception> { bootstrap.initialize() } }
                readSingleLong(
                    Path.of(executionDatabase.path),
                    "SELECT cutover_guard_written FROM execution_legacy_migrations",
                ) shouldBe 0L
                Files.delete(reportingPath)
                listOf("-wal", "-shm").forEach { suffix ->
                    Files.deleteIfExists(Path.of("$reportingPath$suffix"))
                }

                runTest { shouldThrow<IllegalStateException> { bootstrap.initialize() } }
                shouldThrow<IllegalStateException> { executionDatabase.ensureReadyForSubmission() }
                readSingleLong(
                    Path.of(executionDatabase.path),
                    "SELECT cutover_guard_written FROM execution_legacy_migrations",
                ) shouldBe 0L
            }
        }

        "retained legacy backup blocks an empty journal if every execution witness is lost mid-cutover" {
            withTempDirectory { directory ->
                val reportingPath = directory.resolve("reporting.db")
                val reportDatabase = DatabaseConfig.init(reportingPath.toString())
                runTest { SqliteOrderIntentRepositoryImpl(reportDatabase).savePending(newIntent()) }
                DriverManager.getConnection("jdbc:sqlite:$reportingPath").use { connection ->
                    connection.createStatement().use { statement ->
                        statement.execute("DELETE FROM schema_migrations WHERE version=16")
                        statement.execute(
                            "CREATE TRIGGER injected_cutover_marker_failure BEFORE INSERT ON history_sync_metadata " +
                                "WHEN NEW.key LIKE 'execution-cutover:%' " +
                                "BEGIN SELECT RAISE(ABORT, 'injected cutover marker failure'); END",
                        )
                    }
                }
                val executionPath = directory.resolve("execution.db")
                val executionDatabase = ExecutionDatabase.init(executionPath.toString(), reportingPath.toString())
                val repository = SqliteExecutionOrderIntentRepositoryImpl(executionDatabase)
                val bootstrap = ExecutionJournalBootstrap(executionDatabase, repository, reportingPath.toString())
                runTest { shouldThrow<Exception> { bootstrap.initialize() } }

                Files.delete(executionPath)
                Files.delete(Path.of("$executionPath.journal-id"))
                Files.delete(Path.of("$reportingPath.execution-journal-witness"))
                listOf("-wal", "-shm").forEach { suffix ->
                    Files.deleteIfExists(Path.of("$executionPath$suffix"))
                }
                Files.isRegularFile(Path.of("$reportingPath.pre-execution-journal.bak")) shouldBe true

                shouldThrow<IOException> {
                    ExecutionDatabase.init(executionPath.toString(), reportingPath.toString())
                }
                Files.exists(executionPath) shouldBe false
            }
        }

        "report projection failure retries idempotently without writing the execution journal" {
            withTempDirectory { directory ->
                val reportPath = directory.resolve("reporting.db")
                val (executionDatabase, executionRepository) = readyJournal(
                    directory.resolve("execution.db"),
                    reportPath,
                )
                val reportDatabase = DatabaseConfig.init(reportPath.toString())
                val tradeRepository = SqliteTradeRepositoryImpl(reportDatabase)
                var intentId = 0
                runTest { intentId = executionRepository.savePending(newIntent()) }
                runTest {
                    executionRepository.recordOutcome(
                        intentId,
                        OrderIntentState.CONFIRMED,
                        "TX-PROJECTION",
                        null,
                        Instant.now(),
                        newIntent().volume,
                    )
                }
                val projector = TradeProjectionServiceImpl(executionRepository, tradeRepository)
                DriverManager.getConnection("jdbc:sqlite:$reportPath").use { connection ->
                    connection.createStatement().use {
                        it.execute(
                            "CREATE TRIGGER injected_report_failure BEFORE INSERT ON trades " +
                                "BEGIN SELECT RAISE(ABORT, 'injected report failure'); END",
                        )
                    }
                }

                runTest { shouldThrow<Exception> { projector.projectPending() } }
                readSingleLong(
                    Path.of(executionDatabase.path),
                    "SELECT COUNT(*) FROM execution_projection_outbox",
                ) shouldBe
                    1L
                readSingleString(
                    Path.of(executionDatabase.path),
                    "SELECT state FROM execution_order_intents WHERE id=$intentId",
                ) shouldBe OrderIntentState.CONFIRMED.name
                readSingleString(
                    Path.of(executionDatabase.path),
                    "SELECT order_txid FROM execution_order_intents WHERE id=$intentId",
                ) shouldBe "TX-PROJECTION"

                DriverManager.getConnection("jdbc:sqlite:$reportPath").use { connection ->
                    connection.createStatement().use { it.execute("DROP TRIGGER injected_report_failure") }
                }
                DriverManager.getConnection("jdbc:sqlite:${executionDatabase.path}").use { connection ->
                    connection.createStatement().use {
                        it.execute(
                            "CREATE TRIGGER execution_outbox_is_immutable BEFORE UPDATE " +
                                "ON execution_projection_outbox BEGIN SELECT RAISE(ABORT, 'outbox update forbidden'); END",
                        )
                    }
                }

                var projectedCount = 0
                val restartedProjector = TradeProjectionServiceImpl(executionRepository, tradeRepository)
                runTest { projectedCount = restartedProjector.projectPending() }
                projectedCount shouldBe 1
                var reportRowsBeforeRetry = emptyList<com.gemini.krakenbot.model.TradeRecord>()
                runTest {
                    reportRowsBeforeRetry =
                        tradeRepository.getTradesInRange(Instant.EPOCH, Instant.now().plusSeconds(60))
                }
                reportRowsBeforeRetry.size shouldBe 1

                runTest { projectedCount = restartedProjector.projectPending() }
                projectedCount shouldBe 0
                var reportRowsAfterRetry = emptyList<com.gemini.krakenbot.model.TradeRecord>()
                runTest {
                    reportRowsAfterRetry =
                        tradeRepository.getTradesInRange(Instant.EPOCH, Instant.now().plusSeconds(60))
                }
                reportRowsAfterRetry.size shouldBe 1
                reportRowsAfterRetry.single().orderTxid shouldBe "TX-PROJECTION"
                readSingleLong(
                    reportPath,
                    "SELECT CAST(value AS INTEGER) FROM history_sync_metadata " +
                        "WHERE key='execution-projection:${executionDatabase.journalId()}'",
                ) shouldBe 1L
            }
        }

        "application wiring injects a ready execution journal for production live submission" {
            withTempDirectory { directory ->
                val reportingPath = directory.resolve("reporting.db").toString()
                val executionPath = directory.resolve("execution.db").toString()
                val previousReportingPath = System.getProperty("kraken.db.path")
                val previousExecutionPath = System.getProperty("kraken.execution.db.path")
                System.setProperty("kraken.db.path", reportingPath)
                System.setProperty("kraken.execution.db.path", executionPath)
                Files.writeString(
                    directory.resolve("rebalancer-config.json"),
                    """
                    {
                      "kraken": {
                        "apiKey": "${TestFixtures.TRADE_HISTORY_API_KEY}",
                        "privateKey": "${TestFixtures.TRADE_HISTORY_API_SECRET}"
                      },
                      "settings": {
                        "loopDelaySeconds": 60,
                        "deviationTriggerPercent": 2.0,
                        "minimumOrderSizeUSD": 5.0,
                        "dryRun": false,
                        "simulation": false
                      },
                      "allocations": [
                        {"symbol": "BTC", "targetPercent": 50.0},
                        {"symbol": "ETH", "targetPercent": 30.0},
                        {"symbol": "USD", "targetPercent": 20.0}
                      ]
                    }
                    """.trimIndent(),
                )
                val fake = FakeKrakenService()
                val application = koinApplication {
                    modules(
                        appModule,
                        module {
                            single<KrakenService> { fake }
                            single<ConfigService> {
                                ConfigServiceImpl(
                                    objectMapper = get(),
                                    configFilePath = directory.resolve("rebalancer-config.json").toString(),
                                )
                            }
                        },
                    )
                }
                try {
                    val bootstrap = application.koin.get<ExecutionJournalBootstrap>()
                    val executor = application.koin.get<OrderExecutor>()
                    runTest {
                        application.koin.get<ConfigService>().updateConfig(
                            TestFixtures.DEFAULT_TEST_CONFIG.copy(
                                kraken = KrakenCredentials(
                                    TestFixtures.TRADE_HISTORY_API_KEY,
                                    TestFixtures.TRADE_HISTORY_API_SECRET,
                                ),
                                settings = TestFixtures.settings(
                                    dryRun = false,
                                    simulation = false,
                                    loopDelaySeconds = 60L,
                                ),
                            ),
                        )
                        bootstrap.initialize()
                        executeOneLiveBuy(executor)
                    }
                    fake.executedOrders.size shouldBe 1
                    runTest {
                        application.koin.get<ExecutionOrderIntentRepository>().countUnresolvedIntents() shouldBe 0L
                    }
                } finally {
                    application.close()
                    restoreSystemProperty("kraken.db.path", previousReportingPath)
                    restoreSystemProperty("kraken.execution.db.path", previousExecutionPath)
                }
            }
        }

        "execution journal rejects a path that aliases the reporting database" {
            withTempDirectory { directory ->
                val sharedPath = directory.resolve("shared.db")
                val previousReportingPath = System.getProperty("kraken.db.path")
                val previousExecutionPath = System.getProperty("kraken.execution.db.path")
                System.setProperty("kraken.db.path", sharedPath.toString())
                System.setProperty(
                    "kraken.execution.db.path",
                    directory.resolve(".").resolve("shared.db").toString(),
                )

                try {
                    shouldThrow<IllegalArgumentException> { ExecutionDatabase.init() }
                } finally {
                    restoreSystemProperty("kraken.db.path", previousReportingPath)
                    restoreSystemProperty("kraken.execution.db.path", previousExecutionPath)
                }

                Files.exists(sharedPath) shouldBe false
            }
        }

        "execution journal rejects a symlink alias and a shared named-memory database" {
            withTempDirectory { directory ->
                val reportPath = directory.resolve("reporting.db")
                val aliasPath = directory.resolve("reporting-alias.db")
                Files.createFile(reportPath)
                Files.createSymbolicLink(aliasPath, reportPath.fileName)

                shouldThrow<IllegalArgumentException> {
                    ExecutionDatabase.init(aliasPath.toString(), reportPath.toString())
                }
                shouldThrow<IllegalArgumentException> {
                    ExecutionDatabase.init(
                        "file:shared-memory?mode=memory&cache=shared",
                        "jdbc:sqlite:file:shared-memory?mode=memory&cache=shared",
                    )
                }
            }
        }

        "legacy migration identity remains stable when reporting storage is opened through a symlink" {
            withTempDirectory { directory ->
                val reportingPath = directory.resolve("reporting.db")
                val reportingAlias = directory.resolve("reporting-alias.db")
                val executionPath = directory.resolve("execution.db")
                val reportingDatabase = DatabaseConfig.init(reportingPath.toString())
                runTest { SqliteOrderIntentRepositoryImpl(reportingDatabase).savePending(newIntent()) }
                Files.createSymbolicLink(reportingAlias, reportingPath.fileName)

                val database = ExecutionDatabase.init(executionPath.toString(), reportingPath.toString())
                val repository = SqliteExecutionOrderIntentRepositoryImpl(database)
                runTest {
                    ExecutionJournalBootstrap(database, repository, reportingPath.toString()).initialize()
                }
                readSingleLong(executionPath, "SELECT COUNT(*) FROM execution_order_intents") shouldBe 1L

                val restartedDatabase = ExecutionDatabase.init(executionPath.toString(), reportingAlias.toString())
                runTest {
                    ExecutionJournalBootstrap(
                        restartedDatabase,
                        SqliteExecutionOrderIntentRepositoryImpl(restartedDatabase),
                        reportingAlias.toString(),
                    ).initialize()
                }

                readSingleLong(executionPath, "SELECT COUNT(*) FROM execution_order_intents") shouldBe 1L
                readSingleLong(executionPath, "SELECT COUNT(*) FROM execution_legacy_migrations") shouldBe 1L
                database.journalId() shouldBe restartedDatabase.journalId()
            }
        }

        "execution journal honors an independent witness path and rejects mismatched identities" {
            withTempDirectory { directory ->
                val executionPath = directory.resolve("execution.db")
                val reportingPath = directory.resolve("reporting.db")
                val witnessPath = directory.resolve("outside").resolve("witness.dat")
                val previousWitnessPath = System.getProperty("kraken.execution.witness.path")
                System.setProperty("kraken.execution.witness.path", witnessPath.toString())

                try {
                    val database = ExecutionDatabase.init(executionPath.toString(), reportingPath.toString())
                    Files.isRegularFile(witnessPath) shouldBe true
                    Files.readString(witnessPath).trim() shouldBe database.journalId()
                    Files.writeString(Path.of("$executionPath.journal-id"), "different-journal")

                    shouldThrow<IllegalStateException> {
                        ExecutionDatabase.init(executionPath.toString(), reportingPath.toString())
                    }
                } finally {
                    restoreSystemProperty("kraken.execution.witness.path", previousWitnessPath)
                }
            }
        }

        "execution journal refuses to alias its independent witness with database files or sidecars" {
            withTempDirectory { directory ->
                val executionPath = directory.resolve("execution.db")
                val reportingPath = directory.resolve("reporting.db")
                val previousWitnessPath = System.getProperty("kraken.execution.witness.path")

                try {
                    System.setProperty("kraken.execution.witness.path", executionPath.toString())
                    shouldThrow<IllegalArgumentException> {
                        ExecutionDatabase.init(executionPath.toString(), reportingPath.toString())
                    }
                    Files.exists(executionPath) shouldBe false

                    listOf(
                        reportingPath,
                        Path.of("$reportingPath-wal"),
                        Path.of("$reportingPath-shm"),
                        Path.of("$reportingPath-journal"),
                        Path.of("$executionPath-wal"),
                        Path.of("$executionPath-shm"),
                        Path.of("$executionPath-journal"),
                    ).forEach { alias ->
                        System.setProperty("kraken.execution.witness.path", alias.toString())
                        shouldThrow<IllegalArgumentException> {
                            ExecutionDatabase.init(executionPath.toString(), reportingPath.toString())
                        }
                        Files.exists(executionPath) shouldBe false
                        Files.exists(reportingPath) shouldBe false
                    }

                    val existingReportSidecar = Path.of("$reportingPath-shm")
                    Files.writeString(existingReportSidecar, "reserved SQLite sidecar")
                    System.setProperty("kraken.execution.witness.path", existingReportSidecar.toString())
                    shouldThrow<IllegalArgumentException> {
                        ExecutionDatabase.init(executionPath.toString(), reportingPath.toString())
                    }
                    Files.exists(executionPath) shouldBe false
                    Files.exists(reportingPath) shouldBe false
                    Files.isRegularFile(existingReportSidecar) shouldBe true
                } finally {
                    restoreSystemProperty("kraken.execution.witness.path", previousWitnessPath)
                }
            }
        }

        "execution journal resolves explicit, sibling and shared-memory paths" {
            withTempDirectory { directory ->
                val reportingPath = directory.resolve("kraken.db")
                val previousReportingPath = System.getProperty("kraken.db.path")
                val previousExecutionPath = System.getProperty("kraken.execution.db.path")

                try {
                    System.setProperty("kraken.db.path", reportingPath.toString())
                    System.clearProperty("kraken.execution.db.path")
                    ExecutionDatabase.resolveExecutionDatabasePath() shouldBe
                        directory.resolve("kraken-execution.db").toString()

                    System.setProperty("kraken.db.path", ":memory:")
                    ExecutionDatabase.resolveExecutionDatabasePath() shouldBe ":memory:"

                    System.setProperty("kraken.db.path", reportingPath.toString())
                    System.setProperty("kraken.execution.db.path", " ")
                    ExecutionDatabase.resolveExecutionDatabasePath() shouldBe
                        directory.resolve("kraken-execution.db").toString()

                    val explicitExecution = directory.resolve("explicit-execution.db").toString()
                    System.setProperty("kraken.execution.db.path", explicitExecution)
                    ExecutionDatabase.resolveExecutionDatabasePath() shouldBe explicitExecution
                } finally {
                    restoreSystemProperty("kraken.db.path", previousReportingPath)
                    restoreSystemProperty("kraken.execution.db.path", previousExecutionPath)
                }
            }
        }

        "execution database accepts a JDBC path and validates its persistent journal mode" {
            withTempDirectory { directory ->
                val executionPath = directory.resolve("execution.db")
                val reportingPath = directory.resolve("reporting.db")
                val database = ExecutionDatabase.init("jdbc:sqlite:$executionPath", reportingPath.toString())

                database.journalId().isNotBlank() shouldBe true
                DriverManager.getConnection("jdbc:sqlite:$executionPath").use { connection ->
                    connection.createStatement().use { statement ->
                        statement.executeQuery("PRAGMA journal_mode=DELETE").use { it.next() shouldBe true }
                    }
                }

                shouldThrow<IllegalStateException> { database.connect() }
            }
        }

        "empty or not-yet-migrated SQLite files do not look like committed execution journals" {
            withTempDirectory { directory ->
                val executionPath = directory.resolve("execution.db")
                val reportingPath = directory.resolve("reporting.db")
                DriverManager.getConnection("jdbc:sqlite:$executionPath").use { connection ->
                    connection.createStatement().use { it.execute("CREATE TABLE unrelated (id INTEGER)") }
                }

                val database = ExecutionDatabase.init(executionPath.toString(), reportingPath.toString())
                database.journalId().isNotBlank() shouldBe true
                Files.isRegularFile(Path.of("$executionPath.journal-id")) shouldBe true

                val noSchemaPath = directory.resolve("no-schema.db")
                DriverManager.getConnection("jdbc:sqlite:$noSchemaPath").use { }
                val noSchemaDatabase = ExecutionDatabase.init(
                    noSchemaPath.toString(),
                    directory.resolve("no-schema-reporting.db").toString(),
                )
                noSchemaDatabase.journalId().isNotBlank() shouldBe true
            }
        }

        "v1 execution journals migrate to an unbound execution account anchor" {
            withTempDirectory { directory ->
                val reportingPath = directory.resolve("reporting.db")
                val executionPath = directory.resolve("execution.db")
                DatabaseConfig.init(reportingPath.toString())
                val beforeMigration = ExecutionDatabase.init(executionPath.toString(), reportingPath.toString())
                val originalJournalId = beforeMigration.journalId()
                DriverManager.getConnection("jdbc:sqlite:$executionPath").use { connection ->
                    connection.createStatement().use { statement ->
                        statement.execute("DROP TABLE execution_account_binding_audit")
                        statement.execute("DROP TABLE execution_account_binding")
                        statement.execute(
                            "ALTER TABLE execution_journal_metadata " +
                                "DROP COLUMN account_binding_initialized",
                        )
                        statement.execute("DELETE FROM execution_schema_migrations WHERE version=2")
                    }
                }

                val upgraded = ExecutionDatabase.init(executionPath.toString(), reportingPath.toString())

                upgraded.journalId() shouldBe originalJournalId
                readSingleLong(
                    executionPath,
                    "SELECT COUNT(*) FROM execution_schema_migrations WHERE version=2 AND name='execution-account-binding'",
                ) shouldBe 1L
                readSingleLong(
                    executionPath,
                    "SELECT account_binding_initialized FROM execution_journal_metadata WHERE singleton_id=1",
                ) shouldBe 0L
            }
        }

        "new execution journal refuses a schema version written by a newer binary" {
            withTempDirectory { directory ->
                val executionPath = directory.resolve("future.db")
                DriverManager.getConnection("jdbc:sqlite:$executionPath").use { connection ->
                    connection.createStatement().use {
                        it.execute(
                            "CREATE TABLE execution_schema_migrations " +
                                "(version INTEGER PRIMARY KEY, name TEXT NOT NULL, applied_at INTEGER NOT NULL)",
                        )
                        it.execute(
                            "INSERT INTO execution_schema_migrations(version, name, applied_at) " +
                                "VALUES (3, 'future', 0)",
                        )
                    }
                }

                shouldThrow<IllegalStateException> {
                    ExecutionDatabase.init(executionPath.toString(), directory.resolve("reporting.db").toString())
                }
            }
        }

        "empty reporting files permit identity repair while corrupted reporting files fail closed" {
            withTempDirectory { directory ->
                val executionPath = directory.resolve("execution.db")
                val reportingPath = directory.resolve("reporting.db")
                val (database, _) = readyJournal(executionPath, reportingPath)
                val marker = Path.of("$executionPath.journal-id")
                Files.delete(marker)
                Files.createFile(reportingPath)

                ExecutionDatabase.init(executionPath.toString(), reportingPath.toString())
                Files.isRegularFile(marker) shouldBe true

                Files.delete(marker)
                Files.writeString(reportingPath, "not a SQLite database")
                shouldThrow<IOException> {
                    ExecutionDatabase.init(executionPath.toString(), reportingPath.toString())
                }
                Files.exists(marker) shouldBe false
                database.journalId().isNotBlank() shouldBe true
            }
        }

        "execution journal reports a missing identity row instead of inventing an id" {
            withTempDirectory { directory ->
                val (database, _) = readyJournal(directory.resolve("execution.db"))
                DriverManager.getConnection("jdbc:sqlite:${database.path}").use { connection ->
                    connection.createStatement().use { it.execute("DELETE FROM execution_journal_metadata") }
                }

                shouldThrow<IllegalStateException> { database.journalId() }
            }
        }

        "simulation reporting initialization failure is propagated to the caller" {
            val historyService = mockk<TradeHistoryService>(relaxed = true)
            coEvery { historyService.init() } throws IOException("reporting database unavailable")
            val dispatcher = ReportingDispatcher(
                historyServiceProvider = { historyService },
                projectionServiceProvider = { error("projection service is not needed for initialization") },
            )

            runTest { shouldThrow<IOException> { dispatcher.initializeBeforeSimulationCycle() } }

            coVerify(exactly = 1) { historyService.init() }
        }

        "simulation does not read account state before reporting initialization succeeds" {
            val allocations = listOf(
                Allocation(Asset.BTC, 40.0),
                Allocation(Asset.ETH, 40.0),
                Allocation(Asset.USD, 20.0),
            )
            val config = TestFixtures.config(
                settings = TestFixtures.settings(simulation = true, dryRun = false),
                allocations = allocations,
            )
            val configService = mockk<ConfigService>()
            every { configService.getConfig() } returns config
            val reporting = mockk<TradeHistoryService>(relaxed = true)
            coEvery { reporting.init() } throws IOException("simulation seed unavailable")
            var balanceReads = 0
            val kraken = FakeKrakenService().apply {
                balanceSupplier = {
                    balanceReads += 1
                    mapOf(Asset.USD to 1000.0)
                }
                pricesSupplier = { mapOf(Asset.BTC_USD_PAIR to 100.0, Asset.ETH_USD_PAIR to 100.0) }
            }
            val analyzer = PortfolioAnalyzerImpl(kraken, configService, mockk(relaxed = true))
            val manager = PortfolioManagerImpl(
                configService = configService,
                portfolioAnalyzer = analyzer,
                orderExecutor = OrderExecutorImpl(kraken, tradeHistoryService = null),
                krakenService = kraken,
                reportingDispatcher = ReportingDispatcher(
                    historyServiceProvider = { reporting },
                    projectionServiceProvider = { error("projection service is not needed for this test") },
                ),
            )

            runTest { shouldThrow<IOException> { manager.performRebalanceCycle() } }

            balanceReads shouldBe 0
        }

        "fixed cash dry-run plan ignores failed history and ATH reads" {
            withTempDirectory { _ ->
                val allocations = listOf(
                    Allocation(Asset.BTC, 40.0),
                    Allocation(Asset.ETH, 40.0),
                    Allocation(Asset.USD, 20.0),
                )
                val settings = TestFixtures.settings(
                    dryRun = true,
                    simulation = false,
                    fiatMaxDrawdown = 20.0,
                    fiatDeploymentExponent = 1.0,
                )
                val config = TestFixtures.config(settings = settings, allocations = allocations)
                val configService = mockk<ConfigService>()
                every { configService.getConfig() } returns config
                val reporting = mockk<TradeHistoryService>(relaxed = true)
                coEvery { reporting.syncTradesFromKraken() } throws IllegalStateException("history database locked")
                coEvery { reporting.syncLedgersFromKraken() } throws IllegalStateException("history database locked")
                coEvery { reporting.rebuildHistoricalSnapshotsIfNeeded(any()) } throws
                    IllegalStateException("history database locked")
                val stats = mockk<PortfolioStatsRepository>(relaxed = true)
                coEvery { stats.load() } throws IllegalStateException("ATH database unavailable")
                val kraken = FakeKrakenService().apply {
                    balanceSupplier = { mapOf(Asset.USD to 1000.0) }
                    pricesSupplier = { mapOf(Asset.BTC_USD_PAIR to 100.0, Asset.ETH_USD_PAIR to 100.0) }
                }
                val analyzer = PortfolioAnalyzerImpl(kraken, configService, stats)
                val executor = OrderExecutorImpl(kraken, reporting)
                val manager = PortfolioManagerImpl(
                    configService = configService,
                    portfolioAnalyzer = analyzer,
                    orderExecutor = executor,
                    krakenService = kraken,
                    reportingDispatcher = ReportingDispatcher({
                        error("reporting unavailable")
                    }, { error("reporting unavailable") }),
                )

                runTest { manager.performRebalanceCycle() }

                val currentValues = mapOf(Asset.USD to java.math.BigDecimal("1000"))
                val oldDeployment = RebalancerEngine.calculateFiatDeployment(
                    java.math.BigDecimal("20"),
                    settings,
                )
                val oldEffectiveUsd = RebalancerEngine.calculateEffectiveUsdTarget(oldDeployment, allocations)
                val oldCryptoScale = RebalancerEngine.calculateCryptoScaleFactor(oldEffectiveUsd, allocations)
                val oldTargets = RebalancerEngine.analyzeDeviationsPlan(
                    totalPortfolioValueUSD = java.math.BigDecimal("1000"),
                    currentValuesUSD = currentValues,
                    effectiveUsdTarget = oldEffectiveUsd,
                    cryptoScaleFactor = oldCryptoScale,
                    allocations = allocations,
                    settings = settings,
                )
                oldTargets.buyOrders[Asset.BTC]!!.shouldBeEqualComparingTo(java.math.BigDecimal("500.00"))
                oldTargets.buyOrders[Asset.ETH]!!.shouldBeEqualComparingTo(java.math.BigDecimal("500.00"))

                kraken.executedOrders.map { it.side to it.volume.multiply(java.math.BigDecimal("100")) } shouldBe
                    listOf(
                        "buy" to java.math.BigDecimal("400.00000000"),
                        "buy" to java.math.BigDecimal("400.00000000"),
                    )
                coVerify(exactly = 0) { reporting.syncTradesFromKraken() }
                coVerify(exactly = 0) { reporting.syncLedgersFromKraken() }
                coVerify(exactly = 0) { reporting.rebuildHistoricalSnapshotsIfNeeded(any()) }
                coVerify(exactly = 0) { stats.load() }
            }
        }
    }

    private fun readyJournal(
        path: Path,
        reportingPath: Path = path.resolveSibling("reporting-unused.db"),
    ): Pair<ExecutionDatabase, SqliteExecutionOrderIntentRepositoryImpl> {
        val database = ExecutionDatabase.init(path.toString(), reportingPath.toString())
        database.markReadyForSubmission()
        return database to SqliteExecutionOrderIntentRepositoryImpl(database)
    }

    private fun orderIntentService(repository: ExecutionOrderIntentRepository) =
        OrderIntentServiceImpl(repository, ExecutionAccountBindingVerifier { })

    private suspend fun executeOneLiveBuy(executor: OrderExecutor, cycleId: String = "live-cycle") =
        executor.executeOrders(
            buyOrders = mapOf(Asset.BTC to java.math.BigDecimal("25.00")),
            sellOrders = emptyMap(),
            currentValuesUSD = mapOf(Asset.USD to java.math.BigDecimal("100.00")),
            prices = mapOf(Asset.BTC to java.math.BigDecimal("1000.00")),
            settings = TestFixtures.settings(dryRun = false, simulation = false),
            actionLog = mutableListOf(),
            cycleId = cycleId,
        )

    private fun newIntent() = OrderIntent(
        cycleId = "migration-cycle",
        clientOrderId = "client-migration-1",
        pair = "XBTUSD",
        symbol = Asset.BTC,
        side = "BUY",
        volume = java.math.BigDecimal("0.02500000"),
        usdAmount = java.math.BigDecimal("25.00000000"),
        expectedPrice = java.math.BigDecimal("1000.00000000"),
        createdAt = Instant.parse("2026-10-08T12:00:00Z"),
        state = OrderIntentState.PENDING,
    )

    private fun legacyPendingTrade(intent: OrderIntent) = TradeRecord(
        timestamp = intent.createdAt,
        pair = intent.pair,
        side = intent.side,
        symbol = intent.symbol,
        volume = intent.volume,
        usdAmount = intent.usdAmount,
        success = false,
        dryRun = false,
        expectedPrice = intent.expectedPrice,
        cycleId = intent.cycleId,
        clientOrderId = intent.clientOrderId,
        submissionState = OrderSubmissionState.PENDING,
    )

    private fun readSingleString(databasePath: Path, sql: String): String =
        DriverManager.getConnection("jdbc:sqlite:$databasePath").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(sql).use { resultSet ->
                    check(resultSet.next()) { "Query returned no rows: $sql" }
                    resultSet.getString(1)
                }
            }
        }

    private fun readSingleLong(databasePath: Path, sql: String): Long =
        DriverManager.getConnection("jdbc:sqlite:$databasePath").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(sql).use { resultSet ->
                    check(resultSet.next()) { "Query returned no rows: $sql" }
                    resultSet.getLong(1)
                }
            }
        }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }

    private fun restoreSystemProperty(key: String, value: String?) {
        if (value == null) System.clearProperty(key) else System.setProperty(key, value)
    }

    private class SimulatedProcessCrash : Error()

    private fun withTempDirectory(block: (Path) -> Unit) {
        val directory = Files.createTempDirectory("execution-journal-cutover-")
        try {
            block(directory)
        } finally {
            Files.walk(directory).sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
    }
}
