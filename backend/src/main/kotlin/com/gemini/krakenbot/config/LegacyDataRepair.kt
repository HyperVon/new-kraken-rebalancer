package com.gemini.krakenbot.config

import com.gemini.krakenbot.model.Asset
import com.gemini.krakenbot.model.OrderIntentState
import com.gemini.krakenbot.model.OrderSide
import com.gemini.krakenbot.model.OrderSubmissionState
import com.gemini.krakenbot.model.TradeRecord
import com.gemini.krakenbot.model.TradeSource
import com.gemini.krakenbot.repository.table.OrderIntentTable
import com.gemini.krakenbot.repository.table.TradeTable
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import org.slf4j.LoggerFactory
import java.math.BigDecimal

private val log = LoggerFactory.getLogger("com.gemini.krakenbot.config.LegacyDataRepair")

private const val AMBIGUOUS_LEGACY_CLIENT_ORDER_ID_PREFIX = "Ambiguous legacy client_order_id '"
private const val TERMINAL_API_FILL_MATCH_WINDOW_MILLIS = 10_000L
private val TERMINAL_API_FILL_RELATIVE_TOLERANCE = BigDecimal("0.01")

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
    val id: Int,
    val localTradeId: Int,
    val clientOrderId: String?,
    val clientOrderIdAmbiguous: Boolean,
    val createdAt: Long,
    val pair: String,
    val symbol: String,
    val side: String,
    val volume: BigDecimal,
    val usdAmount: BigDecimal,
    val expectedPrice: BigDecimal?,
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
                id = row[OrderIntentTable.id],
                localTradeId = localTradeId,
                clientOrderId = row[OrderIntentTable.clientOrderId],
                clientOrderIdAmbiguous = row[OrderIntentTable.clientOrderIdAmbiguous],
                createdAt = row[OrderIntentTable.createdAt],
                pair = row[OrderIntentTable.pair],
                symbol = row[OrderIntentTable.symbol],
                side = row[OrderIntentTable.side],
                volume = row[OrderIntentTable.volume],
                usdAmount = row[OrderIntentTable.usdAmount],
                expectedPrice = row[OrderIntentTable.expectedPrice],
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
            val currentTrade = TradeTable
                .selectAll()
                .where { TradeTable.id eq resolvedIntent.localTradeId }
                .firstOrNull()
                ?.let(TradeTable::toModel)
            if (
                resolvedIntent.state == OrderIntentState.CONFIRMED &&
                currentTrade != null &&
                currentTrade.isTerminalPlaceholderFor(resolvedIntent)
            ) {
                val settledApiFillId = findTerminalSettledApiFillId(resolvedIntent)
                if (settledApiFillId != null) {
                    check(settledApiFillId != resolvedIntent.localTradeId) {
                        "Cannot reconcile terminal order intent ${resolvedIntent.id}: local trade is also the API fill."
                    }
                    // The API fill is the canonical economic record. Detach before deleting the
                    // superseded local row so the order-intent FK remains valid; the terminal
                    // intent itself remains as the durable audit record.
                    detachTerminalIntent(resolvedIntent.id, resolvedIntent.localTradeId)
                    check(TradeTable.deleteWhere { TradeTable.id eq resolvedIntent.localTradeId } == 1) {
                        "Cannot remove reconciled local trade ${resolvedIntent.localTradeId}."
                    }
                    return@forEach
                }
            }
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
                if (currentTrade == null) {
                    log.info(
                        "Terminal order intent references local trade {} which is no longer in trades table (pruned or deduplicated).",
                        resolvedIntent.localTradeId,
                    )
                    detachTerminalIntent(resolvedIntent.id, resolvedIntent.localTradeId)
                    return@forEach
                }
                val currentSubmissionState = currentTrade.submissionState?.name
                    ?: run {
                        // The local trade row was already reconciled (submission_state cleared or
                        // updated by sync). The intent remains the audit record; it must not pin
                        // this trade forever through its historical FK link.
                        detachTerminalIntent(resolvedIntent.id, resolvedIntent.localTradeId)
                        return@forEach
                    }
                val clientOrderMatches = when {
                    resolvedIntent.clientOrderIdAmbiguous -> true

                    resolvedIntent.clientOrderId != null ->
                        currentTrade.clientOrderId == resolvedIntent.clientOrderId

                    else -> currentTrade.clientOrderId == null
                }
                val immutableIdentityMatches = clientOrderMatches &&
                    currentTrade.timestamp.toEpochMilli() == resolvedIntent.createdAt &&
                    currentTrade.pair == resolvedIntent.pair &&
                    currentTrade.symbol == resolvedIntent.symbol &&
                    currentTrade.side == resolvedIntent.side &&
                    currentTrade.volume.compareTo(resolvedIntent.volume) == 0 &&
                    currentTrade.usdAmount.compareTo(resolvedIntent.usdAmount) == 0 &&
                    !currentTrade.dryRun &&
                    currentTrade.source in listOf(null, TradeSource.LOCAL_ESTIMATE, TradeSource.LEGACY_UNKNOWN)
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
            } else {
                detachTerminalIntent(resolvedIntent.id, resolvedIntent.localTradeId)
            }
        }
}

private fun TradeRecord.isTerminalPlaceholderFor(intent: TerminalIntent): Boolean {
    val clientOrderMatches = when {
        intent.clientOrderIdAmbiguous -> true
        intent.clientOrderId != null -> clientOrderId == intent.clientOrderId
        else -> clientOrderId == null
    }
    return clientOrderMatches &&
        timestamp.toEpochMilli() == intent.createdAt &&
        pair.equals(intent.pair, ignoreCase = true) &&
        symbol.equals(intent.symbol, ignoreCase = true) &&
        OrderSide.normalize(side) == OrderSide.normalize(intent.side) &&
        volume.compareTo(intent.volume) == 0 &&
        usdAmount.compareTo(intent.usdAmount) == 0 &&
        !dryRun &&
        source in setOf(null, TradeSource.LOCAL_ESTIMATE, TradeSource.LEGACY_UNKNOWN)
}

private fun JdbcTransaction.findTerminalSettledApiFillId(intent: TerminalIntent): Int? {
    val normalizedOrderTxid = intent.orderTxid?.takeIf(String::isNotBlank)
    val settledFillIdentity =
        (TradeTable.symbol eq intent.symbol) and
            (TradeTable.side eq OrderSide.normalize(intent.side)) and
            (TradeTable.success eq true) and
            (TradeTable.dryRun eq false) and
            (TradeTable.tradeSource eq TradeSource.API_FILL.name)
    if (normalizedOrderTxid != null) {
        val exactOrderRows = TradeTable
            .selectAll()
            .where { settledFillIdentity and (TradeTable.orderTxid eq normalizedOrderTxid) }
            .toList()
        val pairCompatibleRows = exactOrderRows.filter { row ->
            terminalPairMatches(intent, TradeTable.toModel(row))
        }
        if (pairCompatibleRows.size > 1) {
            throw IllegalStateException(
                "Cannot reconcile terminal order intent ${intent.id}: order $normalizedOrderTxid has " +
                    "multiple settled API fills for the intended instrument.",
            )
        }
        if (pairCompatibleRows.size == 1) {
            val apiFill = TradeTable.toModel(pairCompatibleRows.single())
            check(terminalApiFillMatches(intent, apiFill)) {
                "Cannot reconcile terminal order intent ${intent.id}: order $normalizedOrderTxid has " +
                    "a fill that does not match the intended economics."
            }
            return apiFill.id
        }
        // Rows for another instrument are not evidence for this intent. Continue to the strictly
        // economic fallback rather than treating malformed historical data as a matching fill.
    }

    val usdTolerance = intent.usdAmount.abs().multiply(TERMINAL_API_FILL_RELATIVE_TOLERANCE)
    val candidateRows = TradeTable
        .selectAll()
        .where {
            settledFillIdentity and
                (TradeTable.timestamp greaterEq intent.createdAt - TERMINAL_API_FILL_MATCH_WINDOW_MILLIS) and
                (TradeTable.timestamp lessEq intent.createdAt + TERMINAL_API_FILL_MATCH_WINDOW_MILLIS) and
                (TradeTable.usdAmount greaterEq intent.usdAmount.subtract(usdTolerance)) and
                (TradeTable.usdAmount lessEq intent.usdAmount.add(usdTolerance)) and
                if (normalizedOrderTxid == null) {
                    TradeTable.id eq TradeTable.id
                } else {
                    TradeTable.orderTxid.isNull() and TradeTable.tradeId.isNull()
                }
        }
        .toList()
        .filter { row -> terminalApiFillMatches(intent, TradeTable.toModel(row)) }
        .take(2)
    check(candidateRows.size <= 1) {
        "Cannot reconcile terminal order intent ${intent.id}: multiple settled API fills match."
    }
    return candidateRows.singleOrNull()?.let { TradeTable.toModel(it).id }
}

private fun terminalPairMatches(intent: TerminalIntent, apiFill: TradeRecord): Boolean =
    apiFill.pair.equals(intent.pair, ignoreCase = true) ||
        (
            Asset.matchesUsdQuotedPair(intent.pair, intent.symbol) &&
                Asset.matchesUsdQuotedPair(apiFill.pair, intent.symbol)
            )

private fun terminalApiFillMatches(intent: TerminalIntent, apiFill: TradeRecord): Boolean {
    if (!terminalPairMatches(intent, apiFill)) return false
    if (!apiFill.side.equals(intent.side, ignoreCase = true)) return false
    if (!apiFill.symbol.equals(intent.symbol, ignoreCase = true)) return false
    if (kotlin.math.abs(apiFill.timestamp.toEpochMilli() - intent.createdAt) > TERMINAL_API_FILL_MATCH_WINDOW_MILLIS) {
        return false
    }
    if (apiFill.volume.compareTo(intent.volume) != 0) return false
    val usdTolerance = intent.usdAmount.abs().multiply(TERMINAL_API_FILL_RELATIVE_TOLERANCE)
    if (apiFill.usdAmount.subtract(intent.usdAmount).abs() > usdTolerance) return false
    val expectedPrice = intent.expectedPrice ?: return true
    val priceTolerance = expectedPrice.abs().multiply(TERMINAL_API_FILL_RELATIVE_TOLERANCE)
    return apiFill.price >= expectedPrice.subtract(priceTolerance) &&
        apiFill.price <= expectedPrice.add(priceTolerance)
}

private fun JdbcTransaction.detachTerminalIntent(intentId: Int, tradeId: Int) {
    val updatedRows = OrderIntentTable.update({
        (OrderIntentTable.id eq intentId) and
            (OrderIntentTable.localTradeId eq tradeId)
    }) {
        it[OrderIntentTable.localTradeId] = null
    }
    check(updatedRows == 1) {
        "Cannot detach local trade $tradeId from terminal order intent $intentId."
    }
    check(
        !OrderIntentTable
            .select(OrderIntentTable.id)
            .where { OrderIntentTable.localTradeId eq tradeId }
            .limit(1)
            .any(),
    ) {
        "Cannot detach shared local trade $tradeId from terminal order intent $intentId."
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
    val unresolvedIntentStates = listOf(
        OrderIntentState.PENDING.name,
        OrderIntentState.UNCERTAIN.name,
    )
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
                        (OrderIntentTable.state inList unresolvedIntentStates) and
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
                    (OrderIntentTable.state inList unresolvedIntentStates) and
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
