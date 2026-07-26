package com.gemini.krakenbot.service.impl

import com.gemini.krakenbot.model.OrderResult
import com.gemini.krakenbot.model.TradeRecord
import com.gemini.krakenbot.service.ConfigService
import com.gemini.krakenbot.service.KrakenService
import com.gemini.krakenbot.service.RawBalances
import com.gemini.krakenbot.service.RawPrices
import java.math.BigDecimal

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

    private val activeService: KrakenService
        get() = resolveFromConfig()

    /**
     * Pins the live vs simulation backend for [block] at entry. Each invocation gets its own
     * captured backend — concurrent / nested blocks do not share process-global pin state.
     */
    override suspend fun <T> withStableBackend(block: suspend (KrakenService) -> T): T {
        val backend = resolveFromConfig()
        return block(backend)
    }

    override suspend fun getBalances(): RawBalances = activeService.getBalances()

    override suspend fun getTickerPrices(pairs: String): RawPrices = activeService.getTickerPrices(pairs)

    override suspend fun executeOrder(
        pair: String,
        type: String,
        side: String,
        volume: BigDecimal,
        dryRun: Boolean?,
    ): OrderResult = activeService.executeOrder(pair, type, side, volume, dryRun)

    override suspend fun getTradeHistory(startSec: Long?, offset: Int?): List<TradeRecord> =
        activeService.getTradeHistory(startSec, offset)

    override suspend fun getOHLC(pair: String, interval: Int, since: Long?): List<Pair<Long, BigDecimal>> =
        activeService.getOHLC(pair, interval, since)
}
