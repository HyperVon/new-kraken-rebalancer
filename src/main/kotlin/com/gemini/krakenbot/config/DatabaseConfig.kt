package com.gemini.krakenbot.config

import com.gemini.krakenbot.model.OrderIntentState
import com.gemini.krakenbot.model.OrderSubmissionState
import com.gemini.krakenbot.model.TradeSource
import com.gemini.krakenbot.repository.table.ActionLogTable
import com.gemini.krakenbot.repository.table.AssetSnapshotTable
import com.gemini.krakenbot.repository.table.HistorySyncMetadataTable
import com.gemini.krakenbot.repository.table.LedgerTable
import com.gemini.krakenbot.repository.table.OrderIntentTable
import com.gemini.krakenbot.repository.table.PortfolioSnapshotTable
import com.gemini.krakenbot.repository.table.PortfolioStatsTable
import com.gemini.krakenbot.repository.table.SchemaMigrationTable
import com.gemini.krakenbot.repository.table.TradeTable
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.exists
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.jetbrains.exposed.v1.jdbc.vendors.currentDialectMetadata
import org.slf4j.LoggerFactory
import java.io.File
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.sql.Connection
import java.sql.DriverManager
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

object DatabaseConfig {
    private val log = LoggerFactory.getLogger(DatabaseConfig::class.java)
    private const val CURRENT_SCHEMA_VERSION = 5
    private const val AMBIGUOUS_LEGACY_CLIENT_ORDER_ID_PREFIX = "Ambiguous legacy client_order_id '"

    // A shared in-memory SQLite database disappears when its last connection closes.
    // Retain one connection per URL until JVM shutdown so pooled test connections share stable state.
    private val keepAliveConnections = ConcurrentHashMap<String, Connection>()

    init {
        Runtime.getRuntime().addShutdownHook(
            object : Thread() {
                override fun run() {
                    keepAliveConnections.values.forEach { connection ->
                        try {
                            connection.close()
                        } catch (e: Exception) {
                            log.warn("Failed to close keepalive connection", e)
                        }
                    }
                    keepAliveConnections.clear()
                }
            },
        )
    }

    private val baseTables =
        arrayOf(
            SchemaMigrationTable,
            PortfolioSnapshotTable,
            AssetSnapshotTable,
            TradeTable,
            LedgerTable,
            PortfolioStatsTable,
            ActionLogTable,
            HistorySyncMetadataTable,
        )

    private val allTables = baseTables + OrderIntentTable

    private data class ExpectedIndex(
        val tableName: String,
        val name: String,
        val unique: Boolean,
        val columns: List<String>,
    )

    private val expectedIndexes = listOf(
        ExpectedIndex("portfolio_snapshots", "idx_snapshots_timestamp", false, listOf("timestamp")),
        ExpectedIndex("ledgers", "idx_ledgers_timestamp", false, listOf("timestamp")),
        ExpectedIndex("ledgers", "idx_ledgers_refid", false, listOf("refid")),
        ExpectedIndex("ledgers", "idx_ledgers_dedupe", true, listOf("ledger_id", "timestamp", "asset", "type")),
        ExpectedIndex("trades", "idx_trades_timestamp", false, listOf("timestamp")),
        ExpectedIndex(
            "trades",
            "idx_trades_pair_side_timestamp",
            false,
            listOf("pair", "side", "timestamp"),
        ),
        ExpectedIndex("trades", "idx_trades_success", false, listOf("success")),
        ExpectedIndex("trades", "idx_trades_cycle_id", false, listOf("cycle_id")),
        ExpectedIndex("trades", "idx_trades_trade_id", false, listOf("trade_id")),
        ExpectedIndex("trades", "idx_trades_submission_state", false, listOf("submission_state")),
        ExpectedIndex("order_intents", "idx_order_intents_state", false, listOf("state")),
        ExpectedIndex("order_intents", "idx_order_intents_created_at", false, listOf("created_at")),
        ExpectedIndex("order_intents", "idx_order_intents_local_trade_id", false, listOf("local_trade_id")),
        ExpectedIndex("order_intents", "ux_order_intents_client_order_id", true, listOf("client_order_id")),
        ExpectedIndex("action_logs", "idx_actionlogs_snapshot_id", false, listOf("snapshot_id")),
        ExpectedIndex("asset_snapshots", "idx_assetsnapshots_snapshot_id", false, listOf("snapshot_id")),
    )

    private data class LegacyCandidate(val exactKey: String, val relaxedKey: String, val id: Int)

    private data class TerminalIntent(
        val localTradeId: Int,
        val clientOrderId: String?,
        val clientOrderIdAmbiguous: Boolean,
        val createdAt: Long,
        val pair: String,
        val symbol: String,
        val side: String,
        val volume: BigDecimal,
        val usdAmount: BigDecimal,
        val state: OrderIntentState,
        val orderTxid: String?,
        val errorMessage: String?,
        val resolutionEvidence: String?,
    )

    fun init(dbPath: String = System.getProperty("kraken.db.path", "kraken-rebalancer.db")): Database {
        backupBeforeMigrationIfNeeded(dbPath)
        val url = buildSqliteUrl(dbPath)
        maintainMemoryDatabase(url)

        return Database.connect(url).also { database ->
            // SchemaUtils.createMissingTablesAndColumns is deprecated; use the non-deprecated
            // building blocks sequentially within one transaction instead.
            transaction(database) {
                currentDialectMetadata.resetCaches()

                val (tablesToCreate, tablesToAlter) = baseTables.partition { !it.exists() }
                val createStatements = SchemaUtils.createStatements(*tablesToCreate.toTypedArray())
                createStatements.forEach { exec(it) }

                val alterStatements = SchemaUtils.addMissingColumnsStatements(
                    tables = tablesToAlter.toTypedArray(),
                    withLogs = false,
                )
                alterStatements.forEach { exec(it) }
                currentDialectMetadata.resetCaches()

                // Rows written before provenance existed cannot distinguish a settled API fill
                // from a local order estimate when both have no slippage. Preserve that ambiguity
                // rather than treating it as an API fill and risking an unsafe reconciliation.
                TradeTable.update({
                    TradeTable.tradeSource.isNull() and
                        (TradeTable.success eq true) and
                        (TradeTable.dryRun eq false) and
                        TradeTable.errorMessage.isNull() and
                        TradeTable.slippagePercent.isNull()
                }) {
                    it[TradeTable.tradeSource] = TradeSource.LEGACY_UNKNOWN.name
                }

                applySchemaMigrations()
                val orderIntentAlterStatements = SchemaUtils.addMissingColumnsStatements(
                    tables = arrayOf(OrderIntentTable),
                    withLogs = false,
                )
                orderIntentAlterStatements.forEach { exec(it) }
                currentDialectMetadata.resetCaches()
                markLegacyAmbiguousClientOrderIds()
                backfillLegacyTradeIds()
                reconcileTerminalOrderIntents()
                reconcileLegacySubmissionGuards()
                recoverPendingOrderIntents()
                repairInvalidIndexes()
                currentDialectMetadata.resetCaches()

                val executedStatements = createStatements + alterStatements + orderIntentAlterStatements
                val mappingStatements =
                    SchemaUtils
                        .checkMappingConsistence(tables = allTables, withLogs = false)
                        .filter { it !in executedStatements }
                mappingStatements.forEach { exec(it) }

                currentDialectMetadata.resetCaches()
            }
            log.info("Database initialized successfully.")
        }
    }

    private fun JdbcTransaction.applySchemaMigrations() {
        val appliedVersion =
            SchemaMigrationTable
                .select(SchemaMigrationTable.version)
                .orderBy(SchemaMigrationTable.version, SortOrder.DESC)
                .limit(1)
                .firstOrNull()
                ?.get(SchemaMigrationTable.version)
                ?: 0

        if (appliedVersion < 1) {
            SchemaMigrationTable.insert {
                it[version] = 1
                it[name] = "baseline"
                it[appliedAt] = Instant.now().toEpochMilli()
            }
        }

        if (!OrderIntentTable.exists()) {
            currentDialectMetadata.resetCaches()
            SchemaUtils.createStatements(OrderIntentTable).forEach { exec(it) }
            currentDialectMetadata.resetCaches()
        }

        if (appliedVersion < 2) {
            SchemaMigrationTable.insert {
                it[version] = 2
                it[name] = "order-intent-journal"
                it[appliedAt] = Instant.now().toEpochMilli()
            }
        }

        if (appliedVersion < 3) {
            SchemaMigrationTable.insert {
                it[version] = 3
                it[name] = "legacy-submission-guard-import"
                it[appliedAt] = Instant.now().toEpochMilli()
            }
        }

        if (appliedVersion < 4) {
            SchemaMigrationTable.insert {
                it[version] = 4
                it[name] = "legacy-trade-identity"
                it[appliedAt] = Instant.now().toEpochMilli()
            }
        }

        if (appliedVersion < 5) {
            SchemaMigrationTable.insert {
                it[version] = 5
                it[name] = "ambiguous-legacy-client-order-id"
                it[appliedAt] = Instant.now().toEpochMilli()
            }
        }
    }

    private fun JdbcTransaction.markLegacyAmbiguousClientOrderIds() {
        OrderIntentTable
            .select(OrderIntentTable.id, OrderIntentTable.errorMessage)
            .where {
                OrderIntentTable.clientOrderId.isNull() and
                    (OrderIntentTable.clientOrderIdAmbiguous eq false)
            }
            .map { it[OrderIntentTable.id] to it[OrderIntentTable.errorMessage] }
            .filter { (_, errorMessage) ->
                errorMessage?.startsWith(AMBIGUOUS_LEGACY_CLIENT_ORDER_ID_PREFIX) == true
            }
            .forEach { (id, _) ->
                OrderIntentTable.update({ OrderIntentTable.id eq id }) {
                    it[OrderIntentTable.clientOrderIdAmbiguous] = true
                }
            }
    }

    private fun JdbcTransaction.backfillLegacyTradeIds() {
        val assignedTradeIds = OrderIntentTable
            .select(OrderIntentTable.localTradeId)
            .where { OrderIntentTable.localTradeId.isNotNull() }
            .map { it[OrderIntentTable.localTradeId]!! }
            .toMutableSet()
        val intentCandidates = OrderIntentTable
            .selectAll()
            .where { OrderIntentTable.localTradeId.isNull() }
            .orderBy(OrderIntentTable.id, SortOrder.ASC)
            .map { row ->
                legacyCandidate(
                    clientOrderId = row[OrderIntentTable.clientOrderId],
                    timestamp = row[OrderIntentTable.createdAt],
                    pair = row[OrderIntentTable.pair],
                    symbol = row[OrderIntentTable.symbol],
                    side = row[OrderIntentTable.side],
                    volume = row[OrderIntentTable.volume].toPlainString(),
                    usdAmount = row[OrderIntentTable.usdAmount].toPlainString(),
                    id = row[OrderIntentTable.id],
                )
            }
        val tradeCandidates = TradeTable
            .selectAll()
            .where {
                (TradeTable.dryRun eq false) and
                    (
                        (TradeTable.tradeSource eq TradeSource.LOCAL_ESTIMATE.name) or
                            TradeTable.tradeSource.isNull()
                        )
            }
            .orderBy(TradeTable.id, SortOrder.ASC)
            .toList()
            .mapNotNull { row ->
                val tradeId = row[TradeTable.id]
                if (tradeId in assignedTradeIds) {
                    null
                } else {
                    legacyCandidate(
                        clientOrderId = row[TradeTable.clientOrderId],
                        timestamp = row[TradeTable.timestamp],
                        pair = row[TradeTable.pair],
                        symbol = row[TradeTable.symbol],
                        side = row[TradeTable.side],
                        volume = row[TradeTable.volume].toPlainString(),
                        usdAmount = row[TradeTable.usdAmount].toPlainString(),
                        id = tradeId,
                    )
                }
            }
        val assignedIntentIds = mutableSetOf<Int>()

        fun assignBy(keySelector: (LegacyCandidate) -> String) {
            val tradesByKey = tradeCandidates
                .filter { it.id !in assignedTradeIds }
                .groupBy(keySelector)
            intentCandidates
                .filter { it.id !in assignedIntentIds }
                .groupBy(keySelector)
                .forEach { (key, intents) ->
                    val trades = tradesByKey[key].orEmpty()
                    if (intents.size != 1 || trades.size != 1) return@forEach
                    val intent = intents.single()
                    val trade = trades.single()
                    val updatedRows = OrderIntentTable.update({
                        (OrderIntentTable.id eq intent.id) and OrderIntentTable.localTradeId.isNull()
                    }) {
                        it[OrderIntentTable.localTradeId] = trade.id
                    }
                    check(updatedRows == 1) {
                        "Expected to link one legacy order intent, but updated $updatedRows for intent ${intent.id}."
                    }
                    assignedIntentIds += intent.id
                    assignedTradeIds += trade.id
                }
        }

        // Exact matching is preferred. The relaxed pass supports the merged journal's original
        // separate Instant.now() calls while still requiring a unique immutable identity.
        assignBy(LegacyCandidate::exactKey)
        assignBy(LegacyCandidate::relaxedKey)
    }

    private fun legacyCandidate(
        clientOrderId: String?,
        timestamp: Long,
        pair: String,
        symbol: String,
        side: String,
        volume: String,
        usdAmount: String,
        id: Int,
    ): LegacyCandidate {
        val common = LegacyIdentity(
            clientOrderId = clientOrderId,
            pair = pair,
            symbol = symbol,
            side = side,
            volume = volume,
            usdAmount = usdAmount,
        )
        return LegacyCandidate(
            exactKey = legacyIdentityKey(common, timestamp),
            relaxedKey = legacyIdentityKey(common, null),
            id = id,
        )
    }

    private data class LegacyIdentity(
        val clientOrderId: String?,
        val pair: String,
        val symbol: String,
        val side: String,
        val volume: String,
        val usdAmount: String,
    )

    private fun legacyIdentityKey(identity: LegacyIdentity, timestamp: Long?): String {
        val fingerprint = listOfNotNull(
            timestamp?.toString(),
            identity.pair,
            identity.symbol,
            identity.side,
            identity.volume,
            identity.usdAmount,
        ).joinToString(":")
        return identity.clientOrderId?.let { "CLIENT:$it:$fingerprint" } ?: "LEGACY:$fingerprint"
    }

    private fun JdbcTransaction.reconcileTerminalOrderIntents() {
        val terminalIntents = OrderIntentTable
            .selectAll()
            .where {
                (
                    OrderIntentTable.state inList listOf(
                        OrderIntentState.CONFIRMED.name,
                        OrderIntentState.REJECTED.name,
                    )
                    ) and OrderIntentTable.localTradeId.isNotNull()
            }
            .map { row ->
                TerminalIntent(
                    localTradeId = row[OrderIntentTable.localTradeId]!!,
                    clientOrderId = row[OrderIntentTable.clientOrderId],
                    clientOrderIdAmbiguous = row[OrderIntentTable.clientOrderIdAmbiguous],
                    createdAt = row[OrderIntentTable.createdAt],
                    pair = row[OrderIntentTable.pair],
                    symbol = row[OrderIntentTable.symbol],
                    side = row[OrderIntentTable.side],
                    volume = row[OrderIntentTable.volume],
                    usdAmount = row[OrderIntentTable.usdAmount],
                    state = OrderIntentState.valueOf(row[OrderIntentTable.state]),
                    orderTxid = row[OrderIntentTable.orderTxid],
                    errorMessage = row[OrderIntentTable.errorMessage],
                    resolutionEvidence = row[OrderIntentTable.resolutionEvidence],
                )
            }
        terminalIntents
            .groupBy(TerminalIntent::localTradeId)
            .forEach { (localTradeId, intents) ->
                check(intents.size == 1) {
                    "Multiple terminal order intents reference local trade $localTradeId."
                }
                val resolvedIntent = intents.single()
                val clientOrderIdentity = when {
                    resolvedIntent.clientOrderId != null -> TradeTable.clientOrderId eq resolvedIntent.clientOrderId
                    resolvedIntent.clientOrderIdAmbiguous -> TradeTable.id eq TradeTable.id
                    else -> TradeTable.clientOrderId.isNull()
                }
                val tradeIdentity = clientOrderIdentity and
                    (TradeTable.timestamp eq resolvedIntent.createdAt) and
                    (TradeTable.pair eq resolvedIntent.pair) and
                    (TradeTable.symbol eq resolvedIntent.symbol) and
                    (TradeTable.side eq resolvedIntent.side) and
                    (TradeTable.volume eq resolvedIntent.volume) and
                    (TradeTable.usdAmount eq resolvedIntent.usdAmount) and
                    (TradeTable.dryRun eq false) and
                    ((TradeTable.tradeSource eq TradeSource.LOCAL_ESTIMATE.name) or TradeTable.tradeSource.isNull()) and
                    (
                        TradeTable.submissionState inList listOf(
                            OrderSubmissionState.PENDING.name,
                            OrderSubmissionState.UNCERTAIN.name,
                        )
                        )
                val updatedRows = TradeTable.update({
                    (TradeTable.id eq resolvedIntent.localTradeId) and tradeIdentity
                }) {
                    it[TradeTable.success] = resolvedIntent.state == OrderIntentState.CONFIRMED
                    it[TradeTable.errorMessage] = if (resolvedIntent.state == OrderIntentState.CONFIRMED) {
                        null
                    } else {
                        resolvedIntent.resolutionEvidence ?: resolvedIntent.errorMessage
                    }
                    if (resolvedIntent.orderTxid != null) {
                        it[TradeTable.orderTxid] = resolvedIntent.orderTxid
                    }
                    it[TradeTable.submissionState] = null
                }
                if (updatedRows == 0) {
                    val currentTrade = TradeTable
                        .selectAll()
                        .where { TradeTable.id eq resolvedIntent.localTradeId }
                        .firstOrNull()
                    check(currentTrade != null) {
                        "Cannot reconcile terminal order intent for missing local trade " +
                            resolvedIntent.localTradeId
                    }
                    val clientOrderMatches = when {
                        resolvedIntent.clientOrderIdAmbiguous -> true

                        resolvedIntent.clientOrderId != null ->
                            currentTrade[TradeTable.clientOrderId] == resolvedIntent.clientOrderId

                        else -> currentTrade[TradeTable.clientOrderId] == null
                    }
                    val immutableIdentityMatches = clientOrderMatches &&
                        currentTrade[TradeTable.timestamp] == resolvedIntent.createdAt &&
                        currentTrade[TradeTable.pair] == resolvedIntent.pair &&
                        currentTrade[TradeTable.symbol] == resolvedIntent.symbol &&
                        currentTrade[TradeTable.side] == resolvedIntent.side &&
                        currentTrade[TradeTable.volume].compareTo(resolvedIntent.volume) == 0 &&
                        currentTrade[TradeTable.usdAmount].compareTo(resolvedIntent.usdAmount) == 0 &&
                        !currentTrade[TradeTable.dryRun] &&
                        currentTrade[TradeTable.tradeSource] in listOf(null, TradeSource.LOCAL_ESTIMATE.name)
                    check(immutableIdentityMatches) {
                        "Cannot reconcile terminal order intent for local trade " +
                            "${resolvedIntent.localTradeId}: immutable trade identity changed."
                    }
                    val currentSubmissionState = currentTrade[TradeTable.submissionState]
                    check(
                        currentSubmissionState != OrderSubmissionState.PENDING.name &&
                            currentSubmissionState != OrderSubmissionState.UNCERTAIN.name,
                    ) {
                        "Cannot reconcile terminal order intent for local trade " +
                            "${resolvedIntent.localTradeId}: submission is still unresolved."
                    }
                }
            }
    }

    private fun JdbcTransaction.recoverPendingOrderIntents() {
        val recoveryMessage = "Recovered pending intent after restart; verify Kraken before resolution."
        OrderIntentTable
            .select(OrderIntentTable.id, OrderIntentTable.errorMessage)
            .where { OrderIntentTable.state eq OrderIntentState.PENDING.name }
            .map { it[OrderIntentTable.id] to it[OrderIntentTable.errorMessage] }
            .forEach { (id, errorMessage) ->
                OrderIntentTable.update({
                    (OrderIntentTable.id eq id) and
                        (OrderIntentTable.state eq OrderIntentState.PENDING.name)
                }) {
                    it[OrderIntentTable.state] = OrderIntentState.UNCERTAIN.name
                    if (errorMessage == null) {
                        it[OrderIntentTable.errorMessage] = recoveryMessage
                    }
                }
            }
    }

    private fun JdbcTransaction.reconcileLegacySubmissionGuards() {
        val guardRows = TradeTable
            .selectAll()
            .where {
                (TradeTable.dryRun eq false) and
                    (
                        TradeTable.submissionState inList listOf(
                            OrderSubmissionState.PENDING.name,
                            OrderSubmissionState.UNCERTAIN.name,
                        )
                        )
            }
            .orderBy(TradeTable.id, SortOrder.ASC)
            .toList()
        val duplicateClientOrderIds = TradeTable
            .select(TradeTable.clientOrderId)
            .where {
                (TradeTable.dryRun eq false) and
                    (
                        (TradeTable.tradeSource eq TradeSource.LOCAL_ESTIMATE.name) or
                            TradeTable.tradeSource.isNull()
                        )
            }
            .mapNotNull { it[TradeTable.clientOrderId] }
            .groupingBy { it }
            .eachCount()
            .filterValues { it > 1 }
            .keys
        guardRows.forEach { row ->
            val tradeId = row[TradeTable.id]
            val clientOrderId = row[TradeTable.clientOrderId]
            val conflictingIntent = clientOrderId != null && OrderIntentTable
                .select(OrderIntentTable.localTradeId)
                .where { OrderIntentTable.clientOrderId eq clientOrderId }
                .any { it[OrderIntentTable.localTradeId] != tradeId }
            val ambiguousClientOrderId = clientOrderId != null &&
                (clientOrderId in duplicateClientOrderIds || conflictingIntent)
            val alreadyImported = OrderIntentTable
                .select(OrderIntentTable.id)
                .where { OrderIntentTable.localTradeId eq tradeId }
                .any()
            val ambiguityMessage = if (ambiguousClientOrderId) {
                "$AMBIGUOUS_LEGACY_CLIENT_ORDER_ID_PREFIX$clientOrderId'; " +
                    "verify this trade before resolution. "
            } else {
                ""
            }
            val reusableIntent = clientOrderId?.takeIf { ambiguousClientOrderId }?.let { ambiguousClientId ->
                OrderIntentTable
                    .select(OrderIntentTable.id, OrderIntentTable.errorMessage)
                    .where {
                        (
                            (OrderIntentTable.clientOrderId eq ambiguousClientId) or
                                (
                                    OrderIntentTable.clientOrderId.isNull() and
                                        (OrderIntentTable.clientOrderIdAmbiguous eq true)
                                    )
                            ) and
                            OrderIntentTable.localTradeId.isNull() and
                            (OrderIntentTable.pair eq row[TradeTable.pair]) and
                            (OrderIntentTable.symbol eq row[TradeTable.symbol]) and
                            (OrderIntentTable.side eq row[TradeTable.side]) and
                            (OrderIntentTable.volume eq row[TradeTable.volume]) and
                            (OrderIntentTable.usdAmount eq row[TradeTable.usdAmount])
                    }
                    .orderBy(OrderIntentTable.id, SortOrder.ASC)
                    .firstOrNull()
            }
            val unlinkedConflictingIntent = clientOrderId != null && OrderIntentTable
                .select(OrderIntentTable.id)
                .where {
                    (OrderIntentTable.clientOrderId eq clientOrderId) and
                        OrderIntentTable.localTradeId.isNull()
                }
                .any()
            check(!unlinkedConflictingIntent || reusableIntent != null) {
                "Cannot safely migrate legacy client_order_id '$clientOrderId' for trade $tradeId."
            }
            if (reusableIntent != null) {
                val updatedRows = OrderIntentTable.update({
                    OrderIntentTable.id eq reusableIntent[OrderIntentTable.id]
                }) {
                    it[OrderIntentTable.clientOrderId] = null
                    it[OrderIntentTable.clientOrderIdAmbiguous] = true
                    it[OrderIntentTable.localTradeId] = tradeId
                    it[OrderIntentTable.errorMessage] = ambiguityMessage +
                        (reusableIntent[OrderIntentTable.errorMessage] ?: row[TradeTable.errorMessage] ?: "")
                }
                check(updatedRows == 1) {
                    "Expected to update one ambiguous legacy order intent, but updated $updatedRows."
                }
            } else if (!alreadyImported) {
                OrderIntentTable.insert {
                    it[OrderIntentTable.cycleId] = row[TradeTable.cycleId]
                    it[OrderIntentTable.clientOrderId] = if (ambiguousClientOrderId) null else clientOrderId
                    it[OrderIntentTable.clientOrderIdAmbiguous] = ambiguousClientOrderId
                    it[OrderIntentTable.pair] = row[TradeTable.pair]
                    it[OrderIntentTable.symbol] = row[TradeTable.symbol]
                    it[OrderIntentTable.side] = row[TradeTable.side]
                    it[OrderIntentTable.volume] = row[TradeTable.volume]
                    it[OrderIntentTable.usdAmount] = row[TradeTable.usdAmount]
                    it[OrderIntentTable.expectedPrice] = row[TradeTable.expectedPrice]
                    it[OrderIntentTable.createdAt] = row[TradeTable.timestamp]
                    it[OrderIntentTable.state] = row[TradeTable.submissionState]!!
                    it[OrderIntentTable.orderTxid] = row[TradeTable.orderTxid]
                    it[OrderIntentTable.errorMessage] = if (ambiguousClientOrderId) {
                        ambiguityMessage + (row[TradeTable.errorMessage] ?: "")
                    } else {
                        row[TradeTable.errorMessage]
                    }
                    it[OrderIntentTable.resolvedAt] = null
                    it[OrderIntentTable.resolutionEvidence] = null
                    it[OrderIntentTable.localTradeId] = tradeId
                }
            }
        }
    }

    private fun JdbcTransaction.readIndexDefinition(index: ExpectedIndex): Pair<Boolean, List<String>>? {
        val unique = exec("PRAGMA index_list('${index.tableName}')") { resultSet ->
            var value: Boolean? = null
            while (resultSet.next()) {
                if (resultSet.getString("name") == index.name) {
                    value = resultSet.getInt("unique") != 0
                    break
                }
            }
            value
        } ?: return null
        val columns = exec("PRAGMA index_info('${index.name}')") { resultSet ->
            buildList {
                while (resultSet.next()) {
                    add(resultSet.getString("name") ?: "")
                }
            }
        } ?: emptyList()
        return unique to columns
    }

    private fun JdbcTransaction.repairInvalidIndexes() {
        expectedIndexes.forEach { expectedIndex ->
            val actualDefinition = readIndexDefinition(expectedIndex)
            if (actualDefinition != null && actualDefinition != (expectedIndex.unique to expectedIndex.columns)) {
                exec("DROP INDEX IF EXISTS ${expectedIndex.name}")
            }
        }
    }

    private fun backupBeforeMigrationIfNeeded(dbPath: String) {
        val databaseFile = resolveFileBackedDatabase(dbPath) ?: return
        if (!databaseFile.isFile || databaseFile.length() == 0L || !requiresMigrationBackup(databaseFile)) return

        val source = databaseFile.toPath()
        val backup = source.resolveSibling(
            "${source.fileName}.pre-migration-${Instant.now().toEpochMilli()}.bak",
        )
        try {
            DriverManager.getConnection("jdbc:sqlite:${databaseFile.path}").use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute("PRAGMA wal_checkpoint(TRUNCATE)")
                }
            }
            Files.copy(source, backup, StandardCopyOption.COPY_ATTRIBUTES)
            log.warn("Created pre-migration database backup at {}", backup)
        } catch (e: Exception) {
            throw IllegalStateException("Cannot create pre-migration database backup for $dbPath", e)
        }
    }

    private fun resolveFileBackedDatabase(dbPath: String): File? {
        val sqlitePath = dbPath.removePrefix("jdbc:sqlite:")
        val querylessPath = sqlitePath.substringBefore('?')
        if (dbPath == ":memory:" ||
            querylessPath == ":memory:" ||
            sqlitePath.contains("mode=memory", ignoreCase = true)
        ) {
            return null
        }

        val filePath = querylessPath.removePrefix("file:")
        return filePath.takeIf { it.isNotBlank() }?.let(::File)
    }

    private fun requiresMigrationBackup(databaseFile: File): Boolean = try {
        DriverManager.getConnection("jdbc:sqlite:${databaseFile.path}").use { connection ->
            connection.createStatement().use { statement ->
                fun exists(query: String): Boolean = statement.executeQuery(query).use { resultSet ->
                    resultSet.next()
                }

                val schemaMigrationsExist = exists(
                    "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = 'schema_migrations' LIMIT 1",
                )
                val version = if (schemaMigrationsExist) {
                    statement.executeQuery("SELECT MAX(version) FROM schema_migrations").use { resultSet ->
                        if (resultSet.next()) resultSet.getInt(1) else 0
                    }
                } else {
                    0
                }
                val orderIntentTableExists = exists(
                    "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = 'order_intents' LIMIT 1",
                )
                val localTradeIdColumnExists = orderIntentTableExists && statement.executeQuery(
                    "PRAGMA table_info(order_intents)",
                ).use { resultSet ->
                    generateSequence {
                        if (resultSet.next()) resultSet.getString("name") else null
                    }.any { it == "local_trade_id" }
                }
                fun readIndexDefinition(index: ExpectedIndex): Pair<Boolean, List<String>>? {
                    val unique = statement.executeQuery(
                        "PRAGMA index_list('${index.tableName}')",
                    ).use { resultSet ->
                        var value: Boolean? = null
                        while (resultSet.next()) {
                            if (resultSet.getString("name") == index.name) {
                                value = resultSet.getInt("unique") != 0
                                break
                            }
                        }
                        value
                    } ?: return null
                    val columns = statement.executeQuery(
                        "PRAGMA index_info('${index.name}')",
                    ).use { resultSet ->
                        buildList {
                            while (resultSet.next()) {
                                add(resultSet.getString("name") ?: "")
                            }
                        }
                    }
                    return unique to columns
                }
                val indexesNeedRepair = expectedIndexes.any { expectedIndex ->
                    readIndexDefinition(expectedIndex) != (expectedIndex.unique to expectedIndex.columns)
                }
                val columnsNeedRepair = allTables.any { table ->
                    val tableExists = exists(
                        "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = '${table.tableName}' LIMIT 1",
                    )
                    if (!tableExists) {
                        true
                    } else {
                        val actualColumns = statement.executeQuery(
                            "PRAGMA table_info('${table.tableName}')",
                        ).use { resultSet ->
                            generateSequence {
                                if (resultSet.next()) resultSet.getString("name") else null
                            }.toSet()
                        }
                        table.columns.any { column -> column.name !in actualColumns }
                    }
                }
                if (version < CURRENT_SCHEMA_VERSION || !orderIntentTableExists ||
                    !localTradeIdColumnExists || indexesNeedRepair || columnsNeedRepair
                ) {
                    true
                } else {
                    val needsProvenanceRewrite = exists(
                        """
                        SELECT 1 FROM trades
                        WHERE source IS NULL
                          AND success = 1
                          AND dry_run = 0
                          AND error_message IS NULL
                          AND slippage_percent IS NULL
                        LIMIT 1
                        """.trimIndent(),
                    )
                    val needsLegacyImport = exists(
                        """
                        SELECT 1
                        FROM trades t
                        WHERE t.dry_run = 0
                          AND t.submission_state IN ('PENDING', 'UNCERTAIN')
                          AND NOT EXISTS (
                              SELECT 1
                              FROM order_intents i
                              WHERE i.local_trade_id = t.id
                          )
                        LIMIT 1
                        """.trimIndent(),
                    )
                    val needsLegacyIdentityBackfill = exists(
                        """
                        SELECT 1
                        FROM order_intents i
                        JOIN trades t ON (
                            (i.client_order_id IS NOT NULL AND i.client_order_id = t.client_order_id)
                            OR i.client_order_id IS NULL AND t.client_order_id IS NULL
                        )
                        WHERE i.local_trade_id IS NULL
                          AND i.created_at = t.timestamp
                          AND i.pair = t.pair
                          AND i.symbol = t.symbol
                          AND i.side = t.side
                          AND i.volume = t.volume
                          AND i.usd_amount = t.usd_amount
                          AND t.dry_run = 0
                          AND (t.source = 'LOCAL_ESTIMATE' OR t.source IS NULL)
                        LIMIT 1
                        """.trimIndent(),
                    )
                    val needsTerminalReconciliation = exists(
                        """
                        SELECT 1
                        FROM order_intents i
                        JOIN trades t ON t.id = i.local_trade_id
                        WHERE i.state IN ('CONFIRMED', 'REJECTED')
                          AND t.submission_state IN ('PENDING', 'UNCERTAIN')
                        LIMIT 1
                        """.trimIndent(),
                    )
                    val needsPendingRecovery = exists(
                        "SELECT 1 FROM order_intents WHERE state = 'PENDING' LIMIT 1",
                    )
                    needsProvenanceRewrite || needsLegacyImport || needsLegacyIdentityBackfill ||
                        needsTerminalReconciliation || needsPendingRecovery
                }
            }
        }
    } catch (_: Exception) {
        // Missing schema_migrations on an existing database is the first migration boundary.
        true
    }

    /**
     * Builds an SQLite JDBC URL.
     *
     * - `:memory:` generates a shared in-memory database and enables foreign keys.
     * - Strings already starting with `jdbc:sqlite:` are returned unchanged, as they are treated
     *   as fully formed URLs provided by the caller.
     * - Partial SQLite URLs/file paths get the `foreign_keys=true` parameter appended.
     */
    private fun buildSqliteUrl(dbPath: String): String = when {
        dbPath == ":memory:" -> {
            val dbName = UUID.randomUUID().toString()
            "jdbc:sqlite:file:$dbName?mode=memory&cache=shared&foreign_keys=true"
        }

        dbPath.startsWith("jdbc:sqlite:") -> dbPath

        dbPath.contains("?") -> "jdbc:sqlite:$dbPath&foreign_keys=true"

        else -> "jdbc:sqlite:$dbPath?foreign_keys=true"
    }

    private fun maintainMemoryDatabase(url: String) {
        if (url.isInMemoryShared) {
            keepAliveConnections.getOrPut(url) { DriverManager.getConnection(url) }
        }
    }

    private val String.isInMemoryShared: Boolean
        get() = contains("mode=memory") && contains("cache=shared")
}
