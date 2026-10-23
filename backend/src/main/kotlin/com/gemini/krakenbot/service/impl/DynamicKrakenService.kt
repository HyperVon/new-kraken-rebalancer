package com.gemini.krakenbot.service.impl

import com.gemini.krakenbot.domain.OrderResult
import com.gemini.krakenbot.domain.RawBalances
import com.gemini.krakenbot.domain.RawPrices
import com.gemini.krakenbot.model.ExchangeTradePage
import com.gemini.krakenbot.model.KrakenAssetMetadata
import com.gemini.krakenbot.service.BalanceRead
import com.gemini.krakenbot.service.ConfigService
import com.gemini.krakenbot.service.DirectKrakenEvidenceService
import com.gemini.krakenbot.service.DirectTickerRequest
import com.gemini.krakenbot.service.KrakenService
import com.gemini.krakenbot.service.SpendableBalanceService
import com.gemini.krakenbot.service.TickerRead
import com.gemini.krakenbot.service.readBalancesWithCapture
import com.gemini.krakenbot.service.readTickerPricesWithCapture
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import java.math.BigDecimal
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

class DynamicKrakenService(
    private val realService: KrakenServiceImpl,
    private val simulatedService: SimulatedKrakenService,
    private val configService: ConfigService,
) : KrakenService,
    DirectKrakenEvidenceService,
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

    override suspend fun readBalancesWithCapture(): BalanceRead = currentBackend().readBalancesWithCapture()

    override suspend fun getSpendableBalances(): RawBalances {
        val backend = currentBackend()
        return (backend as? SpendableBalanceService)?.getSpendableBalances() ?: backend.getBalances()
    }

    override suspend fun getTickerPrices(pairs: String): RawPrices = currentBackend().getTickerPrices(pairs)

    override suspend fun readTickerPricesWithCapture(requests: List<DirectTickerRequest>): TickerRead =
        currentBackend().readTickerPricesWithCapture(requests)

    override suspend fun getAssetMetadata(): List<KrakenAssetMetadata> = currentBackend().getAssetMetadata()

    override suspend fun getFreshAssetMetadata(): List<KrakenAssetMetadata> = currentBackend().getFreshAssetMetadata()

    override suspend fun executeOrder(
        pair: String,
        type: String,
        side: String,
        volume: BigDecimal,
        dryRun: Boolean,
        clOrdId: String?,
    ): OrderResult = currentBackend().executeOrder(pair, type, side, volume, dryRun, clOrdId)

    override suspend fun getRecentTradePage(
        startSec: Long?,
        endSec: Long?,
        cursor: String?,
        limit: Int?,
    ): ExchangeTradePage = currentBackend().getRecentTradePage(
        startSec = startSec,
        endSec = endSec,
        cursor = cursor,
        limit = limit,
    )

    override suspend fun getFundingEvidenceScope(): String = currentBackend().getFundingEvidenceScope()

    override suspend fun getAuthenticatedAccountIdentity(): String? = currentBackend().getAuthenticatedAccountIdentity()

    override suspend fun getApiCallCounter(): Double = currentBackend().getApiCallCounter()
}
