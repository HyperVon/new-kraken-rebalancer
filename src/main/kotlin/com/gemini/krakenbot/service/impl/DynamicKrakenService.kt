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
    private val pinLock = Any()
    private var pinnedService: KrakenService? = null
    private var pinDepth = 0

    private fun resolveFromConfig(): KrakenService = if (configService.getConfig().settings.simulation) {
        simulatedService
    } else {
        realService
    }

    private val activeService: KrakenService
        get() = synchronized(pinLock) { pinnedService } ?: resolveFromConfig()

    override suspend fun <T> withStableBackend(block: suspend () -> T): T {
        synchronized(pinLock) {
            if (pinDepth == 0) {
                pinnedService = resolveFromConfig()
            }
            pinDepth++
        }
        try {
            return block()
        } finally {
            synchronized(pinLock) {
                pinDepth--
                if (pinDepth == 0) {
                    pinnedService = null
                }
            }
        }
    }

    override suspend fun getBalances(): RawBalances = activeService.getBalances()

    override suspend fun getTickerPrices(pairs: String): RawPrices = activeService.getTickerPrices(pairs)

    override suspend fun executeOrder(pair: String, type: String, side: String, volume: BigDecimal): OrderResult =
        activeService.executeOrder(pair, type, side, volume)

    override suspend fun getTradeHistory(startSec: Long?, offset: Int?): List<TradeRecord> =
        activeService.getTradeHistory(startSec, offset)

    override suspend fun getOHLC(pair: String, interval: Int, since: Long?): List<Pair<Long, BigDecimal>> =
        activeService.getOHLC(pair, interval, since)
}
