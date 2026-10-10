package com.gemini.krakenbot.service.actual

import com.gemini.krakenbot.config.ExecutionDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.sql.Connection
import java.sql.DriverManager
import java.time.Instant
import java.util.Base64

/** Separate append-only SQLite store for forward-only Actual observations. */
class ActualObservationStore(
    private val actualDatabasePath: String = System.getProperty("kraken.actual.db.path", "kraken-actual.db"),
    private val reportingDatabasePath: String = System.getProperty("kraken.db.path", "kraken-rebalancer.db"),
    private val executionDatabasePath: String = ExecutionDatabase.resolveExecutionDatabasePath(reportingDatabasePath),
) {
    private val schemaInitializationLock = Any()

    @Volatile
    private var schemaInitialized = false

    suspend fun append(observation: ActualObservation): Boolean = withContext(Dispatchers.IO) {
        appendBlocking(observation)
    }

    suspend fun querySegment(
        scopeFingerprint: String,
        accountIdentityDigest: String,
        limit: Int = MAX_QUERY_LIMIT,
    ): List<ActualObservation> = withContext(Dispatchers.IO) {
        querySegmentBlocking(scopeFingerprint, accountIdentityDigest, limit)
    }

    private fun appendBlocking(observation: ActualObservation): Boolean {
        require(observation.observationSchemaVersion == ACTUAL_OBSERVATION_SCHEMA_VERSION) {
            "Unsupported Actual observation schema version ${observation.observationSchemaVersion}."
        }
        initializeSchema()
        val persistedAt = Instant.now()
        connect().use { connection ->
            connection.autoCommit = false
            try {
                val inserted = connection.prepareStatement(
                    """
                    INSERT OR IGNORE INTO actual_observations (
                        observation_id, account_identity_digest, wallet_scope, scope_fingerprint, scope_symbols,
                        observed_at_ns, balance_request_started_at_ns, balance_response_ended_at_ns,
                        price_request_started_at_ns, price_response_ended_at_ns, persisted_at_ns,
                        balance_source, price_source, valuation_currency, observation_schema_version,
                        status, total_usd, incomplete_reasons, payload_hash_version, payload_hash
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """.trimIndent(),
                ).use { statement ->
                    statement.setString(1, observation.observationId)
                    statement.setString(2, observation.accountIdentityDigest)
                    statement.setString(3, observation.walletScope)
                    statement.setString(4, observation.scopeFingerprint)
                    statement.setString(5, observation.scopeSymbols.joinToString("\n"))
                    statement.setLong(6, observation.observedAt.toEpochNanos())
                    statement.setLong(7, observation.balanceRequestStartedAt.toEpochNanos())
                    statement.setLong(8, observation.balanceResponseEndedAt.toEpochNanos())
                    statement.setLong(9, observation.priceRequestStartedAt.toEpochNanos())
                    statement.setLong(10, observation.priceResponseEndedAt.toEpochNanos())
                    statement.setLong(11, persistedAt.toEpochNanos())
                    statement.setString(12, observation.balanceSource)
                    statement.setString(13, observation.priceSource)
                    statement.setString(14, observation.valuationCurrency)
                    statement.setInt(15, observation.observationSchemaVersion)
                    statement.setString(16, observation.status.name)
                    statement.setString(17, observation.totalUsd?.toPlainString())
                    statement.setString(18, observation.incompleteReasons.joinToString("\n"))
                    statement.setInt(19, CURRENT_PAYLOAD_HASH_VERSION)
                    statement.setString(20, observation.payloadHash())
                    statement.executeUpdate() == 1
                }
                if (!inserted) {
                    val existingHash = connection.prepareStatement(
                        "SELECT payload_hash, payload_hash_version FROM actual_observations WHERE observation_id = ?",
                    ).use { statement ->
                        statement.setString(1, observation.observationId)
                        statement.executeQuery().use { resultSet ->
                            check(resultSet.next()) { "Observation idempotency key disappeared during append." }
                            resultSet.getString("payload_hash") to resultSet.getInt("payload_hash_version")
                        }
                    }
                    val retryHash = when (existingHash.second) {
                        LEGACY_PAYLOAD_HASH_VERSION -> observation.legacyPayloadHash()
                        CURRENT_PAYLOAD_HASH_VERSION -> observation.payloadHash()
                        else -> error("Unsupported Actual observation payload hash version ${existingHash.second}.")
                    }
                    check(existingHash.first == retryHash) {
                        "Observation id ${observation.observationId} was reused for different evidence."
                    }
                    connection.commit()
                    return false
                }
                connection.prepareStatement(
                    """
                    INSERT INTO actual_observation_assets (
                        observation_id, asset_symbol, status, raw_balance_keys, raw_balance_values,
                        quantity, requested_pair, response_pair, candidate_response_pairs, candidate_raw_prices, raw_price,
                        price_usd, value_usd, reason
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """.trimIndent(),
                ).use { statement ->
                    observation.assets.forEach { asset ->
                        statement.setString(1, observation.observationId)
                        statement.setString(2, asset.symbol)
                        statement.setString(3, asset.status.name)
                        statement.setString(4, encodeStrings(asset.rawBalanceKeys.sorted()))
                        statement.setString(5, encodePairs(asset.rawBalanceValues))
                        statement.setString(6, asset.quantity?.toPlainString())
                        statement.setString(7, asset.requestedPair)
                        statement.setString(8, asset.responsePair)
                        statement.setString(9, encodeStrings(asset.candidateResponsePairs.sorted()))
                        statement.setString(10, encodePairs(asset.candidateRawPrices))
                        statement.setString(11, asset.rawPrice)
                        statement.setString(12, asset.priceUsd?.toPlainString())
                        statement.setString(13, asset.valueUsd?.toPlainString())
                        statement.setString(14, asset.reason)
                        statement.addBatch()
                    }
                    statement.executeBatch()
                }
                connection.commit()
                return true
            } catch (e: Exception) {
                connection.rollback()
                throw e
            }
        }
    }

    private fun querySegmentBlocking(
        scopeFingerprint: String,
        accountIdentityDigest: String,
        limit: Int,
    ): List<ActualObservation> {
        require(limit in 1..MAX_QUERY_LIMIT) { "Actual history limit must be between 1 and $MAX_QUERY_LIMIT." }
        initializeSchema()
        return connect().use { connection ->
            val headers = connection.prepareStatement(
                """
                SELECT * FROM actual_observations
                WHERE scope_fingerprint = ? AND account_identity_digest = ?
                ORDER BY observed_at_ns DESC LIMIT ?
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, scopeFingerprint)
                statement.setString(2, accountIdentityDigest)
                statement.setInt(3, limit)
                statement.executeQuery().use { resultSet ->
                    buildList {
                        while (resultSet.next()) {
                            add(
                                ObservationHeader(
                                    id = resultSet.getString("observation_id"),
                                    accountIdentityDigest = resultSet.getString("account_identity_digest"),
                                    walletScope = resultSet.getString("wallet_scope"),
                                    balanceSource = resultSet.getString("balance_source"),
                                    priceSource = resultSet.getString("price_source"),
                                    valuationCurrency = resultSet.getString("valuation_currency"),
                                    observationSchemaVersion = resultSet.getInt("observation_schema_version"),
                                    scopeFingerprint = resultSet.getString("scope_fingerprint"),
                                    scopeSymbols = resultSet.getString("scope_symbols").splitLines(),
                                    observedAt = resultSet.getLong("observed_at_ns").toInstant(),
                                    balanceRequestStartedAt = resultSet.getLong(
                                        "balance_request_started_at_ns",
                                    ).toInstant(),
                                    balanceResponseEndedAt = resultSet.getLong(
                                        "balance_response_ended_at_ns",
                                    ).toInstant(),
                                    priceRequestStartedAt = resultSet.getLong(
                                        "price_request_started_at_ns",
                                    ).toInstant(),
                                    priceResponseEndedAt = resultSet.getLong("price_response_ended_at_ns").toInstant(),
                                    persistedAt = resultSet.getLong("persisted_at_ns").takeUnless {
                                        resultSet.wasNull()
                                    }?.toInstant(),
                                    status = ActualObservationStatus.valueOf(resultSet.getString("status")),
                                    totalUsd = resultSet.getString("total_usd")?.toBigDecimalOrNull(),
                                    incompleteReasons = resultSet.getString("incomplete_reasons").splitLines(),
                                ),
                            )
                        }
                    }
                }
            }
            if (headers.isEmpty()) return@use emptyList()
            val assetsByObservation = connection.prepareStatement(
                """
                SELECT * FROM actual_observation_assets
                WHERE observation_id IN (${headers.joinToString(",") { "?" }})
                ORDER BY observation_id, asset_symbol
                """.trimIndent(),
            ).use { statement ->
                headers.forEachIndexed { index, header -> statement.setString(index + 1, header.id) }
                statement.executeQuery().use { resultSet ->
                    buildMap<String, MutableList<ActualAssetObservation>> {
                        while (resultSet.next()) {
                            val id = resultSet.getString("observation_id")
                            getOrPut(id) { mutableListOf() }.add(
                                ActualAssetObservation(
                                    symbol = resultSet.getString("asset_symbol"),
                                    status = ActualAssetStatus.valueOf(resultSet.getString("status")),
                                    rawBalanceKeys = resultSet.getString("raw_balance_keys").decodeStrings(),
                                    rawBalanceValues = resultSet.getString("raw_balance_values").decodePairs(),
                                    quantity = resultSet.getString("quantity")?.toBigDecimalOrNull(),
                                    requestedPair = resultSet.getString("requested_pair"),
                                    responsePair = resultSet.getString("response_pair"),
                                    candidateResponsePairs = resultSet.getString(
                                        "candidate_response_pairs",
                                    ).decodeStrings(),
                                    candidateRawPrices = resultSet.getString("candidate_raw_prices").decodePairs(),
                                    rawPrice = resultSet.getString("raw_price"),
                                    priceUsd = resultSet.getString("price_usd")?.toBigDecimalOrNull(),
                                    valueUsd = resultSet.getString("value_usd")?.toBigDecimalOrNull(),
                                    reason = resultSet.getString("reason"),
                                ),
                            )
                        }
                    }
                }
            }
            headers.asReversed().map { header ->
                ActualObservation(
                    observationId = header.id,
                    accountIdentityDigest = header.accountIdentityDigest,
                    walletScope = header.walletScope,
                    balanceSource = header.balanceSource,
                    priceSource = header.priceSource,
                    valuationCurrency = header.valuationCurrency,
                    observationSchemaVersion = header.observationSchemaVersion,
                    scopeFingerprint = header.scopeFingerprint,
                    scopeSymbols = header.scopeSymbols,
                    observedAt = header.observedAt,
                    balanceRequestStartedAt = header.balanceRequestStartedAt,
                    balanceResponseEndedAt = header.balanceResponseEndedAt,
                    priceRequestStartedAt = header.priceRequestStartedAt,
                    priceResponseEndedAt = header.priceResponseEndedAt,
                    persistedAt = header.persistedAt,
                    status = header.status,
                    totalUsd = header.totalUsd,
                    incompleteReasons = header.incompleteReasons,
                    assets = assetsByObservation[header.id].orEmpty(),
                )
            }
        }
    }

    private fun initializeSchema() {
        if (schemaInitialized) return
        synchronized(schemaInitializationLock) {
            if (schemaInitialized) return
            initializeSchemaBlocking()
            schemaInitialized = true
        }
    }

    private fun initializeSchemaBlocking() {
        val actual = databaseFile(actualDatabasePath)
        val reporting = databaseFile(reportingDatabasePath)
        val execution = databaseFile(executionDatabasePath)
        require(!sharesArtifact(actual, reporting) && !sharesArtifact(actual, execution)) {
            "Actual observations must use a separate SQLite file from reporting and execution data."
        }
        actual.parent?.let(Files::createDirectories)
        connect().use { connection ->
            connection.autoCommit = false
            try {
                connection.createStatement().use { statement ->
                    statement.execute(
                        "CREATE TABLE IF NOT EXISTS actual_schema_migrations " +
                            "(version INTEGER PRIMARY KEY, name TEXT NOT NULL, applied_at_ns INTEGER NOT NULL)",
                    )
                    val version = statement.executeQuery(
                        "SELECT COALESCE(MAX(version), 0) FROM actual_schema_migrations",
                    ).use { resultSet ->
                        resultSet.next()
                        resultSet.getInt(1)
                    }
                    check(version <= SCHEMA_VERSION) {
                        "Actual observation schema version $version is newer than this binary supports."
                    }
                    statement.execute(
                        """
                        CREATE TABLE IF NOT EXISTS actual_observations (
                            observation_id TEXT PRIMARY KEY,
                            account_identity_digest TEXT NOT NULL CHECK(length(account_identity_digest) = 64),
                            wallet_scope TEXT NOT NULL,
                            balance_source TEXT NOT NULL DEFAULT '$DIRECT_BALANCE_SOURCE',
                            price_source TEXT NOT NULL DEFAULT '$DIRECT_PRICE_SOURCE',
                            valuation_currency TEXT NOT NULL DEFAULT '$OBSERVATION_CURRENCY',
                            observation_schema_version INTEGER NOT NULL DEFAULT $ACTUAL_OBSERVATION_SCHEMA_VERSION
                                CHECK(observation_schema_version > 0),
                            scope_fingerprint TEXT NOT NULL CHECK(length(scope_fingerprint) = 64),
                            scope_symbols TEXT NOT NULL,
                            observed_at_ns INTEGER NOT NULL,
                            balance_request_started_at_ns INTEGER NOT NULL,
                            balance_response_ended_at_ns INTEGER NOT NULL,
                            price_request_started_at_ns INTEGER NOT NULL,
                            price_response_ended_at_ns INTEGER NOT NULL,
                            persisted_at_ns INTEGER,
                            status TEXT NOT NULL CHECK(status IN ('COMPLETE', 'INCOMPLETE')),
                            total_usd TEXT,
                            incomplete_reasons TEXT NOT NULL,
                            payload_hash_version INTEGER NOT NULL DEFAULT 1 CHECK(payload_hash_version IN (1, 2)),
                            payload_hash TEXT NOT NULL CHECK(length(payload_hash) = 64),
                            CHECK((status = 'COMPLETE' AND total_usd IS NOT NULL) OR
                                  (status = 'INCOMPLETE' AND total_usd IS NULL))
                        )
                        """.trimIndent(),
                    )
                    statement.execute(
                        """
                        CREATE TABLE IF NOT EXISTS actual_observation_assets (
                            observation_id TEXT NOT NULL REFERENCES actual_observations(observation_id) ON DELETE RESTRICT,
                            asset_symbol TEXT NOT NULL,
                            status TEXT NOT NULL CHECK(status IN (
                                'COMPLETE', 'MISSING_BALANCE', 'INVALID_BALANCE', 'AMBIGUOUS_BALANCE',
                                'MISSING_PRICE', 'INVALID_PRICE', 'INVALID_TIME_WINDOW'
                            )),
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
                            PRIMARY KEY(observation_id, asset_symbol),
                            CHECK((status = 'COMPLETE' AND value_usd IS NOT NULL) OR
                                  (status <> 'COMPLETE' AND value_usd IS NULL))
                        )
                        """.trimIndent(),
                    )
                    statement.execute(
                        "CREATE INDEX IF NOT EXISTS idx_actual_scope_observed " +
                            "ON actual_observations(scope_fingerprint, account_identity_digest, observed_at_ns)",
                    )
                    statement.execute(
                        """
                        CREATE TRIGGER IF NOT EXISTS actual_observations_no_update
                        BEFORE UPDATE ON actual_observations
                        BEGIN SELECT RAISE(ABORT, 'actual observations are append-only'); END
                        """.trimIndent(),
                    )
                    statement.execute(
                        """
                        CREATE TRIGGER IF NOT EXISTS actual_observations_no_delete
                        BEFORE DELETE ON actual_observations
                        BEGIN SELECT RAISE(ABORT, 'actual observations are append-only'); END
                        """.trimIndent(),
                    )
                    statement.execute(
                        """
                        CREATE TRIGGER IF NOT EXISTS actual_observation_assets_no_update
                        BEFORE UPDATE ON actual_observation_assets
                        BEGIN SELECT RAISE(ABORT, 'actual observations are append-only'); END
                        """.trimIndent(),
                    )
                    statement.execute(
                        """
                        CREATE TRIGGER IF NOT EXISTS actual_observation_assets_no_delete
                        BEFORE DELETE ON actual_observation_assets
                        BEGIN SELECT RAISE(ABORT, 'actual observations are append-only'); END
                        """.trimIndent(),
                    )
                    if (!hasColumn(connection, "actual_observations", "persisted_at_ns")) {
                        statement.execute("ALTER TABLE actual_observations ADD COLUMN persisted_at_ns INTEGER")
                    }
                    if (!hasColumn(connection, "actual_observations", "payload_hash_version")) {
                        statement.execute(
                            "ALTER TABLE actual_observations ADD COLUMN payload_hash_version " +
                                "INTEGER NOT NULL DEFAULT 1 CHECK(payload_hash_version IN (1, 2))",
                        )
                    }
                    if (!hasColumn(connection, "actual_observations", "balance_source")) {
                        statement.execute(
                            "ALTER TABLE actual_observations ADD COLUMN balance_source TEXT NOT NULL " +
                                "DEFAULT '$DIRECT_BALANCE_SOURCE'",
                        )
                    }
                    if (!hasColumn(connection, "actual_observations", "price_source")) {
                        statement.execute(
                            "ALTER TABLE actual_observations ADD COLUMN price_source TEXT NOT NULL " +
                                "DEFAULT '$DIRECT_PRICE_SOURCE'",
                        )
                    }
                    if (!hasColumn(connection, "actual_observations", "valuation_currency")) {
                        statement.execute(
                            "ALTER TABLE actual_observations ADD COLUMN valuation_currency TEXT NOT NULL " +
                                "DEFAULT '$OBSERVATION_CURRENCY'",
                        )
                    }
                    if (!hasColumn(connection, "actual_observations", "observation_schema_version")) {
                        statement.execute(
                            "ALTER TABLE actual_observations ADD COLUMN observation_schema_version " +
                                "INTEGER NOT NULL DEFAULT $ACTUAL_OBSERVATION_SCHEMA_VERSION",
                        )
                    }
                    if (version == 1) {
                        migrateVersionOneEvidenceEncoding(connection)
                    }
                    if (version < 1) {
                        statement.executeUpdate(
                            "INSERT OR IGNORE INTO actual_schema_migrations(version, name, applied_at_ns) " +
                                "VALUES (1, 'forward_only_actual_v1', ${Instant.now().toEpochNanos()})",
                        )
                    }
                    if (version < 2) {
                        statement.executeUpdate(
                            "INSERT OR IGNORE INTO actual_schema_migrations(version, name, applied_at_ns) " +
                                "VALUES (2, 'persisted_at_v2', ${Instant.now().toEpochNanos()})",
                        )
                    }
                    if (version < 3) {
                        statement.executeUpdate(
                            "INSERT OR IGNORE INTO actual_schema_migrations(version, name, applied_at_ns) " +
                                "VALUES (3, 'explicit_sources_and_valuation_v3', ${Instant.now().toEpochNanos()})",
                        )
                    }
                    if (version < 4) {
                        statement.execute(
                            """
                            CREATE TABLE IF NOT EXISTS benchmark_segments (
                                segment_id TEXT PRIMARY KEY,
                                baseline_observation_id TEXT NOT NULL REFERENCES actual_observations(observation_id),
                                account_identity_digest TEXT NOT NULL,
                                scope_fingerprint TEXT NOT NULL,
                                scope_symbols TEXT NOT NULL,
                                baseline_at_ns INTEGER NOT NULL,
                                initial_holdings TEXT NOT NULL,
                                baseline_marks TEXT NOT NULL,
                                baseline_total_usd TEXT NOT NULL,
                                status TEXT NOT NULL CHECK(status IN ('TRACKING', 'TERMINATED', 'INVALID')),
                                termination_reason TEXT,
                                last_verified_event_time_ns INTEGER NOT NULL,
                                created_at_ns INTEGER NOT NULL
                            )
                            """.trimIndent(),
                        )
                        statement.execute(
                            "CREATE INDEX IF NOT EXISTS idx_benchmark_segments_scope_account " +
                                "ON benchmark_segments(scope_fingerprint, account_identity_digest, created_at_ns)",
                        )
                        statement.executeUpdate(
                            "INSERT OR IGNORE INTO actual_schema_migrations(version, name, applied_at_ns) " +
                                "VALUES (4, 'prospective_benchmark_segments_v4', ${Instant.now().toEpochNanos()})",
                        )
                    }
                }
                connection.commit()
            } catch (e: Exception) {
                connection.rollback()
                throw e
            }
        }
    }

    fun <T> withConnection(block: (Connection) -> T): T {
        initializeSchema()
        return connect().use(block)
    }

    private fun connect(): Connection {
        val path = databaseFile(actualDatabasePath)
        return DriverManager.getConnection("jdbc:sqlite:$path").also { connection ->
            try {
                connection.createStatement().use { statement ->
                    statement.execute("PRAGMA foreign_keys = ON")
                    statement.execute("PRAGMA busy_timeout = $BUSY_TIMEOUT_MILLIS")
                    statement.execute("PRAGMA synchronous = FULL")
                    statement.executeQuery("PRAGMA journal_mode = WAL").use { resultSet ->
                        check(resultSet.next() && resultSet.getString(1).equals("wal", ignoreCase = true)) {
                            "Actual observation database could not enter WAL mode."
                        }
                    }
                }
            } catch (e: Exception) {
                connection.close()
                throw e
            }
        }
    }

    private fun ActualObservation.payloadHash(): String {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { output ->
            output.writeNullableString(observationId)
            output.writeNullableString(accountIdentityDigest)
            output.writeNullableString(walletScope)
            output.writeNullableString(balanceSource)
            output.writeNullableString(priceSource)
            output.writeNullableString(valuationCurrency)
            output.writeNullableString(observationSchemaVersion.toString())
            output.writeNullableString(scopeFingerprint)
            output.writeStringList(scopeSymbols)
            output.writeNullableString(observedAt.toString())
            output.writeNullableString(balanceRequestStartedAt.toString())
            output.writeNullableString(balanceResponseEndedAt.toString())
            output.writeNullableString(priceRequestStartedAt.toString())
            output.writeNullableString(priceResponseEndedAt.toString())
            output.writeNullableString(status.name)
            output.writeNullableString(totalUsd?.toPlainString())
            output.writeStringList(incompleteReasons)
            val orderedAssets = assets.sortedBy(ActualAssetObservation::symbol)
            output.writeInt(orderedAssets.size)
            orderedAssets.forEach { asset ->
                output.writeNullableString(asset.symbol)
                output.writeNullableString(asset.status.name)
                output.writeStringList(asset.rawBalanceKeys.sorted())
                output.writeStringMap(asset.rawBalanceValues)
                output.writeNullableString(asset.quantity?.toPlainString())
                output.writeNullableString(asset.requestedPair)
                output.writeNullableString(asset.responsePair)
                output.writeStringList(asset.candidateResponsePairs.sorted())
                output.writeStringMap(asset.candidateRawPrices)
                output.writeNullableString(asset.rawPrice)
                output.writeNullableString(asset.priceUsd?.toPlainString())
                output.writeNullableString(asset.valueUsd?.toPlainString())
                output.writeNullableString(asset.reason)
            }
        }
        return bytes.toByteArray().sha256()
    }

    /** Hash format used before v2; retained only to recognize idempotent retries during migration. */
    private fun ActualObservation.legacyPayloadHash(): String {
        val fields = buildList {
            add(observationId)
            add(accountIdentityDigest)
            add(walletScope)
            add(scopeFingerprint)
            addAll(scopeSymbols)
            add(observedAt.toString())
            add(balanceRequestStartedAt.toString())
            add(balanceResponseEndedAt.toString())
            add(priceRequestStartedAt.toString())
            add(priceResponseEndedAt.toString())
            add(status.name)
            add(totalUsd?.toPlainString().orEmpty())
            addAll(incompleteReasons)
            assets.sortedBy(ActualAssetObservation::symbol).forEach { asset ->
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
        return bytes.toByteArray().sha256()
    }

    private fun DataOutputStream.writeNullableString(value: String?) {
        if (value == null) {
            writeByte(0)
        } else {
            val bytes = value.toByteArray(Charsets.UTF_8)
            writeByte(1)
            writeInt(bytes.size)
            write(bytes)
        }
    }

    private fun DataOutputStream.writeStringList(values: List<String>) {
        writeInt(values.size)
        values.forEach { writeNullableString(it) }
    }

    private fun DataOutputStream.writeStringMap(values: Map<String, String?>) {
        val sorted = values.toSortedMap()
        writeInt(sorted.size)
        sorted.forEach { (key, value) ->
            writeNullableString(key)
            writeNullableString(value)
        }
    }

    private fun ByteArray.sha256(): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(this)
        return digest.joinToString("") { byte -> "%02x".format(byte) }
    }

    private data class ObservationHeader(
        val id: String,
        val accountIdentityDigest: String,
        val walletScope: String,
        val balanceSource: String,
        val priceSource: String,
        val valuationCurrency: String,
        val observationSchemaVersion: Int,
        val scopeFingerprint: String,
        val scopeSymbols: List<String>,
        val observedAt: Instant,
        val balanceRequestStartedAt: Instant,
        val balanceResponseEndedAt: Instant,
        val priceRequestStartedAt: Instant,
        val priceResponseEndedAt: Instant,
        val persistedAt: Instant?,
        val status: ActualObservationStatus,
        val totalUsd: java.math.BigDecimal?,
        val incompleteReasons: List<String>,
    )

    private data class LegacyAssetEncoding(
        val observationId: String,
        val symbol: String,
        val balanceKeys: List<String>,
        val balanceValues: Map<String, String?>,
        val candidatePairs: List<String>,
        val candidatePrices: Map<String, String?>,
    )

    private fun databaseFile(value: String): Path {
        require(value != ":memory:" && !value.contains("mode=memory") && !value.contains(":memory:")) {
            "Actual observations require a file-backed SQLite database."
        }
        val raw = value.removePrefix("jdbc:sqlite:").substringBefore('?').removePrefix("file:")
        require(raw.isNotBlank()) { "Actual observation database path cannot be blank." }
        val absolute = Path.of(raw).toAbsolutePath().normalize()
        val resolvedParent = absolute.parent?.takeIf(Files::isDirectory)?.toRealPath()
        val resolved = resolvedParent?.resolve(absolute.fileName) ?: absolute
        return if (Files.exists(resolved)) resolved.toRealPath() else resolved
    }

    private fun actualArtifacts(path: Path): Set<Path> = buildSet {
        add(path)
        listOf("-wal", "-shm", "-journal").forEach { suffix ->
            val artifact = path.resolveSibling("${path.fileName}$suffix")
            add(if (Files.exists(artifact)) artifact.toRealPath() else artifact)
        }
    }

    private fun sharesArtifact(left: Path, right: Path): Boolean {
        val leftArtifacts = actualArtifacts(left)
        val rightArtifacts = actualArtifacts(right)
        return leftArtifacts.any { leftArtifact ->
            rightArtifacts.any { rightArtifact ->
                leftArtifact == rightArtifact ||
                    (
                        Files.exists(leftArtifact) && Files.exists(rightArtifact) &&
                            Files.isSameFile(leftArtifact, rightArtifact)
                        )
            }
        }
    }

    private fun hasColumn(connection: Connection, table: String, column: String): Boolean =
        connection.createStatement().use { statement ->
            statement.executeQuery("PRAGMA table_info($table)").use { rows ->
                buildSet {
                    while (rows.next()) add(rows.getString("name"))
                }.contains(column)
            }
        }

    private fun migrateVersionOneEvidenceEncoding(connection: Connection) {
        connection.createStatement().use { statement ->
            statement.execute("DROP TRIGGER IF EXISTS actual_observation_assets_no_update")
        }
        val legacyRows = connection.createStatement().use { statement ->
            statement.executeQuery(
                "SELECT observation_id, asset_symbol, raw_balance_keys, raw_balance_values, " +
                    "candidate_response_pairs, candidate_raw_prices FROM actual_observation_assets",
            ).use { rows ->
                buildList {
                    while (rows.next()) {
                        add(
                            LegacyAssetEncoding(
                                observationId = rows.getString("observation_id"),
                                symbol = rows.getString("asset_symbol"),
                                balanceKeys = rows.getString("raw_balance_keys").splitLines(),
                                balanceValues = rows.getString("raw_balance_values").decodeLegacyPairs(),
                                candidatePairs = rows.getString("candidate_response_pairs").splitLines(),
                                candidatePrices = rows.getString("candidate_raw_prices").decodeLegacyPairs(),
                            ),
                        )
                    }
                }
            }
        }
        legacyRows.forEach { row ->
            check(row.balanceKeys.size == row.balanceKeys.distinct().size) {
                "Legacy Actual observation ${row.observationId}/${row.symbol} has ambiguous raw balance keys."
            }
            check(row.candidatePairs.size == row.candidatePairs.distinct().size) {
                "Legacy Actual observation ${row.observationId}/${row.symbol} has ambiguous ticker response pairs."
            }
            check(row.balanceKeys.toSet() == row.balanceValues.keys) {
                "Legacy Actual observation ${row.observationId}/${row.symbol} has incomplete raw balance evidence."
            }
            check(row.candidatePairs.toSet() == row.candidatePrices.keys) {
                "Legacy Actual observation ${row.observationId}/${row.symbol} has incomplete raw price evidence."
            }
        }
        connection.prepareStatement(
            "UPDATE actual_observation_assets SET raw_balance_keys = ?, raw_balance_values = ?, " +
                "candidate_response_pairs = ?, candidate_raw_prices = ? " +
                "WHERE observation_id = ? AND asset_symbol = ?",
        ).use { statement ->
            legacyRows.forEach { row ->
                statement.setString(1, encodeStrings(row.balanceKeys))
                statement.setString(2, encodePairs(row.balanceValues))
                statement.setString(3, encodeStrings(row.candidatePairs))
                statement.setString(4, encodePairs(row.candidatePrices))
                statement.setString(5, row.observationId)
                statement.setString(6, row.symbol)
                statement.addBatch()
            }
            statement.executeBatch()
        }
        connection.createStatement().use { statement ->
            statement.execute(
                "CREATE TRIGGER actual_observation_assets_no_update " +
                    "BEFORE UPDATE ON actual_observation_assets " +
                    "BEGIN SELECT RAISE(ABORT, 'actual observations are append-only'); END",
            )
        }
    }

    private fun String?.decodeLegacyPairs(): Map<String, String?> {
        if (this.isNullOrEmpty()) return emptyMap()
        val rows = lineSequence().toList()
        val pairs = rows.map { row ->
            val separator = row.indexOf('\u0000')
            require(separator > 0) { "Legacy Actual evidence encoding is ambiguous; preserving the original database." }
            row.substring(0, separator) to row.substring(separator + 1)
        }
        require(pairs.map { it.first }.distinct().size == pairs.size) {
            "Legacy Actual evidence contains duplicate keys; preserving the original database."
        }
        return pairs.toMap()
    }

    private fun encodeStrings(values: List<String>): String = values.joinToString("\n", transform = ::encodeString)

    private fun String?.decodeStrings(): List<String> = this?.takeIf(String::isNotEmpty)
        ?.lineSequence()
        ?.map(::decodeString)
        ?.toList()
        .orEmpty()

    private fun encodePairs(values: Map<String, String?>): String = values.toSortedMap().entries.joinToString("\n") {
        "${encodeString(it.key)}\t${it.value?.let(::encodeString) ?: NULL_MARKER}"
    }

    private fun String?.decodePairs(): Map<String, String?> = this?.takeIf(String::isNotEmpty)
        ?.lineSequence()
        ?.mapNotNull { row ->
            val separator = row.indexOf('\t')
            if (separator < 0) {
                null
            } else {
                val key = decodeString(row.substring(0, separator))
                val value = row.substring(separator + 1).let { encoded ->
                    if (encoded == NULL_MARKER) null else decodeString(encoded)
                }
                key to value
            }
        }
        ?.toMap()
        .orEmpty()

    private fun String?.splitLines(): List<String> = this?.takeIf(String::isNotEmpty)?.split('\n').orEmpty()

    private fun encodeString(value: String): String = Base64.getUrlEncoder().withoutPadding()
        .encodeToString(value.toByteArray(Charsets.UTF_8))

    private fun decodeString(value: String): String = String(
        Base64.getUrlDecoder().decode(value),
        Charsets.UTF_8,
    )

    private fun Instant.toEpochNanos(): Long =
        Math.addExact(Math.multiplyExact(epochSecond, NANOS_PER_SECOND), nano.toLong())

    private fun Long.toInstant(): Instant = Instant.ofEpochSecond(
        Math.floorDiv(this, NANOS_PER_SECOND),
        Math.floorMod(this, NANOS_PER_SECOND),
    )

    private companion object {
        const val LEGACY_PAYLOAD_HASH_VERSION = 1
        const val CURRENT_PAYLOAD_HASH_VERSION = 2
        const val SCHEMA_VERSION = 4
        const val MAX_QUERY_LIMIT = 500
        const val BUSY_TIMEOUT_MILLIS = 1500
        const val NANOS_PER_SECOND = 1_000_000_000L
        const val NULL_MARKER = "~"
    }
}
