package com.gemini.krakenbot.service.actual

import com.gemini.krakenbot.service.DirectBalanceCapture
import com.gemini.krakenbot.service.DirectEvidenceStatus
import com.gemini.krakenbot.service.DirectTickerCapture
import com.gemini.krakenbot.service.DirectTickerMark
import com.gemini.krakenbot.service.DirectValueEvidence
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.test.runTest
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.sql.DriverManager
import java.sql.SQLException
import java.time.Instant

class ActualObservationStoreTest : StringSpec() {
    override fun isolationMode() = IsolationMode.InstancePerTest

    init {
        "file-backed observations round-trip raw evidence and persist across store restarts" {
            withTempDirectory { directory ->
                val paths = paths(directory)
                val original = sample("one").copy(
                    assets = sample("one").assets.map { asset ->
                        asset.copy(
                            rawBalanceKeys = listOf("XXBT\nraw\u0000"),
                            rawBalanceValues = mapOf("XXBT\nraw\u0000" to "1.25\t\n\u0000"),
                            candidateResponsePairs = listOf("XXBTZUSD\nraw"),
                            candidateRawPrices = mapOf("XXBTZUSD\nraw" to "100.00\t\n"),
                        )
                    },
                )
                val later = sample("two", observedAt = NOW.plusSeconds(10), btcPrice = "120")

                runTest {
                    store(paths).append(original) shouldBe true
                    store(paths).append(later) shouldBe true
                    val loaded = store(paths)
                        .querySegment(original.scopeFingerprint, original.accountIdentityDigest)
                    loaded.map { it.observationId } shouldBe listOf("one", "two")

                    loaded.first().observationId shouldBe original.observationId
                    loaded.first().persistedAt shouldNotBe null
                    loaded.first().balanceSource shouldBe DIRECT_BALANCE_SOURCE
                    loaded.first().priceSource shouldBe DIRECT_PRICE_SOURCE
                    loaded.first().valuationCurrency shouldBe OBSERVATION_CURRENCY
                    loaded.first().observationSchemaVersion shouldBe ACTUAL_OBSERVATION_SCHEMA_VERSION
                    loaded.first().assets shouldBe original.assets
                    loaded.first().totalUsd?.compareTo(BigDecimal("200.0")) shouldBe 0
                    loaded.last().totalUsd?.compareTo(BigDecimal("225.0")) shouldBe 0
                }
            }
        }

        "version one data migrates atomically without changing resolvable evidence" {
            withTempDirectory { directory ->
                val paths = paths(directory)
                val observation = sample("legacy")
                createVersionOneDatabase(paths.actual, observation)

                runTest {
                    val loaded = store(paths)
                        .querySegment(observation.scopeFingerprint, observation.accountIdentityDigest)
                        .single()
                    loaded.observationId shouldBe observation.observationId
                    loaded.persistedAt shouldBe null
                    loaded.assets shouldBe observation.assets
                    store(paths).append(sample("after-migration", observedAt = NOW.plusSeconds(10))) shouldBe true
                }
                DriverManager.getConnection("jdbc:sqlite:${paths.actual}").use { connection ->
                    connection.createStatement().use { statement ->
                        statement.executeQuery("SELECT MAX(version) FROM actual_schema_migrations").use { rows ->
                            rows.next() shouldBe true
                            rows.getInt(1) shouldBe 4
                        }
                        statement.executeQuery(
                            "SELECT COUNT(*) FROM actual_observations WHERE observation_id = 'after-migration'",
                        ).use { rows ->
                            rows.next() shouldBe true
                            rows.getInt(1) shouldBe 1
                        }
                        shouldThrow<SQLException> {
                            statement.executeUpdate("UPDATE actual_observation_assets SET raw_balance_keys = ''")
                        }
                    }
                }
            }
        }

        "version one incomplete observation without asset rows remains incomplete after migration" {
            withTempDirectory { directory ->
                val paths = paths(directory)
                val observation = sample("legacy-without-assets").copy(
                    status = ActualObservationStatus.INCOMPLETE,
                    totalUsd = null,
                    incompleteReasons = listOf("legacy sample contained no asset rows"),
                    assets = emptyList(),
                )
                createVersionOneDatabase(paths.actual, observation)

                runTest {
                    val loaded = store(paths)
                        .querySegment(observation.scopeFingerprint, observation.accountIdentityDigest)
                        .single()
                    loaded.status shouldBe ActualObservationStatus.INCOMPLETE
                    loaded.totalUsd shouldBe null
                    loaded.assets shouldBe emptyList()
                    loaded.incompleteReasons shouldBe observation.incompleteReasons
                }
            }
        }

        "ambiguous version one evidence leaves the original database intact" {
            withTempDirectory { directory ->
                val paths = paths(directory)
                val observation = sample("legacy-ambiguous")
                createVersionOneDatabase(paths.actual, observation, corruptFirstBalanceValue = true)

                runTest {
                    shouldThrow<IllegalArgumentException> {
                        store(paths).querySegment(observation.scopeFingerprint, observation.accountIdentityDigest)
                    }
                }
                DriverManager.getConnection("jdbc:sqlite:${paths.actual}").use { connection ->
                    connection.createStatement().use { statement ->
                        statement.executeQuery("SELECT MAX(version) FROM actual_schema_migrations").use { rows ->
                            rows.next() shouldBe true
                            rows.getInt(1) shouldBe 1
                        }
                        statement.executeQuery(
                            "SELECT raw_balance_values FROM actual_observation_assets " +
                                "WHERE observation_id = 'legacy-ambiguous' AND asset_symbol = 'BTC'",
                        ).use { rows ->
                            rows.next() shouldBe true
                            rows.getString(1) shouldBe "XXBT\u0000raw\nvalue"
                        }
                    }
                }
            }
        }

        "version one migration rejects duplicate and incomplete raw evidence without changing the source" {
            listOf(
                "XXBT\u0000first\nXXBT\u0000second" to "duplicate keys",
                "orphan\u0000value" to "incomplete raw balance evidence",
            ).forEachIndexed { index, (legacyValues, expectedMessage) ->
                withTempDirectory { directory ->
                    val paths = paths(directory)
                    val observation = sample("legacy-invalid-$index")
                    createVersionOneDatabase(paths.actual, observation)
                    DriverManager.getConnection("jdbc:sqlite:${paths.actual}").use { connection ->
                        connection.prepareStatement(
                            "UPDATE actual_observation_assets SET raw_balance_values = ? " +
                                "WHERE observation_id = ? AND asset_symbol = 'BTC'",
                        ).use { statement ->
                            statement.setString(1, legacyValues)
                            statement.setString(2, observation.observationId)
                            statement.executeUpdate() shouldBe 1
                        }
                    }

                    runTest {
                        val query: suspend () -> List<ActualObservation> = {
                            store(paths).querySegment(observation.scopeFingerprint, observation.accountIdentityDigest)
                        }
                        val failure = if (index == 0) {
                            shouldThrow<IllegalArgumentException> { query() }
                        } else {
                            shouldThrow<IllegalStateException> { query() }
                        }
                        failure.message.orEmpty() shouldContain expectedMessage
                    }
                    DriverManager.getConnection("jdbc:sqlite:${paths.actual}").use { connection ->
                        connection.createStatement().use { statement ->
                            statement.executeQuery("SELECT MAX(version) FROM actual_schema_migrations").use { rows ->
                                rows.next() shouldBe true
                                rows.getInt(1) shouldBe 1
                            }
                        }
                    }
                }
            }
        }

        "version one migration rejects ambiguous or incomplete ticker response evidence" {
            val invalidCases = listOf(
                Triple(
                    "XXBTZUSD\nXXBTZUSD",
                    "XXBTZUSD\u0000100.00",
                    "ambiguous ticker response pairs",
                ),
                Triple(
                    "missing-pair",
                    "XXBTZUSD\u0000100.00",
                    "incomplete raw price evidence",
                ),
            )
            invalidCases.forEachIndexed { index, (candidatePairs, candidatePrices, expectedMessage) ->
                withTempDirectory { directory ->
                    val paths = paths(directory)
                    val observation = sample("legacy-invalid-price-$index")
                    createVersionOneDatabase(paths.actual, observation)
                    DriverManager.getConnection("jdbc:sqlite:${paths.actual}").use { connection ->
                        connection.prepareStatement(
                            "UPDATE actual_observation_assets SET candidate_response_pairs = ?, " +
                                "candidate_raw_prices = ? WHERE observation_id = ? AND asset_symbol = 'BTC'",
                        ).use { statement ->
                            statement.setString(1, candidatePairs)
                            statement.setString(2, candidatePrices)
                            statement.setString(3, observation.observationId)
                            statement.executeUpdate() shouldBe 1
                        }
                    }

                    runTest {
                        val failure = shouldThrow<IllegalStateException> {
                            store(paths).querySegment(observation.scopeFingerprint, observation.accountIdentityDigest)
                        }
                        failure.message.orEmpty() shouldContain expectedMessage
                    }
                    DriverManager.getConnection("jdbc:sqlite:${paths.actual}").use { connection ->
                        connection.createStatement().use { statement ->
                            statement.executeQuery("SELECT MAX(version) FROM actual_schema_migrations").use { rows ->
                                rows.next() shouldBe true
                                rows.getInt(1) shouldBe 1
                            }
                        }
                    }
                }
            }
        }

        "unsupported observation schema versions are rejected before storage is opened" {
            withTempDirectory { directory ->
                val paths = paths(directory)
                runTest {
                    shouldThrow<IllegalArgumentException> {
                        store(paths).append(sample("future-observation").copy(observationSchemaVersion = 999))
                    }
                }
                Files.exists(paths.actual) shouldBe false
            }
        }

        "duplicate observations are idempotent and reused IDs cannot replace evidence" {
            withTempDirectory { directory ->
                val paths = paths(directory)
                val store = store(paths)
                val original = sample("duplicate")
                val absentRawValue = original.copy(
                    assets = original.assets.map { asset ->
                        asset.copy(rawBalanceValues = asset.rawBalanceValues.mapValues { null })
                    },
                )
                val emptyRawValue = original.copy(
                    assets = original.assets.map { asset ->
                        asset.copy(rawBalanceValues = asset.rawBalanceValues.mapValues { "" })
                    },
                )

                runTest {
                    store.append(original) shouldBe true
                    store.append(original) shouldBe false
                    shouldThrow<IllegalStateException> { store.append(absentRawValue) }
                    shouldThrow<IllegalStateException> { store.append(emptyRawValue) }
                    shouldThrow<IllegalStateException> {
                        store.append(original.copy(totalUsd = BigDecimal("999.99")))
                    }
                }
                runTest {
                    store.querySegment(original.scopeFingerprint, original.accountIdentityDigest).size shouldBe 1
                }
            }
        }

        "version one payload hashes preserve idempotent retries without permitting evidence replacement" {
            withTempDirectory { directory ->
                val paths = paths(directory)
                val store = store(paths)
                val original = sample("legacy-payload-hash")
                val observation = original.copy(
                    status = ActualObservationStatus.INCOMPLETE,
                    totalUsd = null,
                    incompleteReasons = listOf("raw price evidence was unavailable"),
                    assets = original.assets.map { asset ->
                        asset.copy(
                            status = ActualAssetStatus.INVALID_PRICE,
                            rawBalanceValues = asset.rawBalanceValues.mapValues { null },
                            quantity = null,
                            requestedPair = null,
                            responsePair = null,
                            candidateRawPrices = asset.candidateRawPrices.mapValues { null },
                            rawPrice = null,
                            priceUsd = null,
                            valueUsd = null,
                        )
                    },
                )

                runTest {
                    store.append(observation) shouldBe true
                }
                DriverManager.getConnection("jdbc:sqlite:${paths.actual}").use { connection ->
                    connection.createStatement().use { statement ->
                        statement.execute("DROP TRIGGER actual_observations_no_update")
                    }
                    connection.prepareStatement(
                        "UPDATE actual_observations SET payload_hash_version = 1, payload_hash = ? " +
                            "WHERE observation_id = ?",
                    ).use { statement ->
                        statement.setString(1, legacyPayloadHash(observation))
                        statement.setString(2, observation.observationId)
                        statement.executeUpdate() shouldBe 1
                    }
                }

                runTest {
                    store.append(observation) shouldBe false
                    shouldThrow<IllegalStateException> {
                        store.append(observation.copy(totalUsd = BigDecimal("999.99")))
                    }
                }
            }
        }

        "header and asset values roll back together when a child row violates its unique key" {
            withTempDirectory { directory ->
                val paths = paths(directory)
                val store = store(paths)
                val observation = sample("atomic")
                val invalid = observation.copy(assets = observation.assets + observation.assets.first())

                runTest { shouldThrow<Exception> { store.append(invalid) } }
                runTest {
                    store.querySegment(observation.scopeFingerprint, observation.accountIdentityDigest) shouldBe
                        emptyList()
                }
            }
        }

        "append-only triggers reject direct updates and deletes" {
            withTempDirectory { directory ->
                val paths = paths(directory)
                val store = store(paths)
                val observation = sample("immutable")
                runTest { store.append(observation) }

                DriverManager.getConnection("jdbc:sqlite:${paths.actual}").use { connection ->
                    shouldThrow<SQLException> {
                        connection.createStatement().use {
                            it.executeUpdate("UPDATE actual_observations SET total_usd = '1'")
                        }
                    }
                    shouldThrow<SQLException> {
                        connection.createStatement().use {
                            it.executeUpdate("DELETE FROM actual_observation_assets")
                        }
                    }
                }
            }
        }

        "segment queries separate scope and account and enforce a result limit" {
            withTempDirectory { directory ->
                val paths = paths(directory)
                val store = store(paths)
                val first = sample("first")
                val second = sample("second", observedAt = NOW.plusSeconds(10))
                val otherScope = sample("other-scope").copy(
                    scopeSymbols = listOf("BTC"),
                    scopeFingerprint = ActualObservationValuator.scopeFingerprint(listOf("BTC")),
                )
                val otherAccount = sample("other-account").copy(accountIdentityDigest = OTHER_ACCOUNT_DIGEST)

                runTest {
                    store.append(first)
                    store.append(second)
                    store.append(otherScope)
                    store.append(otherAccount)
                    val limited = store.querySegment(first.scopeFingerprint, first.accountIdentityDigest, limit = 1)
                    limited.map { it.observationId } shouldBe listOf("second")
                }
            }
        }

        "file-backed store rejects memory paths, invalid limits, future schemas, and corrupt encodings" {
            withTempDirectory { directory ->
                val paths = paths(directory)
                runTest {
                    shouldThrow<IllegalArgumentException> {
                        ActualObservationStore(":memory:", paths.reporting.toString(), paths.execution.toString())
                            .querySegment("f".repeat(64), ACCOUNT_DIGEST)
                    }
                    shouldThrow<IllegalArgumentException> {
                        ActualObservationStore(" ", paths.reporting.toString(), paths.execution.toString())
                            .querySegment("f".repeat(64), ACCOUNT_DIGEST)
                    }
                    shouldThrow<IllegalArgumentException> {
                        ActualObservationStore(
                            "jdbc:sqlite:file:actual?mode=memory&cache=shared",
                            paths.reporting.toString(),
                            paths.execution.toString(),
                        ).querySegment("f".repeat(64), ACCOUNT_DIGEST)
                    }
                    shouldThrow<IllegalArgumentException> {
                        store(paths).querySegment("f".repeat(64), ACCOUNT_DIGEST, limit = 0)
                    }
                }

                DriverManager.getConnection("jdbc:sqlite:${paths.actual}").use { connection ->
                    connection.createStatement().use { statement ->
                        statement.execute(
                            "CREATE TABLE actual_schema_migrations " +
                                "(version INTEGER PRIMARY KEY, name TEXT NOT NULL, applied_at_ns INTEGER NOT NULL)",
                        )
                        statement.execute(
                            "INSERT INTO actual_schema_migrations(version, name, applied_at_ns) " +
                                "VALUES (5, 'future', 1)",
                        )
                    }
                }
                runTest {
                    shouldThrow<IllegalStateException> {
                        store(paths).querySegment("f".repeat(64), ACCOUNT_DIGEST)
                    }
                }

                val corruptDirectory = Files.createDirectory(directory.resolve("corrupt"))
                val corruptPaths = paths(corruptDirectory)
                val corruptStore = store(corruptPaths)
                val observation = sample("corrupt-encoded-value")
                runTest { corruptStore.append(observation) shouldBe true }
                DriverManager.getConnection("jdbc:sqlite:${corruptPaths.actual}").use { connection ->
                    connection.createStatement().use { statement ->
                        statement.execute("DROP TRIGGER actual_observation_assets_no_update")
                        statement.execute(
                            "UPDATE actual_observation_assets SET raw_balance_keys = '%' " +
                                "WHERE observation_id = '${observation.observationId}' AND asset_symbol = 'BTC'",
                        )
                    }
                }
                runTest {
                    shouldThrow<IllegalArgumentException> {
                        corruptStore.querySegment(observation.scopeFingerprint, observation.accountIdentityDigest)
                    }
                }
            }
        }

        "actual database paths cannot alias reporting or execution storage" {
            withTempDirectory { directory ->
                val paths = paths(directory)
                val samePathStore = ActualObservationStore(
                    paths.reporting.toString(),
                    paths.reporting.toString(),
                    paths.execution.toString(),
                )

                runTest {
                    shouldThrow<IllegalArgumentException> {
                        samePathStore.querySegment("f".repeat(64), ACCOUNT_DIGEST)
                    }
                }

                Files.createFile(paths.reporting)
                Files.createLink(paths.actual, paths.reporting)
                val hardLinkStore = store(paths)
                runTest {
                    shouldThrow<IllegalArgumentException> {
                        hardLinkStore.querySegment("f".repeat(64), ACCOUNT_DIGEST)
                    }
                }
            }
        }

        "constructing the observation service does not open or create its database" {
            withTempDirectory { directory ->
                val paths = paths(directory)

                store(paths)

                Files.exists(paths.actual) shouldBe false
            }
        }
    }

    private fun sample(
        id: String,
        observedAt: Instant = NOW.plusSeconds(3),
        btcPrice: String = "100.00",
    ): ActualObservation {
        val balances = DirectBalanceCapture(
            requestStartedAt = NOW,
            responseEndedAt = NOW.plusSeconds(1),
            resultShapeValid = true,
            valuesByAssetId = mapOf(
                "XXBT" to evidence("1.25"),
                "ZUSD" to evidence("75.00"),
            ),
        )
        val prices = DirectTickerCapture(
            requestStartedAt = NOW.plusSeconds(2),
            responseEndedAt = NOW.plusSeconds(3),
            resultShapeValid = true,
            marksBySymbol = mapOf(
                "BTC" to DirectTickerMark(
                    symbol = "BTC",
                    requestedPair = "XBTUSD",
                    responsePair = "XXBTZUSD",
                    candidateResponsePairs = listOf("XXBTZUSD"),
                    rawPrice = btcPrice,
                    price = BigDecimal(btcPrice),
                    status = DirectEvidenceStatus.VALID,
                    candidateValuesByPair = mapOf("XXBTZUSD" to evidence(btcPrice)),
                ),
            ),
        )
        return ActualObservationValuator.build(
            observationId = id,
            scopeSymbols = listOf("BTC", "USD"),
            accountIdentityDigest = ACCOUNT_DIGEST,
            balances = balances.copy(responseEndedAt = observedAt),
            prices = prices.copy(requestStartedAt = observedAt, responseEndedAt = observedAt),
            now = observedAt.plusSeconds(1),
        )
    }

    private fun evidence(value: String) = DirectValueEvidence(
        rawValue = value,
        value = BigDecimal(value),
        status = DirectEvidenceStatus.VALID,
    )

    /** Historical v1 hash encoding used to verify a retry against an existing v1 row. */
    private fun legacyPayloadHash(observation: ActualObservation): String {
        val fields = buildList {
            add(observation.observationId)
            add(observation.accountIdentityDigest)
            add(observation.walletScope)
            add(observation.scopeFingerprint)
            addAll(observation.scopeSymbols)
            add(observation.observedAt.toString())
            add(observation.balanceRequestStartedAt.toString())
            add(observation.balanceResponseEndedAt.toString())
            add(observation.priceRequestStartedAt.toString())
            add(observation.priceResponseEndedAt.toString())
            add(observation.status.name)
            add(observation.totalUsd?.toPlainString().orEmpty())
            addAll(observation.incompleteReasons)
            observation.assets.sortedBy(ActualAssetObservation::symbol).forEach { asset ->
                add(asset.symbol)
                add(asset.status.name)
                addAll(asset.rawBalanceKeys.sorted())
                asset.rawBalanceValues.toSortedMap().forEach { (key, value) ->
                    add(key)
                    add(value.orEmpty())
                }
                add(asset.quantity?.toPlainString().orEmpty())
                add(asset.requestedPair.orEmpty())
                add(asset.responsePair.orEmpty())
                addAll(asset.candidateResponsePairs.sorted())
                asset.candidateRawPrices.toSortedMap().forEach { (key, value) ->
                    add(key)
                    add(value.orEmpty())
                }
                add(asset.rawPrice.orEmpty())
                add(asset.priceUsd?.toPlainString().orEmpty())
                add(asset.valueUsd?.toPlainString().orEmpty())
                add(asset.reason.orEmpty())
            }
        }
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { output ->
            fields.forEach { field ->
                val encoded = field.toByteArray(Charsets.UTF_8)
                output.writeInt(encoded.size)
                output.writeByte(0)
                output.write(encoded)
            }
        }
        return MessageDigest.getInstance("SHA-256")
            .digest(bytes.toByteArray())
            .joinToString("") { byte -> "%02x".format(byte) }
    }

    private fun paths(directory: Path) = DatabasePaths(
        actual = directory.resolve("actual.db"),
        reporting = directory.resolve("reporting.db"),
        execution = directory.resolve("execution.db"),
    )

    private fun store(paths: DatabasePaths) = ActualObservationStore(
        paths.actual.toString(),
        paths.reporting.toString(),
        paths.execution.toString(),
    )

    private fun createVersionOneDatabase(
        path: Path,
        observation: ActualObservation,
        corruptFirstBalanceValue: Boolean = false,
    ) {
        DriverManager.getConnection("jdbc:sqlite:$path").use { connection ->
            connection.createStatement().use { statement ->
                statement.execute(
                    "CREATE TABLE actual_schema_migrations " +
                        "(version INTEGER PRIMARY KEY, name TEXT NOT NULL, applied_at_ns INTEGER NOT NULL)",
                )
                statement.execute(
                    "INSERT INTO actual_schema_migrations(version, name, applied_at_ns) " +
                        "VALUES (1, 'forward_only_actual_v1', 1)",
                )
                statement.execute(
                    """
                    CREATE TABLE actual_observations (
                        observation_id TEXT PRIMARY KEY,
                        account_identity_digest TEXT NOT NULL,
                        wallet_scope TEXT NOT NULL,
                        scope_fingerprint TEXT NOT NULL,
                        scope_symbols TEXT NOT NULL,
                        observed_at_ns INTEGER NOT NULL,
                        balance_request_started_at_ns INTEGER NOT NULL,
                        balance_response_ended_at_ns INTEGER NOT NULL,
                        price_request_started_at_ns INTEGER NOT NULL,
                        price_response_ended_at_ns INTEGER NOT NULL,
                        status TEXT NOT NULL,
                        total_usd TEXT,
                        incomplete_reasons TEXT NOT NULL,
                        payload_hash TEXT NOT NULL
                    )
                    """.trimIndent(),
                )
                statement.execute(
                    """
                    CREATE TABLE actual_observation_assets (
                        observation_id TEXT NOT NULL,
                        asset_symbol TEXT NOT NULL,
                        status TEXT NOT NULL,
                        raw_balance_keys TEXT NOT NULL,
                        raw_balance_values TEXT NOT NULL,
                        quantity TEXT,
                        requested_pair TEXT,
                        response_pair TEXT,
                        candidate_response_pairs TEXT NOT NULL,
                        candidate_raw_prices TEXT NOT NULL,
                        raw_price TEXT,
                        price_usd TEXT,
                        value_usd TEXT,
                        reason TEXT,
                        PRIMARY KEY(observation_id, asset_symbol)
                    )
                    """.trimIndent(),
                )
            }
            connection.prepareStatement(
                "INSERT INTO actual_observations VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            ).use { statement ->
                statement.setString(1, observation.observationId)
                statement.setString(2, observation.accountIdentityDigest)
                statement.setString(3, observation.walletScope)
                statement.setString(4, observation.scopeFingerprint)
                statement.setString(5, observation.scopeSymbols.joinToString("\n"))
                statement.setLong(6, observation.observedAt.epochNanos())
                statement.setLong(7, observation.balanceRequestStartedAt.epochNanos())
                statement.setLong(8, observation.balanceResponseEndedAt.epochNanos())
                statement.setLong(9, observation.priceRequestStartedAt.epochNanos())
                statement.setLong(10, observation.priceResponseEndedAt.epochNanos())
                statement.setString(11, observation.status.name)
                statement.setString(12, observation.totalUsd?.toPlainString())
                statement.setString(13, observation.incompleteReasons.joinToString("\n"))
                statement.setString(14, "0".repeat(64))
                statement.executeUpdate()
            }
            connection.prepareStatement(
                "INSERT INTO actual_observation_assets VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            ).use { statement ->
                observation.assets.forEach { asset ->
                    statement.setString(1, observation.observationId)
                    statement.setString(2, asset.symbol)
                    statement.setString(3, asset.status.name)
                    statement.setString(4, asset.rawBalanceKeys.joinToString("\n"))
                    val legacyValues = legacyPairs(asset.rawBalanceValues)
                    statement.setString(
                        5,
                        if (corruptFirstBalanceValue && asset.symbol == "BTC") {
                            "XXBT\u0000raw\nvalue"
                        } else {
                            legacyValues
                        },
                    )
                    statement.setString(6, asset.quantity?.toPlainString())
                    statement.setString(7, asset.requestedPair)
                    statement.setString(8, asset.responsePair)
                    statement.setString(9, asset.candidateResponsePairs.joinToString("\n"))
                    statement.setString(10, legacyPairs(asset.candidateRawPrices))
                    statement.setString(11, asset.rawPrice)
                    statement.setString(12, asset.priceUsd?.toPlainString())
                    statement.setString(13, asset.valueUsd?.toPlainString())
                    statement.setString(14, asset.reason)
                    statement.addBatch()
                }
                statement.executeBatch()
            }
        }
    }

    private fun legacyPairs(values: Map<String, String?>): String = values.toSortedMap().entries.joinToString("\n") {
        "${it.key}\u0000${it.value.orEmpty()}"
    }

    private fun Instant.epochNanos(): Long =
        Math.addExact(Math.multiplyExact(epochSecond, 1_000_000_000L), nano.toLong())

    private fun withTempDirectory(block: (Path) -> Unit) {
        val directory = Files.createTempDirectory("actual-observation-test")
        try {
            block(directory)
        } finally {
            Files.walk(directory).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
            }
        }
    }

    private data class DatabasePaths(val actual: Path, val reporting: Path, val execution: Path)

    private companion object {
        val ACCOUNT_DIGEST = "a".repeat(64)
        val OTHER_ACCOUNT_DIGEST = "b".repeat(64)
        val NOW = Instant.parse("2026-10-09T12:00:00Z")
    }
}
