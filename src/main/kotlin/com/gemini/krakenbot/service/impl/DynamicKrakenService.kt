package com.gemini.krakenbot.service.impl

import com.gemini.krakenbot.model.OrderResult
import com.gemini.krakenbot.model.TradeRecord
import com.gemini.krakenbot.service.ConfigService
import com.gemini.krakenbot.service.KrakenService
import com.gemini.krakenbot.service.RawBalances
import com.gemini.krakenbot.service.RawPrices
import kotlinx.coroutines.withContext
import java.math.BigDecimal
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext

class DynamicKrakenService(
    val realService: KrakenServiceImpl,
    private val simulatedService: SimulatedKrakenService,
    private val configService: ConfigService,
) : KrakenService {
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
        coroutineContext[PinnedBackend]?.service ?: resolveFromConfig()

    /**
     * Pins the live vs simulation backend for [block] at entry. If a pin is already
     * active on this coroutine, it is reused (so nested [OrderExecutor] wraps cannot
     * shadow a full rebalance/sync pin). Concurrent top-level invocations each capture
     * their own entry-time backend.
     */
    override suspend fun <T> withStableBackend(block: suspend (KrakenService) -> T): T {
        val existing = coroutineContext[PinnedBackend]?.service
        if (existing != null) {
            return block(existing)
        }
        val backend = resolveFromConfig()
        return withContext(PinnedBackend(backend)) {
            block(backend)
        }
    }

    override suspend fun getBalances(): RawBalances = currentBackend().getBalances()

    override suspend fun getTickerPrices(pairs: String): RawPrices = currentBackend().getTickerPrices(pairs)

    override suspend fun executeOrder(
        pair: String,
        type: String,
        side: String,
        volume: BigDecimal,
        dryRun: Boolean?,
    ): OrderResult = currentBackend().executeOrder(pair, type, side, volume, dryRun)

    override suspend fun getTradeHistory(startSec: Long?, offset: Int?): List<TradeRecord> =
        currentBackend().getTradeHistory(startSec, offset)

    override suspend fun getOHLC(pair: String, interval: Int, since: Long?): List<Pair<Long, BigDecimal>> =
        currentBackend().getOHLC(pair, interval, since)
}
