package com.gemini.krakenbot.repository.impl

import com.gemini.krakenbot.config.ExecutionDatabase
import com.gemini.krakenbot.model.OrderIntent
import com.gemini.krakenbot.model.OrderIntentReconciliationException
import com.gemini.krakenbot.model.OrderIntentState
import com.gemini.krakenbot.model.RebalancerOrderIdentities
import com.gemini.krakenbot.repository.ExecutionOrderIntentRepository
import com.gemini.krakenbot.repository.PendingTradeProjection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.math.BigDecimal
import java.sql.Connection
import java.sql.ResultSet
import java.time.Instant

class SqliteExecutionOrderIntentRepositoryImpl(private val database: ExecutionDatabase) :
    ExecutionOrderIntentRepository {
    private val log = LoggerFactory.getLogger(SqliteExecutionOrderIntentRepositoryImpl::class.java)

    override suspend fun ensureReadyForSubmission() {
        database.ensureReadyForSubmission()
    }

    override suspend fun savePending(intent: OrderIntent): Int = withContext(Dispatchers.IO) {
        database.ensureReadyForSubmission()
        inTransaction("save pending order intent") { connection ->
            connection.prepareStatement(
                """
                INSERT INTO execution_order_intents (
                    cycle_id, client_order_id, client_order_id_ambiguous, pair, symbol, side,
                    volume, usd_amount, expected_price, created_at, state, order_txid,
                    error_message, resolved_at, resolution_evidence, outcome_volume,
                    legacy_source_id, legacy_intent_id, legacy_trade_id, legacy_source_state
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NULL, NULL, NULL, NULL, NULL, ?, ?, ?, ?)
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, intent.cycleId)
                statement.setString(2, intent.clientOrderId)
                statement.setInt(3, intent.clientOrderIdAmbiguous.toSqlBoolean())
                statement.setString(4, intent.pair)
                statement.setString(5, intent.symbol)
                statement.setString(6, intent.side)
                statement.setString(7, intent.volume.toPlainString())
                statement.setString(8, intent.usdAmount.toPlainString())
                statement.setString(9, intent.expectedPrice?.toPlainString())
                statement.setLong(10, intent.createdAt.toEpochMilli())
                statement.setString(11, OrderIntentState.PENDING.name)
                statement.setString(12, intent.legacySourceId)
                statement.setObject(13, intent.legacyIntentId)
                statement.setObject(14, intent.legacyTradeId ?: intent.localTradeId)
                statement.setString(15, intent.legacySourceState)
                statement.executeUpdate()
            }
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT last_insert_rowid()").use { resultSet ->
                    check(resultSet.next()) { "SQLite did not return the new execution intent ID." }
                    resultSet.getInt(1)
                }
            }
        }
    }

    override suspend fun recordOutcome(
        id: Int,
        state: OrderIntentState,
        orderTxid: String?,
        errorMessage: String?,
        resolvedAt: Instant?,
        outcomeVolume: BigDecimal?,
    ): Boolean = withContext(Dispatchers.IO) {
        database.ensureReadyForSubmission()
        require(state != OrderIntentState.PENDING) { "An order outcome cannot return an intent to PENDING." }
        inTransaction("record order intent outcome") { connection ->
            val updateCount = connection.prepareStatement(
                """
                UPDATE execution_order_intents
                SET state = ?, order_txid = ?, error_message = ?, resolved_at = ?, outcome_volume = ?
                WHERE id = ? AND state = 'PENDING'
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, state.name)
                statement.setString(2, orderTxid)
                statement.setString(3, errorMessage)
                statement.setObject(4, resolvedAt?.toEpochMilli())
                statement.setString(5, outcomeVolume?.toPlainString())
                statement.setInt(6, id)
                statement.executeUpdate()
            }
            if (updateCount != 1) {
                val currentState = connection.prepareStatement(
                    "SELECT state FROM execution_order_intents WHERE id = ?",
                ).use { statement ->
                    statement.setInt(1, id)
                    statement.executeQuery().use { resultSet ->
                        if (resultSet.next()) resultSet.getString(1) else null
                    }
                }
                if (currentState == OrderIntentState.CONFIRMED.name || currentState == OrderIntentState.REJECTED.name) {
                    return@inTransaction false
                }
                throw OrderIntentReconciliationException(
                    "Expected to update one pending execution intent, updated $updateCount for intent $id " +
                        "(current state=$currentState).",
                )
            }
            incrementProjectionRevision(connection, id)
            true
        }
    }

    override suspend fun hasUnresolvedIntents(): Boolean = withContext(Dispatchers.IO) {
        database.ensureReadyForSubmission()
        database.connect().use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(
                    "SELECT 1 FROM execution_order_intents WHERE state IN ('PENDING', 'UNCERTAIN') LIMIT 1",
                ).use(ResultSet::next)
            }
        }
    }

    override suspend fun countUnresolvedIntents(): Long = withContext(Dispatchers.IO) {
        database.ensureReadyForSubmission()
        database.connect().use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(
                    "SELECT COUNT(*) FROM execution_order_intents WHERE state IN ('PENDING', 'UNCERTAIN')",
                ).use { resultSet ->
                    check(resultSet.next()) { "Unresolved execution-intent count could not be read." }
                    resultSet.getLong(1)
                }
            }
        }
    }

    override suspend fun loadUnresolvedIntents(): List<OrderIntent> = withContext(Dispatchers.IO) {
        database.ensureReadyForSubmission()
        database.connect().use { connection ->
            connection.prepareStatement(
                "SELECT * FROM execution_order_intents WHERE state IN ('PENDING', 'UNCERTAIN') ORDER BY created_at, id",
            ).use { statement ->
                statement.executeQuery().use { resultSet ->
                    buildList { while (resultSet.next()) add(resultSet.toOrderIntent()) }
                }
            }
        }
    }

    override suspend fun resolve(
        id: Int,
        state: OrderIntentState,
        evidence: String,
        resolvedAt: Instant,
        orderTxid: String?,
    ): Boolean = withContext(Dispatchers.IO) {
        database.ensureReadyForSubmission()
        require(state == OrderIntentState.CONFIRMED || state == OrderIntentState.REJECTED) {
            "Only terminal outcomes can resolve an execution intent."
        }
        inTransaction("resolve execution order intent") { connection ->
            val updated = connection.prepareStatement(
                """
                UPDATE execution_order_intents
                SET state = ?, order_txid = COALESCE(?, order_txid), error_message = ?,
                    resolved_at = ?, resolution_evidence = ?
                WHERE id = ? AND state = 'UNCERTAIN'
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, state.name)
                statement.setString(2, orderTxid)
                statement.setString(3, evidence)
                statement.setLong(4, resolvedAt.toEpochMilli())
                statement.setString(5, evidence)
                statement.setInt(6, id)
                statement.executeUpdate()
            }
            if (updated == 1) incrementProjectionRevision(connection, id)
            updated == 1
        }
    }

    override suspend fun getKnownRebalancerOrderIdentities(
        orderTxids: Set<String>,
        clientOrderIds: Set<String>,
    ): RebalancerOrderIdentities = withContext(Dispatchers.IO) {
        val txids = orderTxids.mapNotNull { it.nonBlankTrimmed() }.toSet()
        val clientIds = clientOrderIds.mapNotNull { it.nonBlankTrimmed() }.toSet()
        if (txids.isEmpty() && clientIds.isEmpty()) return@withContext RebalancerOrderIdentities()
        database.connect().use { connection ->
            val known = mutableSetOf<String>()
            fun collectKnownOrderIds(column: String, values: Set<String>) {
                values.chunked(SQLITE_IN_CHUNK_SIZE).forEach { chunk ->
                    val placeholders = chunk.placeholders()
                    val sql =
                        "SELECT order_txid FROM execution_order_intents WHERE $column IN ($placeholders)"
                    connection.prepareStatement(sql).use { statement ->
                        chunk.forEachIndexed { index, value -> statement.setString(index + 1, value) }
                        statement.executeQuery().use { resultSet ->
                            while (resultSet.next()) {
                                resultSet.getString(1)?.trim()?.takeIf(String::isNotEmpty)?.let(known::add)
                            }
                        }
                    }
                }
            }
            collectKnownOrderIds("order_txid", txids)
            collectKnownOrderIds("client_order_id", clientIds)
            RebalancerOrderIdentities(orderTxids = known)
        }
    }

    override suspend fun loadProjectionEvents(afterEventId: Long, limit: Int): List<PendingTradeProjection> =
        withContext(Dispatchers.IO) {
            require(limit > 0)
            val journalId = database.journalId()
            database.connect().use { connection ->
                connection.prepareStatement(
                    """
                SELECT i.*, o.event_id, o.revision
                FROM execution_projection_outbox o
                JOIN execution_order_intents i ON i.id = o.intent_id
                WHERE o.event_id > ? AND i.state != 'PENDING'
                ORDER BY o.event_id
                LIMIT ?
                    """.trimIndent(),
                ).use { statement ->
                    statement.setLong(1, afterEventId)
                    statement.setInt(2, limit)
                    statement.executeQuery().use { resultSet ->
                        buildList {
                            while (resultSet.next()) {
                                add(
                                    PendingTradeProjection(
                                        journalId,
                                        resultSet.getLong("event_id"),
                                        resultSet.getLong("revision"),
                                        resultSet.toOrderIntent(),
                                    ),
                                )
                            }
                        }
                    }
                }
            }
        }

    override suspend fun recoverInterruptedSubmissions() = withContext(Dispatchers.IO) {
        inTransaction("recover interrupted execution intents") { connection ->
            val pendingIds = connection.createStatement().use { statement ->
                statement.executeQuery(
                    "SELECT id FROM execution_order_intents WHERE state = 'PENDING'",
                ).use { resultSet ->
                    buildList { while (resultSet.next()) add(resultSet.getInt(1)) }
                }
            }
            pendingIds.forEach { id ->
                connection.prepareStatement(
                    "UPDATE execution_order_intents SET state = 'UNCERTAIN', error_message = ? WHERE id = ? AND state = 'PENDING'",
                ).use { statement ->
                    statement.setString(1, "Process restarted before the exchange outcome was durably recorded.")
                    statement.setInt(2, id)
                    if (statement.executeUpdate() == 1) incrementProjectionRevision(connection, id)
                }
            }
        }
    }

    private suspend fun <T> inTransaction(label: String, block: (Connection) -> T): T = withContext(Dispatchers.IO) {
        database.connect().use { connection ->
            connection.autoCommit = false
            try {
                val result = block(connection)
                connection.commit()
                result
            } catch (e: Exception) {
                try {
                    connection.rollback()
                } catch (rollbackFailure: Exception) {
                    e.addSuppressed(rollbackFailure)
                }
                log.error("Failed to $label", e)
                throw e
            }
        }
    }

    private fun incrementProjectionRevision(connection: Connection, intentId: Int) {
        connection.prepareStatement(
            """
            INSERT INTO execution_projection_outbox(intent_id, revision)
            VALUES (?, COALESCE((SELECT MAX(revision) + 1 FROM execution_projection_outbox WHERE intent_id = ?), 1))
            """.trimIndent(),
        ).use { statement ->
            statement.setInt(1, intentId)
            statement.setInt(2, intentId)
            check(statement.executeUpdate() == 1) {
                "Projection event was not recorded for intent $intentId."
            }
        }
    }

    override fun journalId(): String = database.journalId()

    private fun ResultSet.toOrderIntent(): OrderIntent = OrderIntent(
        id = getInt("id"),
        cycleId = getString("cycle_id"),
        clientOrderId = getString("client_order_id"),
        clientOrderIdAmbiguous = getInt("client_order_id_ambiguous") != 0,
        pair = getString("pair"),
        symbol = getString("symbol"),
        side = getString("side"),
        volume = BigDecimal(getString("volume")),
        usdAmount = BigDecimal(getString("usd_amount")),
        expectedPrice = getString("expected_price")?.let(::BigDecimal),
        createdAt = Instant.ofEpochMilli(getLong("created_at")),
        state = OrderIntentState.valueOf(getString("state")),
        orderTxid = getString("order_txid"),
        errorMessage = getString("error_message"),
        resolvedAt = getObject("resolved_at")?.let { Instant.ofEpochMilli(getLong("resolved_at")) },
        resolutionEvidence = getString("resolution_evidence"),
        outcomeVolume = getString("outcome_volume")?.let(::BigDecimal),
        legacySourceId = getString("legacy_source_id"),
        legacyIntentId = getObject("legacy_intent_id")?.let { getInt("legacy_intent_id") },
        legacyTradeId = getObject("legacy_trade_id")?.let { getInt("legacy_trade_id") },
        legacySourceState = getString("legacy_source_state"),
    )

    private fun Boolean.toSqlBoolean(): Int = if (this) 1 else 0

    private fun String?.nonBlankTrimmed(): String? = this?.trim()?.takeIf(String::isNotEmpty)

    private fun List<String>.placeholders(): String = joinToString(",") { "?" }

    private companion object {
        const val SQLITE_IN_CHUNK_SIZE = 500
    }
}
