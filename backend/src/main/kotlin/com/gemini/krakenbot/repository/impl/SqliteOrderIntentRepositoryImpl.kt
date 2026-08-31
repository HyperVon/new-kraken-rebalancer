package com.gemini.krakenbot.repository.impl

import com.gemini.krakenbot.model.Asset
import com.gemini.krakenbot.model.OrderIntent
import com.gemini.krakenbot.model.OrderIntentReconciliationException
import com.gemini.krakenbot.model.OrderIntentState
import com.gemini.krakenbot.model.OrderSide
import com.gemini.krakenbot.model.TradeSource
import com.gemini.krakenbot.repository.OrderIntentRepository
import com.gemini.krakenbot.repository.table.OrderIntentTable
import com.gemini.krakenbot.repository.table.TradeTable
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.count
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import org.slf4j.LoggerFactory
import java.math.BigDecimal
import java.time.Instant

class SqliteOrderIntentRepositoryImpl(private val database: Database) : OrderIntentRepository {
    private companion object {
        const val LEGACY_API_FILL_MATCH_WINDOW_MILLIS = 10_000L
        val LEGACY_API_FILL_RELATIVE_TOLERANCE = BigDecimal("0.01")
    }

    private val log = LoggerFactory.getLogger(SqliteOrderIntentRepositoryImpl::class.java)

    override suspend fun savePending(intent: OrderIntent): Int =
        database.safeTransactionIO(log, "Failed to save order intent") {
            OrderIntentTable.insert {
                OrderIntentTable.applyPending(it, intent)
            }[OrderIntentTable.id]
        }

    override suspend fun recordOutcome(
        id: Int,
        state: OrderIntentState,
        orderTxid: String?,
        errorMessage: String?,
        resolvedAt: Instant?,
    ): Boolean = database.safeTransactionIO(log, "Failed to record order intent outcome") {
        val intent = OrderIntentTable
            .selectAll()
            .where {
                (OrderIntentTable.id eq id) and
                    (OrderIntentTable.state eq OrderIntentState.PENDING.name)
            }
            .firstOrNull()
            ?.let(OrderIntentTable::toModel)
        val updatedRows = OrderIntentTable.update({
            (OrderIntentTable.id eq id) and
                (OrderIntentTable.state eq OrderIntentState.PENDING.name)
        }) {
            it[OrderIntentTable.state] = state.name
            it[OrderIntentTable.orderTxid] = orderTxid
            it[OrderIntentTable.errorMessage] = errorMessage
            it[OrderIntentTable.resolvedAt] = resolvedAt?.toEpochMilli()
        }
        if (updatedRows != 1) {
            val currentState = OrderIntentTable
                .select(OrderIntentTable.state)
                .where { OrderIntentTable.id eq id }
                .firstOrNull()
                ?.get(OrderIntentTable.state)
            if (currentState != OrderIntentState.CONFIRMED.name &&
                currentState != OrderIntentState.REJECTED.name
            ) {
                throw OrderIntentReconciliationException(
                    "Expected to update one pending order intent, but updated $updatedRows for intent $id",
                )
            }
            // An operator resolution won the race. Preserve its evidence and terminal outcome.
        }
        if (updatedRows == 1) {
            intent?.let { updateLocalTrade(it, state, orderTxid, errorMessage) }
        }
        updatedRows == 1
    }

    override suspend fun hasUnresolvedIntents(): Boolean = database.readTransactionIO {
        OrderIntentTable
            .selectAll()
            .where { OrderIntentTable.state inList unresolvedStates() }
            .any()
    }

    override suspend fun countUnresolvedIntents(): Long = database.readTransactionIO {
        val countColumn = OrderIntentTable.id.count()
        OrderIntentTable
            .select(countColumn)
            .where { OrderIntentTable.state inList unresolvedStates() }
            .firstOrNull()
            ?.get(countColumn) ?: 0L
    }

    override suspend fun loadUnresolvedIntents(): List<OrderIntent> = database.readTransactionIO {
        OrderIntentTable
            .selectAll()
            .where { OrderIntentTable.state inList unresolvedStates() }
            .orderBy(OrderIntentTable.createdAt, SortOrder.ASC)
            .map(OrderIntentTable::toModel)
    }

    override suspend fun resolve(
        id: Int,
        state: OrderIntentState,
        evidence: String,
        resolvedAt: Instant,
        orderTxid: String?,
    ): Boolean = database.safeTransactionIO(log, "Failed to resolve order intent") {
        val intent = OrderIntentTable
            .selectAll()
            .where {
                (OrderIntentTable.id eq id) and
                    (OrderIntentTable.state eq OrderIntentState.UNCERTAIN.name)
            }
            .firstOrNull()
            ?.let(OrderIntentTable::toModel)
        val resolved = OrderIntentTable.update({
            (OrderIntentTable.id eq id) and
                (OrderIntentTable.state eq OrderIntentState.UNCERTAIN.name)
        }) {
            it[OrderIntentTable.state] = state.name
            it[OrderIntentTable.resolutionEvidence] = evidence
            it[OrderIntentTable.resolvedAt] = resolvedAt.toEpochMilli()
            it[OrderIntentTable.orderTxid] = orderTxid ?: intent?.orderTxid
        } == 1
        if (resolved) {
            intent?.let { updateLocalTrade(it, state, orderTxid ?: it.orderTxid, evidence) }
        }
        resolved
    }

    private fun updateLocalTrade(
        intent: OrderIntent,
        state: OrderIntentState,
        orderTxid: String?,
        errorMessage: String?,
    ) {
        val effectiveOrderTxid = orderTxid ?: intent.orderTxid
        val clientOrderIdentity = when {
            intent.clientOrderId != null -> TradeTable.clientOrderId eq intent.clientOrderId
            intent.clientOrderIdAmbiguous -> null
            else -> TradeTable.clientOrderId.isNull()
        }
        val localTradeAttributes =
            (TradeTable.timestamp eq intent.createdAt.toEpochMilli()) and
                (TradeTable.pair eq intent.pair) and
                (TradeTable.symbol eq intent.symbol) and
                (TradeTable.side eq intent.side) and
                (TradeTable.volume eq intent.volume) and
                (TradeTable.usdAmount eq intent.usdAmount) and
                (TradeTable.dryRun eq false) and
                ((TradeTable.tradeSource eq TradeSource.LOCAL_ESTIMATE.name) or TradeTable.tradeSource.isNull()) and
                (TradeTable.success eq false)
        val candidateBaseCondition = clientOrderIdentity?.let { it and localTradeAttributes } ?: localTradeAttributes
        val strictCandidateIdentity = candidateBaseCondition and
            (
                TradeTable.submissionState inList listOf(
                    OrderIntentState.PENDING.name,
                    OrderIntentState.UNCERTAIN.name,
                )
                )
        val localTradeId = intent.localTradeId ?: run {
            val candidateIds = TradeTable
                .select(TradeTable.id)
                .where { strictCandidateIdentity }
                .orderBy(TradeTable.id, SortOrder.ASC)
                .limit(2)
                .map { it[TradeTable.id] }
            if (candidateIds.size > 1) {
                throw OrderIntentReconciliationException(
                    "Cannot reconcile order intent ${intent.id}: multiple local trade candidates exist.",
                )
            }
            candidateIds.singleOrNull()
        }
        if (localTradeId == null) {
            throw OrderIntentReconciliationException(
                "Cannot reconcile order intent ${intent.id}: no matching local trade exists.",
            )
        }
        // localTradeId is the durable journal link. Verify the immutable attributes before mutating
        // it, but target writes by its primary key so SQLite's decimal representation cannot make a
        // valid journal link appear to be missing.
        verifyLinkedLocalTrade(intent, localTradeId)
        val linkedTradeIdentity = TradeTable.id eq localTradeId
        if (state == OrderIntentState.CONFIRMED) {
            val settledApiFillId = findSettledApiFillId(intent, effectiveOrderTxid)
            if (settledApiFillId != null) {
                // The API fill is the canonical economic record. Detach before deleting the
                // local placeholder so the FK remains valid, and keep the durable intent as
                // the audit record for the operator's resolution.
                detachLocalTrade(intent.id, intent.localTradeId, localTradeId)
                check(TradeTable.deleteWhere { TradeTable.id eq localTradeId } == 1) {
                    "Cannot remove reconciled local trade $localTradeId for order intent ${intent.id}."
                }
                return
            }
        }
        val updatedRows = TradeTable.update({
            linkedTradeIdentity
        }) {
            it[TradeTable.success] = state == OrderIntentState.CONFIRMED
            it[TradeTable.errorMessage] = if (state == OrderIntentState.CONFIRMED) null else errorMessage
            it[TradeTable.orderTxid] = effectiveOrderTxid
            it[TradeTable.submissionState] = when (state) {
                OrderIntentState.UNCERTAIN -> OrderIntentState.UNCERTAIN.name

                OrderIntentState.PENDING -> OrderIntentState.PENDING.name

                OrderIntentState.CONFIRMED,
                OrderIntentState.REJECTED,
                -> null
            }
        }
        if (updatedRows != 1) {
            throw OrderIntentReconciliationException(
                "Cannot reconcile order intent ${intent.id}: linked local trade $localTradeId is missing.",
            )
        }
        if (state == OrderIntentState.CONFIRMED || state == OrderIntentState.REJECTED) {
            detachLocalTrade(intent.id, intent.localTradeId, localTradeId)
        }
    }

    private fun detachLocalTrade(intentId: Int?, linkedTradeId: Int?, tradeId: Int) {
        val updatedRows = if (linkedTradeId == null) {
            0
        } else {
            check(linkedTradeId == tradeId) {
                "Cannot detach unexpected local trade $linkedTradeId from order intent $intentId."
            }
            OrderIntentTable.update({
                (OrderIntentTable.id eq requireNotNull(intentId)) and
                    (OrderIntentTable.localTradeId eq tradeId)
            }) {
                it[OrderIntentTable.localTradeId] = null
            }
        }
        if (linkedTradeId != null) {
            check(updatedRows == 1) {
                "Cannot detach local trade $tradeId from order intent $intentId."
            }
        }

        // A terminal intent may not delete a trade still owned by another intent. Keep the
        // whole transaction rolled back so an operator never loses unresolved evidence.
        check(
            !OrderIntentTable
                .select(OrderIntentTable.id)
                .where { OrderIntentTable.localTradeId eq tradeId }
                .limit(1)
                .any(),
        ) {
            "Cannot detach shared local trade $tradeId from order intent $intentId."
        }
    }

    private fun verifyLinkedLocalTrade(intent: OrderIntent, localTradeId: Int) {
        val localTrade = TradeTable
            .selectAll()
            .where { TradeTable.id eq localTradeId }
            .singleOrNull()
            ?: throw OrderIntentReconciliationException(
                "Cannot reconcile order intent ${intent.id}: linked local trade $localTradeId is missing.",
            )
        val source = localTrade[TradeTable.tradeSource]
        val matchesIntent =
            listOf(
                localTrade[TradeTable.timestamp] == intent.createdAt.toEpochMilli(),
                localTrade[TradeTable.pair] == intent.pair,
                localTrade[TradeTable.symbol] == intent.symbol,
                localTrade[TradeTable.side].equals(intent.side, ignoreCase = true),
                localTrade[TradeTable.volume].compareTo(intent.volume) == 0,
                localTrade[TradeTable.usdAmount].compareTo(intent.usdAmount) == 0,
                !localTrade[TradeTable.dryRun],
                source in setOf(TradeSource.LOCAL_ESTIMATE.name, null),
                !localTrade[TradeTable.success],
            ).all { it }
        if (!matchesIntent) {
            throw OrderIntentReconciliationException(
                "Cannot reconcile order intent ${intent.id}: linked local trade $localTradeId does not match the intent.",
            )
        }
    }

    private fun findSettledApiFillId(intent: OrderIntent, orderTxid: String?): Int? {
        val normalizedOrderTxid = orderTxid?.takeIf(String::isNotBlank)
        val settledFillIdentity =
            (TradeTable.symbol eq intent.symbol) and
                (TradeTable.side eq OrderSide.normalize(intent.side)) and
                (TradeTable.success eq true) and
                (TradeTable.dryRun eq false) and
                (TradeTable.tradeSource eq TradeSource.API_FILL.name)

        // A shared order id is not enough: one Kraken order can have multiple fill legs. Only
        // delete a local placeholder when exactly one same-order row also matches its immutable
        // pair identity and economics. If same-order candidates exist but none match, fail closed
        // rather than turning a partial-fill set into one aggregate trade.
        if (normalizedOrderTxid != null) {
            val exactOrderRows = TradeTable
                .selectAll()
                .where { settledFillIdentity and (TradeTable.orderTxid eq normalizedOrderTxid) }
                .toList()
            val pairCompatibleOrderRows = exactOrderRows.filter { row -> pairMatchesIntent(intent, row) }
            if (pairCompatibleOrderRows.isNotEmpty()) {
                if (pairCompatibleOrderRows.size > 1) {
                    throw OrderIntentReconciliationException(
                        "Cannot reconcile order intent ${intent.id}: order $normalizedOrderTxid has " +
                            "multiple settled API fills for the intended instrument.",
                    )
                }
                val matchingRow = pairCompatibleOrderRows.single()
                if (settledFillMatchesIntent(intent, matchingRow)) return matchingRow[TradeTable.id]
                throw OrderIntentReconciliationException(
                    "Cannot reconcile order intent ${intent.id}: order $normalizedOrderTxid has " +
                        "a fill that does not match the intended economics.",
                )
            }
            // Rows for another instrument are not evidence for this intent. They may be malformed
            // historical data, so continue to the strictly economic fallback below rather than
            // treating an unrelated row as a settled fill for this local placeholder.
        }

        val usdTolerance = intent.usdAmount.abs().multiply(LEGACY_API_FILL_RELATIVE_TOLERANCE)
        val matchingBaseCondition =
            settledFillIdentity and
                (
                    TradeTable.timestamp greaterEq
                        intent.createdAt.toEpochMilli() - LEGACY_API_FILL_MATCH_WINDOW_MILLIS
                    ) and
                (
                    TradeTable.timestamp lessEq
                        intent.createdAt.toEpochMilli() + LEGACY_API_FILL_MATCH_WINDOW_MILLIS
                    ) and
                (TradeTable.usdAmount greaterEq intent.usdAmount.subtract(usdTolerance)) and
                (TradeTable.usdAmount lessEq intent.usdAmount.add(usdTolerance))
        val candidateRows = TradeTable
            .selectAll()
            .where {
                matchingBaseCondition and if (normalizedOrderTxid == null) {
                    // The operator may omit the order id; a unique full economic match is still
                    // safe to use, including when the synchronized API row has its own id.
                    TradeTable.id eq TradeTable.id
                } else {
                    // With a supplied order id, only an unkeyed legacy fill can be a safe
                    // fallback; a keyed row with a different order id is contradictory evidence.
                    TradeTable.orderTxid.isNull() and TradeTable.tradeId.isNull()
                }
            }
            .toList()
            .filter { row -> pairMatchesIntent(intent, row) && settledFillMatchesIntent(intent, row) }
            .take(2)
        if (candidateRows.size > 1) {
            throw OrderIntentReconciliationException(
                "Cannot reconcile order intent ${intent.id}: multiple settled API fills match.",
            )
        }
        return candidateRows.singleOrNull()?.get(TradeTable.id)
    }

    private fun pairMatchesIntent(intent: OrderIntent, row: ResultRow): Boolean {
        val apiPair = row[TradeTable.pair]
        return apiPair.equals(intent.pair, ignoreCase = true) ||
            (
                Asset.matchesUsdQuotedPair(intent.pair, intent.symbol) &&
                    Asset.matchesUsdQuotedPair(apiPair, intent.symbol)
                )
    }

    private fun settledFillMatchesIntent(intent: OrderIntent, row: ResultRow): Boolean {
        val timestampDifference = kotlin.math.abs(
            row[TradeTable.timestamp] - intent.createdAt.toEpochMilli(),
        )
        if (timestampDifference > LEGACY_API_FILL_MATCH_WINDOW_MILLIS) return false
        if (row[TradeTable.volume].compareTo(intent.volume) != 0) return false

        val usdTolerance = intent.usdAmount.abs().multiply(LEGACY_API_FILL_RELATIVE_TOLERANCE)
        if (row[TradeTable.usdAmount].subtract(intent.usdAmount).abs() > usdTolerance) return false

        val expectedPrice = intent.expectedPrice ?: return true
        val priceTolerance = expectedPrice.abs().multiply(LEGACY_API_FILL_RELATIVE_TOLERANCE)
        return row[TradeTable.price] >= expectedPrice.subtract(priceTolerance) &&
            row[TradeTable.price] <= expectedPrice.add(priceTolerance)
    }

    private fun unresolvedStates(): List<String> = listOf(
        OrderIntentState.PENDING.name,
        OrderIntentState.UNCERTAIN.name,
    )
}
