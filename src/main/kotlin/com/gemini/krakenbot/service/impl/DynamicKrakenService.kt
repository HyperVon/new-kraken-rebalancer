package com.gemini.krakenbot.service.impl

import com.gemini.krakenbot.domain.OrderResult
import com.gemini.krakenbot.domain.RawBalances
import com.gemini.krakenbot.domain.RawPrices
import com.gemini.krakenbot.model.LedgerEvent
import com.gemini.krakenbot.model.TradeRecord
import com.gemini.krakenbot.service.BoundedTradeHistoryService
import com.gemini.krakenbot.service.ConfigService
import com.gemini.krakenbot.service.KrakenService
import com.gemini.krakenbot.service.SpendableBalanceService
import com.gemini.krakenbot.service.getTradeHistoryUntil
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import java.math.BigDecimal
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

class DynamicKrakenService(
    private val realService: KrakenServiceImpl,
    private val simulatedService: SimulatedKrakenService,
    private val configService: ConfigService,
) : KrakenService,
    BoundedTradeHistoryService,
    SpendableBalanceService {
    // `simulation` picks the backend; `dryRun` is enforced inside that backend's executeOrder, not here.
    private fun resolveFromConfig(): KrakenService = if (configService.getConfig().settings.simulation) {
        simulatedService
    } else {
        realService
    }

    /**
     * Backend pinned for the current coroutine via [withStableBackend]. Absent when
     * callers use unpinned entry points (dashboard reads outside a cycle/sync).
     */
    private data class PinnedBackend(val service: KrakenService) : AbstractCoroutineContextElement(Key) {
        companion object Key : CoroutineContext.Key<PinnedBackend>
    }

    private suspend fun currentBackend(): KrakenService =
        currentCoroutineContext()[PinnedBackend]?.service ?: resolveFromConfig()

    /** Cached after [getTradeHistory] so progress metadata need not downcast the port. */
    private val lastTradeHistoryTotalCount = AtomicInteger(0)

    /** Cached after [getLedgers] so sync progress metadata need not downcast the port. */
    private val lastLedgerTotalCount = AtomicInteger(0)

    /**
     * Pins the live vs simulation backend for [block] at entry. If a pin is already
     * active on this coroutine, it is reused (so nested `OrderExecutor` wraps
     * cannot shadow a full rebalance/sync pin). Concurrent top-level invocations
     * each capture their own entry-time backend.
     */
    override suspend fun <T> withStableBackend(block: suspend (KrakenService) -> T): T {
        val existing = currentCoroutineContext()[PinnedBackend]?.service
        if (existing != null) {
            return block(existing)
        }
        val backend = resolveFromConfig()
        return withContext(PinnedBackend(backend)) {
            block(backend)
        }
    }

    override suspend fun getBalances(): RawBalances = currentBackend().getBalances()

    override suspend fun getSpendableBalances(): RawBalances {
        val backend = currentBackend()
        return (backend as? SpendableBalanceService)?.getSpendableBalances() ?: backend.getBalances()
    }

    override suspend fun getTickerPrices(pairs: String): RawPrices = currentBackend().getTickerPrices(pairs)

    override suspend fun executeOrder(
        pair: String,
        type: String,
        side: String,
        volume: BigDecimal,
        dryRun: Boolean?,
        clOrdId: String?,
    ): OrderResult = currentBackend().executeOrder(pair, type, side, volume, dryRun, clOrdId)

    override suspend fun getTradeHistory(startSec: Long?, offset: Int?): List<TradeRecord> {
        val backend = currentBackend()
        val trades = backend.getTradeHistory(startSec, offset)
        lastTradeHistoryTotalCount.set(backend.getLastTradeHistoryTotalCount())
        return trades
    }

    override suspend fun getTradeHistoryUntil(startSec: Long?, offset: Int?, endSec: Long?): List<TradeRecord> {
        val backend = currentBackend()
        val trades = backend.getTradeHistoryUntil(startSec, offset, endSec)
        lastTradeHistoryTotalCount.set(backend.getLastTradeHistoryTotalCount())
        return trades
    }

    override suspend fun getOHLC(pair: String, interval: Int, since: Long?): List<Pair<Long, BigDecimal>> =
        currentBackend().getOHLC(pair, interval, since)

    override fun getLastTradeHistoryTotalCount(): Int = lastTradeHistoryTotalCount.get()

    override suspend fun getLedgers(
        startSec: Long?,
        offset: Int?,
        endSec: Long?,
        types: Set<String>?,
    ): List<LedgerEvent> {
        val backend = currentBackend()
        val ledgers = backend.getLedgers(startSec, offset, endSec, types)
        lastLedgerTotalCount.set(backend.getLastLedgerTotalCount())
        return ledgers
    }

    override fun getLastLedgerTotalCount(): Int = lastLedgerTotalCount.get()

    override suspend fun getApiCallCounter(): Double = currentBackend().getApiCallCounter()
}
