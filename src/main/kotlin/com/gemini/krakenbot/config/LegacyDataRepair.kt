package com.gemini.krakenbot.config

import com.gemini.krakenbot.model.OrderIntentState
import com.gemini.krakenbot.model.OrderSubmissionState
import com.gemini.krakenbot.model.TradeSource
import com.gemini.krakenbot.repository.table.OrderIntentTable
import com.gemini.krakenbot.repository.table.TradeTable
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import org.slf4j.LoggerFactory
import java.math.BigDecimal

private val log = LoggerFactory.getLogger("com.gemini.krakenbot.config.LegacyDataRepair")

private const val AMBIGUOUS_LEGACY_CLIENT_ORDER_ID_PREFIX = "Ambiguous legacy client_order_id '"

internal fun JdbcTransaction.markLegacyUnknownTradeProvenance() {
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
}

internal fun JdbcTransaction.markLegacyAmbiguousClientOrderIds() {
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

internal fun JdbcTransaction.backfillLegacyTradeIds() {
    val assignedTradeIds = OrderIntentTable
        .select(OrderIntentTable.id, OrderIntentTable.localTradeId)
        .where { OrderIntentTable.localTradeId.isNotNull() }
        .map { row ->
            checkNotNull(row.getOrNull(OrderIntentTable.localTradeId)) {
                "Legacy order intent ${row[OrderIntentTable.id]} has a null local_trade_id despite the non-null query."
            }
        }
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

private data class LegacyCandidate(val exactKey: String, val relaxedKey: String, val id: Int)

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

internal fun JdbcTransaction.reconcileTerminalOrderIntents() {
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
            val localTradeId = checkNotNull(row.getOrNull(OrderIntentTable.localTradeId)) {
                "Terminal order intent ${row[OrderIntentTable.id]} has a null local_trade_id " +
                    "despite the non-null query."
            }
            TerminalIntent(
                localTradeId = localTradeId,
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
                if (currentTrade == null) {
                    log.info(
                        "Terminal order intent references local trade {} which is no longer in trades table (pruned or deduplicated).",
                        resolvedIntent.localTradeId,
                    )
                    return@forEach
                }
                val currentSubmissionState = currentTrade[TradeTable.submissionState]
                    ?: // The local trade row was already reconciled (submission_state cleared or updated by sync).
                    return@forEach
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
                    currentTrade[TradeTable.tradeSource] in listOf(
                        null,
                        TradeSource.LOCAL_ESTIMATE.name,
                        TradeSource.LEGACY_UNKNOWN.name,
                    )
                check(immutableIdentityMatches) {
                    "Cannot reconcile terminal order intent for local trade " +
                        "${resolvedIntent.localTradeId}: immutable trade identity changed."
                }
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

internal fun JdbcTransaction.recoverPendingOrderIntents() {
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

internal fun JdbcTransaction.reconcileLegacySubmissionGuards() {
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
                it[OrderIntentTable.state] = checkNotNull(row.getOrNull(TradeTable.submissionState)) {
                    "Legacy live trade $tradeId has a null submission_state despite the PENDING/UNCERTAIN query."
                }
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
