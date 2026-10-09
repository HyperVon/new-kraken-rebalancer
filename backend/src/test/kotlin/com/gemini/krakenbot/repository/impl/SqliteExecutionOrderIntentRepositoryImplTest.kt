package com.gemini.krakenbot.repository.impl

import com.gemini.krakenbot.config.ExecutionDatabase
import com.gemini.krakenbot.model.Asset
import com.gemini.krakenbot.model.OrderIntent
import com.gemini.krakenbot.model.OrderIntentReconciliationException
import com.gemini.krakenbot.model.OrderIntentState
import com.gemini.krakenbot.model.RebalancerOrderIdentities
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.Comparator

class SqliteExecutionOrderIntentRepositoryImplTest : StringSpec() {
    override fun isolationMode() = IsolationMode.InstancePerTest

    init {
        "pending intent becomes durable uncertainty and terminal resolution" {
            runTest {
                withRepository { database, repository ->
                    val intentId = repository.savePending(intent())

                    repository.hasUnresolvedIntents() shouldBe true
                    repository.countUnresolvedIntents() shouldBe 1L
                    repository.loadUnresolvedIntents().single().state shouldBe OrderIntentState.PENDING
                    repository.loadProjectionEvents(0, 10) shouldBe emptyList()

                    repository.recordOutcome(
                        intentId,
                        state = OrderIntentState.UNCERTAIN,
                        orderTxid = null,
                        errorMessage = "response lost",
                        resolvedAt = Instant.parse("2026-10-08T12:00:30Z"),
                        outcomeVolume = BigDecimal("0.025"),
                    ) shouldBe true

                    val uncertain = repository.loadUnresolvedIntents().single()
                    uncertain.state shouldBe OrderIntentState.UNCERTAIN
                    uncertain.errorMessage shouldBe "response lost"
                    uncertain.clientOrderIdAmbiguous shouldBe true
                    repository.countUnresolvedIntents() shouldBe 1L
                    repository.resolve(
                        id = intentId,
                        state = OrderIntentState.CONFIRMED,
                        evidence = "operator found Kraken order",
                        resolvedAt = Instant.parse("2026-10-08T12:01:00Z"),
                        orderTxid = "OID-1",
                    ) shouldBe true

                    repository.hasUnresolvedIntents() shouldBe false
                    repository.loadUnresolvedIntents() shouldBe emptyList()
                    val events = repository.loadProjectionEvents(0, 10)
                    events.map { it.revision } shouldBe listOf(1L, 2L)
                    events.all { it.intent.state == OrderIntentState.CONFIRMED } shouldBe true
                    events.last().intent.resolutionEvidence shouldBe "operator found Kraken order"
                    repository.recordOutcome(
                        intentId,
                        state = OrderIntentState.CONFIRMED,
                        orderTxid = "OID-1",
                        errorMessage = null,
                        resolvedAt = Instant.parse("2026-10-08T12:00:45Z"),
                        outcomeVolume = BigDecimal("0.025"),
                    ) shouldBe false
                    database.journalId().isNotBlank() shouldBe true
                }
            }
        }

        "terminal outcomes are idempotent while unknown or invalid transitions fail" {
            runTest {
                withRepository { _, repository ->
                    val terminalId = repository.savePending(intent(clientOrderId = "client-terminal"))
                    repository.recordOutcome(
                        terminalId,
                        state = OrderIntentState.CONFIRMED,
                        orderTxid = "OID-TERM",
                        errorMessage = null,
                        resolvedAt = Instant.parse("2026-10-08T12:00:30Z"),
                    ) shouldBe true
                    repository.recordOutcome(
                        terminalId,
                        state = OrderIntentState.CONFIRMED,
                        orderTxid = "OID-TERM",
                        errorMessage = null,
                        resolvedAt = Instant.parse("2026-10-08T12:00:30Z"),
                    ) shouldBe false

                    val rejectedId = repository.savePending(intent(clientOrderId = "client-rejected"))
                    repository.recordOutcome(
                        rejectedId,
                        state = OrderIntentState.REJECTED,
                        orderTxid = null,
                        errorMessage = "rejected",
                        resolvedAt = Instant.parse("2026-10-08T12:00:30Z"),
                    ) shouldBe true
                    repository.recordOutcome(
                        rejectedId,
                        state = OrderIntentState.REJECTED,
                        orderTxid = null,
                        errorMessage = "rejected again",
                        resolvedAt = Instant.parse("2026-10-08T12:00:31Z"),
                    ) shouldBe false

                    shouldThrow<IllegalArgumentException> {
                        repository.recordOutcome(
                            terminalId,
                            state = OrderIntentState.PENDING,
                            orderTxid = null,
                            errorMessage = "pending",
                            resolvedAt = null,
                        )
                    }
                    shouldThrow<OrderIntentReconciliationException> {
                        repository.recordOutcome(
                            terminalId + 100,
                            state = OrderIntentState.UNCERTAIN,
                            orderTxid = null,
                            errorMessage = "missing",
                            resolvedAt = Instant.now(),
                        )
                    }
                    repository.resolve(
                        id = terminalId,
                        state = OrderIntentState.REJECTED,
                        evidence = "duplicate resolution",
                        resolvedAt = Instant.parse("2026-10-08T12:02:00Z"),
                    ) shouldBe false
                    shouldThrow<IllegalArgumentException> {
                        repository.resolve(
                            id = terminalId,
                            state = OrderIntentState.PENDING,
                            evidence = "not terminal",
                            resolvedAt = Instant.parse("2026-10-08T12:02:00Z"),
                        )
                    }
                }
            }
        }

        "known order lookup trims inputs and scans multiple bounded chunks" {
            runTest {
                withRepository { _, repository ->
                    val intentId = repository.savePending(intent(clientOrderId = "client-known"))
                    repository.recordOutcome(
                        intentId,
                        state = OrderIntentState.CONFIRMED,
                        orderTxid = "OID-KNOWN",
                        errorMessage = null,
                        resolvedAt = Instant.parse("2026-10-08T12:00:30Z"),
                    )

                    repository.getKnownRebalancerOrderIdentities(emptySet(), emptySet()) shouldBe
                        RebalancerOrderIdentities()
                    val clientIds = (0..500).map { "client-$it" }.toMutableSet().apply {
                        add(" client-known ")
                    }
                    repository.getKnownRebalancerOrderIdentities(
                        orderTxids = setOf(" OID-KNOWN ", "", "  ", "not-known"),
                        clientOrderIds = clientIds,
                    ) shouldBe RebalancerOrderIdentities(setOf("OID-KNOWN"))
                    repository.getKnownRebalancerOrderIdentities(
                        orderTxids = setOf(" OID-KNOWN "),
                        clientOrderIds = emptySet(),
                    ) shouldBe RebalancerOrderIdentities(setOf("OID-KNOWN"))
                    repository.getKnownRebalancerOrderIdentities(
                        orderTxids = emptySet(),
                        clientOrderIds = setOf(" client-known "),
                    ) shouldBe RebalancerOrderIdentities(setOf("OID-KNOWN"))

                    val nullTxidId = repository.savePending(
                        intent(clientOrderId = "client-null-txid", expectedPrice = null),
                    )
                    repository.recordOutcome(
                        nullTxidId,
                        state = OrderIntentState.CONFIRMED,
                        orderTxid = null,
                        errorMessage = null,
                        resolvedAt = Instant.parse("2026-10-08T12:01:00Z"),
                    ) shouldBe true
                    val blankTxidId = repository.savePending(
                        intent(clientOrderId = "client-blank-txid", expectedPrice = null),
                    )
                    repository.recordOutcome(
                        blankTxidId,
                        state = OrderIntentState.CONFIRMED,
                        orderTxid = "  ",
                        errorMessage = null,
                        resolvedAt = Instant.parse("2026-10-08T12:01:00Z"),
                    ) shouldBe true
                    repository.getKnownRebalancerOrderIdentities(
                        orderTxids = emptySet(),
                        clientOrderIds = setOf("client-null-txid", "client-blank-txid"),
                    ) shouldBe RebalancerOrderIdentities()
                    repository.loadProjectionEvents(0, 10).filter { it.intent.id in setOf(nullTxidId, blankTxidId) }
                        .all { it.intent.expectedPrice == null } shouldBe true
                }
            }
        }

        "a failed reporting outbox insert rolls back the outcome and permits a safe retry" {
            runTest {
                withRepository { database, repository ->
                    val intentId = repository.savePending(intent())
                    database.connect().use { connection ->
                        connection.createStatement().use { statement ->
                            statement.execute(
                                "CREATE TRIGGER fail_projection_insert BEFORE INSERT ON execution_projection_outbox " +
                                    "BEGIN SELECT RAISE(IGNORE); END",
                            )
                        }
                    }

                    shouldThrow<IllegalStateException> {
                        repository.recordOutcome(
                            intentId,
                            state = OrderIntentState.CONFIRMED,
                            orderTxid = "OID-OUTBOX-RETRY",
                            errorMessage = null,
                            resolvedAt = Instant.parse("2026-10-08T12:00:30Z"),
                        )
                    }
                    repository.loadUnresolvedIntents().single().state shouldBe OrderIntentState.PENDING
                    repository.loadProjectionEvents(0, 10) shouldBe emptyList()

                    database.connect().use { connection ->
                        connection.createStatement().use { it.execute("DROP TRIGGER fail_projection_insert") }
                    }
                    repository.recordOutcome(
                        intentId,
                        state = OrderIntentState.CONFIRMED,
                        orderTxid = "OID-OUTBOX-RETRY",
                        errorMessage = null,
                        resolvedAt = Instant.parse("2026-10-08T12:00:30Z"),
                    ) shouldBe true
                    repository.loadProjectionEvents(0, 10).single().intent.state shouldBe OrderIntentState.CONFIRMED
                }
            }
        }

        "projection pages exclude pending intents and validate their bounds" {
            runTest {
                withRepository { _, repository ->
                    shouldThrow<IllegalArgumentException> { repository.loadProjectionEvents(0, 0) }
                    val pendingId = repository.savePending(intent(clientOrderId = "pending-client"))
                    repository.loadProjectionEvents(0, 10) shouldBe emptyList()

                    repository.recordOutcome(
                        pendingId,
                        state = OrderIntentState.REJECTED,
                        orderTxid = null,
                        errorMessage = "rejected",
                        resolvedAt = Instant.parse("2026-10-08T12:00:30Z"),
                    ) shouldBe true
                    repository.loadProjectionEvents(0, 1).single().let { event ->
                        event.journalId shouldBe repository.journalId()
                        event.eventId shouldBe 1L
                        event.intent.state shouldBe OrderIntentState.REJECTED
                    }
                    repository.loadProjectionEvents(1, 10) shouldBe emptyList()
                }
            }
        }

        "restart recovery changes only pending submissions and adds a projection revision" {
            runTest {
                withRepository { _, repository ->
                    val pendingId = repository.savePending(intent(clientOrderId = "client-pending"))
                    val confirmedId = repository.savePending(intent(clientOrderId = "client-confirmed"))
                    repository.recordOutcome(
                        confirmedId,
                        state = OrderIntentState.CONFIRMED,
                        orderTxid = "OID-OK",
                        errorMessage = null,
                        resolvedAt = Instant.parse("2026-10-08T12:00:30Z"),
                    )

                    repository.recoverInterruptedSubmissions()

                    val intents = repository.loadUnresolvedIntents().associateBy { it.id }
                    intents[pendingId]?.state shouldBe OrderIntentState.UNCERTAIN
                    intents[pendingId]?.errorMessage shouldBe
                        "Process restarted before the exchange outcome was durably recorded."
                    intents[confirmedId] shouldBe null
                    repository.loadProjectionEvents(0, 10).map { it.revision } shouldBe listOf(1L, 1L)
                    repository.recoverInterruptedSubmissions()
                    repository.loadProjectionEvents(0, 10).map { it.revision } shouldBe listOf(1L, 1L)
                }
            }
        }
    }

    private suspend fun withRepository(
        block: suspend (ExecutionDatabase, SqliteExecutionOrderIntentRepositoryImpl) -> Unit,
    ) {
        val directory = Files.createTempDirectory("execution-intent-repository-")
        try {
            val reportingPath = directory.resolve("reporting.db")
            val database = ExecutionDatabase.init(
                directory.resolve("execution.db").toString(),
                reportingPath.toString(),
            )
            database.markReadyForSubmission()
            block(database, SqliteExecutionOrderIntentRepositoryImpl(database))
        } finally {
            Files.walk(directory).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
            }
        }
    }

    private fun intent(clientOrderId: String = "client-1", expectedPrice: BigDecimal? = BigDecimal("1000.00")) =
        OrderIntent(
            cycleId = "cycle-1",
            clientOrderId = clientOrderId,
            clientOrderIdAmbiguous = true,
            pair = "XBTUSD",
            symbol = Asset.BTC,
            side = "BUY",
            volume = BigDecimal("0.02500000"),
            usdAmount = BigDecimal("25.00"),
            expectedPrice = expectedPrice,
            createdAt = Instant.parse("2026-10-08T12:00:00Z"),
            state = OrderIntentState.PENDING,
            localTradeId = 73,
            legacySourceId = "legacy-$clientOrderId",
            legacyIntentId = clientOrderId.hashCode(),
            legacyTradeId = 73,
            legacySourceState = "PENDING",
        )
}
