package com.gemini.krakenbot.service

import com.gemini.krakenbot.domain.OrderResult
import com.gemini.krakenbot.domain.RawBalances
import com.gemini.krakenbot.domain.RawPrices
import com.gemini.krakenbot.model.DepositStatusRecord
import com.gemini.krakenbot.model.InternalTransferRecord
import com.gemini.krakenbot.model.KrakenApiConstants
import com.gemini.krakenbot.model.LedgerEvent
import com.gemini.krakenbot.model.TradeRecord
import com.gemini.krakenbot.model.WithdrawStatusRecord
import java.math.BigDecimal

/**
 * Test-only controllable [KrakenService]: suppliers drive balances/prices/history, and
 * [executedOrders] records placements. Prefer this in unit/evaluation tests.
 *
 * Distinct from [com.gemini.krakenbot.service.impl.SimulatedKrakenService], the production
 * emulator used when `settings.simulation=true` (seeded portfolio, drifted prices).
 *
 * Suppliers and [executeOrderAction] / [orderResultFactory] may be reassigned between tests.
 * [seedLedgerEntries] pre-seeds ledger entries served like the Kraken Ledgers endpoint
 * (type/time filtered, offset-paged at [KrakenApiConstants.LEDGER_PAGE_SIZE], matching total
 * via [getLastLedgerTotalCount]).
 */
class FakeKrakenService :
    KrakenService,
    BoundedTradeHistoryService,
    RecoveryTradeHistoryService {
    var balanceSupplier: () -> Map<String, Any> = { emptyMap() }
    var pricesSupplier: (String) -> Map<String, Any> = { emptyMap() }
    var tradeHistorySupplier: (Long?, Int?) -> List<TradeRecord> = { _, _ -> emptyList() }
    var ledgerSupplier: (Long?, Int?, Long?, Set<String>?) -> List<LedgerEvent> = { _, _, _, _ -> emptyList() }
    var ohlcSupplier: (String, Int, Long?) -> List<Pair<Long, BigDecimal>> = { _, _, _ -> emptyList() }
    var depositStatusSupplier: (Long?, Long?) -> List<DepositStatusRecord> = { _, _ -> emptyList() }
    var withdrawStatusSupplier: (Long?, Long?) -> List<WithdrawStatusRecord> = { _, _ -> emptyList() }
    var internalTransfersSupplier: (Long?, Long?) -> List<InternalTransferRecord> = { _, _ -> emptyList() }
    var fundingEvidenceScopeSupplier: () -> String = { "fake-account" }

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
    var ledgerRawPageSizeOverride: Int? = null
    private var lastRecordedLedgerRawPageSize = 0
    var getDepositStatusCallCount = 0
    var getWithdrawStatusCallCount = 0
    var getInternalTransfersCallCount = 0
    var getOHLCCallCount = 0

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

    override suspend fun getTradeHistoryUntil(startSec: Long?, offset: Int?, endSec: Long?): List<TradeRecord> {
        getTradeHistoryCallCount++
        return tradeHistorySupplier(startSec, offset)
    }

    override suspend fun getRecoveryTradeHistoryUntil(
        startSec: Long?,
        offset: Int?,
        endSec: Long?,
    ): List<TradeRecord> {
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
        val entries = ledgerSupplier(startSec, offset, endSec, types)
        lastRecordedLedgerRawPageSize = entries.size
        return entries
    }

    override fun getLastLedgerTotalCount(): Int = ledgerTotalCountOverride

    override fun getLastLedgerRawPageSize(): Int = ledgerRawPageSizeOverride ?: lastRecordedLedgerRawPageSize

    override suspend fun getDepositStatus(startSec: Long?, endSec: Long?): List<DepositStatusRecord> {
        getDepositStatusCallCount++
        return depositStatusSupplier(startSec, endSec)
    }

    override suspend fun getWithdrawStatus(startSec: Long?, endSec: Long?): List<WithdrawStatusRecord> {
        getWithdrawStatusCallCount++
        return withdrawStatusSupplier(startSec, endSec)
    }

    override suspend fun getInternalTransfers(startSec: Long?, endSec: Long?): List<InternalTransferRecord> {
        getInternalTransfersCallCount++
        return internalTransfersSupplier(startSec, endSec)
    }

    override suspend fun getFundingEvidenceScope(): String = fundingEvidenceScopeSupplier()

    /**
     * Pre-seeds ledger entries (e.g. staking rewards or consumer spend/receive legs) and serves them like the Kraken
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
        dryRun: Boolean,
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
                dryRun = dryRun,
                orderTxid = if (dryRun) null else "FAKE-ORDER-${executedOrders.size}",
            )
    }

    override suspend fun getOHLC(pair: String, interval: Int, since: Long?): List<Pair<Long, BigDecimal>> {
        getOHLCCallCount++
        return ohlcSupplier(pair, interval, since)
    }
}

data class OrderCall(
    val pair: String,
    val type: String,
    val side: String,
    val volume: BigDecimal,
    val dryRun: Boolean,
    val clOrdId: String? = null,
)
