package com.gemini.krakenbot.service

import com.gemini.krakenbot.model.LedgerEvent
import com.gemini.krakenbot.model.OrderResult
import com.gemini.krakenbot.model.TradeRecord
import com.gemini.krakenbot.service.impl.KrakenApiConstants
import java.math.BigDecimal

/**
 * Test-only controllable [KrakenService]: suppliers drive balances/prices/history, and
 * [executedOrders] records placements. Prefer this in unit/evaluation tests.
 *
 * Distinct from [com.gemini.krakenbot.service.impl.SimulatedKrakenService], the production
 * emulator used when `settings.simulation=true` (seeded portfolio, drifted prices).
 *
 * Suppliers and [executeOrderAction] / [orderResultFactory] may be reassigned between tests.
 * [seedLedgerEntries] pre-seeds staking/dividend entries served like the Kraken Ledgers
 * endpoint (type/time filtered, offset-paged at [KrakenApiConstants.LEDGER_PAGE_SIZE],
 * matching total via [getLastLedgerTotalCount]).
 */
class FakeKrakenService : KrakenService {
    var balanceSupplier: () -> Map<String, Any> = { emptyMap() }
    var pricesSupplier: (String) -> Map<String, Any> = { emptyMap() }
    var tradeHistorySupplier: (Long?, Int?) -> List<TradeRecord> = { _, _ -> emptyList() }
    var ledgerSupplier: (Long?, Int?, Long?, Set<String>?) -> List<LedgerEvent> = { _, _, _, _ -> emptyList() }

    /** Optional side effect after recording (e.g. throw to simulate placement failure). */
    var executeOrderAction: ((String, String, String, BigDecimal) -> Unit)? =
        null

    /** When set, overrides the default successful [OrderResult]. */
    var orderResultFactory: ((String, String, String, BigDecimal) -> OrderResult)? =
        null

    var executedOrders = mutableListOf<OrderCall>()
    var getBalancesCallCount = 0
    var getTradeHistoryCallCount = 0
    var tradeHistoryTotalCountOverride = 0
    var getLedgersCallCount = 0
    var ledgerTotalCountOverride = 0

    private var seededLedgerEntries: List<LedgerEvent> = emptyList()

    override suspend fun getBalances(): RawBalances {
        getBalancesCallCount++
        return balanceSupplier().mapValues { (_, value) ->
            when (value) {
                is BigDecimal -> value
                is Double -> BigDecimal.valueOf(value)
                is Number -> BigDecimal(value.toString())
                else -> BigDecimal.ZERO
            }
        }
    }

    override suspend fun getTickerPrices(pairs: String): RawPrices = pricesSupplier(pairs).mapValues { (_, value) ->
        when (value) {
            is BigDecimal -> value
            is Double -> BigDecimal.valueOf(value)
            is Number -> BigDecimal(value.toString())
            else -> BigDecimal.ZERO
        }
    }

    override suspend fun getTradeHistory(startSec: Long?, offset: Int?): List<TradeRecord> {
        getTradeHistoryCallCount++
        return tradeHistorySupplier(startSec, offset)
    }

    override fun getLastTradeHistoryTotalCount(): Int = tradeHistoryTotalCountOverride

    override suspend fun getLedgers(
        startSec: Long?,
        offset: Int?,
        endSec: Long?,
        types: Set<String>?,
    ): List<LedgerEvent> {
        getLedgersCallCount++
        return ledgerSupplier(startSec, offset, endSec, types)
    }

    override fun getLastLedgerTotalCount(): Int = ledgerTotalCountOverride

    /**
     * Pre-seeds ledger entries (e.g. staking rewards) and serves them like the Kraken
     * Ledgers endpoint: filtered by requested types and the start/end time window,
     * newest-first, paged at [KrakenApiConstants.LEDGER_PAGE_SIZE] from [offset], with
     * [getLastLedgerTotalCount] reporting the matching (unpaged) total.
     */
    fun seedLedgerEntries(entries: List<LedgerEvent>) {
        seededLedgerEntries = entries
        ledgerSupplier = { startSec, offset, endSec, types ->
            val matching = seededLedgerEntries.filter { entry ->
                (types == null || entry.type in types) &&
                    (startSec == null || entry.time.epochSecond >= startSec) &&
                    (endSec == null || entry.time.epochSecond <= endSec)
            }.sortedByDescending { it.time }
            ledgerTotalCountOverride = matching.size
            matching.drop((offset ?: 0).coerceAtLeast(0)).take(KrakenApiConstants.LEDGER_PAGE_SIZE)
        }
    }

    override suspend fun executeOrder(
        pair: String,
        type: String,
        side: String,
        volume: BigDecimal,
        dryRun: Boolean?,
        clOrdId: String?,
    ): OrderResult {
        executedOrders.add(OrderCall(pair, type, side, volume, dryRun, clOrdId))
        executeOrderAction?.invoke(pair, type, side, volume)
        return orderResultFactory?.invoke(pair, type, side, volume)
            ?: OrderResult(
                success = true,
                pair = pair,
                side = side,
                volume = volume,
                dryRun = dryRun == true,
                orderTxid = if (dryRun == true) null else "FAKE-ORDER-${executedOrders.size}",
            )
    }

    override suspend fun getOHLC(pair: String, interval: Int, since: Long?): List<Pair<Long, BigDecimal>> = emptyList()
}

data class OrderCall(
    val pair: String,
    val type: String,
    val side: String,
    val volume: BigDecimal,
    val dryRun: Boolean? = null,
    val clOrdId: String? = null,
)
