package com.gemini.krakenbot.repository.impl

import com.gemini.krakenbot.model.OrderIntent
import com.gemini.krakenbot.model.OrderIntentReconciliationException
import com.gemini.krakenbot.model.OrderIntentState
import com.gemini.krakenbot.model.TradeSource
import com.gemini.krakenbot.repository.OrderIntentRepository
import com.gemini.krakenbot.repository.table.OrderIntentTable
import com.gemini.krakenbot.repository.table.TradeTable
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
            check(
                currentState == OrderIntentState.CONFIRMED.name ||
                    currentState == OrderIntentState.REJECTED.name,
            ) {
                "Expected to update one pending order intent, but updated $updatedRows for intent $id"
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
        if (state == OrderIntentState.CONFIRMED && hasSettledApiFill(intent, effectiveOrderTxid)) {
            // A verified Kraken order may sync before an operator resolves its recovered intent.
            // Keep the placeholder because the order-intent journal is its audit owner. The
            // normal update below marks it resolved; later conservative dedupe may remove it
            // only when an authoritative fill identity proves it is a duplicate.
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

    private fun hasSettledApiFill(intent: OrderIntent, orderTxid: String?): Boolean {
        if (orderTxid == null) return false
        val settledFillIdentity =
            (TradeTable.pair eq intent.pair) and
                (TradeTable.symbol eq intent.symbol) and
                (TradeTable.side eq intent.side) and
                (TradeTable.success eq true) and
                (TradeTable.dryRun eq false) and
                (TradeTable.tradeSource eq TradeSource.API_FILL.name)
        val exactOrderMatch = TradeTable
            .select(TradeTable.id)
            .where { settledFillIdentity and (TradeTable.orderTxid eq orderTxid) }
            .any()
        if (exactOrderMatch) return true

        val usdTolerance = intent.usdAmount.abs().multiply(LEGACY_API_FILL_RELATIVE_TOLERANCE)
        val expectedPriceIdentity = intent.expectedPrice?.let { expectedPrice ->
            val priceTolerance = expectedPrice.abs().multiply(LEGACY_API_FILL_RELATIVE_TOLERANCE)
            (TradeTable.price greaterEq expectedPrice.subtract(priceTolerance)) and
                (TradeTable.price lessEq expectedPrice.add(priceTolerance))
        }
        val unkeyedBaseCondition =
            settledFillIdentity and
                TradeTable.orderTxid.isNull() and
                TradeTable.tradeId.isNull() and
                (TradeTable.volume eq intent.volume) and
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
        val unkeyedCondition = expectedPriceIdentity?.let { unkeyedBaseCondition and it } ?: unkeyedBaseCondition
        val unkeyedCandidates = TradeTable
            .select(TradeTable.id)
            .where { unkeyedCondition }
            .limit(2)
            .map { it[TradeTable.id] }
        if (unkeyedCandidates.size > 1) {
            throw OrderIntentReconciliationException(
                "Cannot reconcile order intent ${intent.id}: multiple unkeyed settled API fills match.",
            )
        }
        return unkeyedCandidates.size == 1
    }

    private fun unresolvedStates(): List<String> = listOf(
        OrderIntentState.PENDING.name,
        OrderIntentState.UNCERTAIN.name,
    )
}
