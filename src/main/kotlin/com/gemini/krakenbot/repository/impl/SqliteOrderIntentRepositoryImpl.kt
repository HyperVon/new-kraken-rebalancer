package com.gemini.krakenbot.repository.impl

import com.gemini.krakenbot.model.OrderIntent
import com.gemini.krakenbot.model.OrderIntentState
import com.gemini.krakenbot.model.TradeSource
import com.gemini.krakenbot.repository.OrderIntentRepository
import com.gemini.krakenbot.repository.table.OrderIntentTable
import com.gemini.krakenbot.repository.table.TradeTable
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.count
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import org.slf4j.LoggerFactory
import java.time.Instant

class SqliteOrderIntentRepositoryImpl(private val database: Database) : OrderIntentRepository {
    private val log = LoggerFactory.getLogger(SqliteOrderIntentRepositoryImpl::class.java)

    override suspend fun savePending(intent: OrderIntent): Int =
        database.safeTransactionIO(log, "Failed to save order intent") {
            OrderIntentTable.insert {
                it[cycleId] = intent.cycleId
                it[clientOrderId] = intent.clientOrderId
                it[pair] = intent.pair
                it[symbol] = intent.symbol
                it[side] = intent.side
                it[volume] = intent.volume
                it[usdAmount] = intent.usdAmount
                it[expectedPrice] = intent.expectedPrice
                it[createdAt] = intent.createdAt.toEpochMilli()
                it[state] = OrderIntentState.PENDING.name
                it[orderTxid] = null
                it[errorMessage] = null
                it[resolvedAt] = null
                it[resolutionEvidence] = null
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
            ?.let(::buildIntent)
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
            .map(::buildIntent)
    }

    override suspend fun resolve(id: Int, state: OrderIntentState, evidence: String, resolvedAt: Instant): Boolean =
        database.safeTransactionIO(log, "Failed to resolve order intent") {
            val intent = OrderIntentTable
                .selectAll()
                .where {
                    (OrderIntentTable.id eq id) and
                        (OrderIntentTable.state eq OrderIntentState.UNCERTAIN.name)
                }
                .firstOrNull()
                ?.let(::buildIntent)
            val resolved = OrderIntentTable.update({
                (OrderIntentTable.id eq id) and
                    (OrderIntentTable.state eq OrderIntentState.UNCERTAIN.name)
            }) {
                it[OrderIntentTable.state] = state.name
                it[OrderIntentTable.resolutionEvidence] = evidence
                it[OrderIntentTable.resolvedAt] = resolvedAt.toEpochMilli()
            } == 1
            if (resolved) {
                intent?.let { updateLocalTrade(it, state, it.orderTxid, evidence) }
            }
            resolved
        }

    private fun updateLocalTrade(
        intent: OrderIntent,
        state: OrderIntentState,
        orderTxid: String?,
        errorMessage: String?,
    ) {
        val clientOrderId = intent.clientOrderId ?: return
        val effectiveOrderTxid = orderTxid ?: intent.orderTxid
        TradeTable.update({
            (TradeTable.clientOrderId eq clientOrderId) and
                (TradeTable.dryRun eq false) and
                (TradeTable.tradeSource eq TradeSource.LOCAL_ESTIMATE.name)
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
    }

    private fun buildIntent(row: ResultRow): OrderIntent = OrderIntent(
        id = row[OrderIntentTable.id],
        cycleId = row[OrderIntentTable.cycleId],
        clientOrderId = row[OrderIntentTable.clientOrderId],
        pair = row[OrderIntentTable.pair],
        symbol = row[OrderIntentTable.symbol],
        side = row[OrderIntentTable.side],
        volume = row[OrderIntentTable.volume],
        usdAmount = row[OrderIntentTable.usdAmount],
        expectedPrice = row[OrderIntentTable.expectedPrice],
        createdAt = Instant.ofEpochMilli(row[OrderIntentTable.createdAt]),
        state = OrderIntentState.valueOf(row[OrderIntentTable.state]),
        orderTxid = row[OrderIntentTable.orderTxid],
        errorMessage = row[OrderIntentTable.errorMessage],
        resolvedAt = row[OrderIntentTable.resolvedAt]?.let(Instant::ofEpochMilli),
        resolutionEvidence = row[OrderIntentTable.resolutionEvidence],
    )

    private fun unresolvedStates(): List<String> = listOf(
        OrderIntentState.PENDING.name,
        OrderIntentState.UNCERTAIN.name,
    )
}
