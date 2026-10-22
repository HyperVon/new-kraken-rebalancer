package com.gemini.krakenbot.service.history

import com.gemini.krakenbot.model.Asset
import com.gemini.krakenbot.service.KrakenService
import com.gemini.krakenbot.service.impl.KrakenPublicApiException
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

data class HistoryDailyPriceRequest(val rawAssetKey: String, val day: LocalDate)

/** Resolves only required holding-days from completed Kraken daily candles. */
class HistoryDailyPriceResolver(
    private val krakenService: KrakenService,
    private val nowProvider: () -> Instant = Instant::now,
) {
    suspend fun resolve(
        accountIdentityDigest: String,
        requests: Set<HistoryDailyPriceRequest>,
    ): List<HistoryDailyPrice> {
        if (requests.isEmpty()) return emptyList()
        val now = nowProvider()
        val latestCompletedOpen = now.atZone(ZoneOffset.UTC).toLocalDate().minusDays(1)
        val earliestRequested = requests.minOf { it.day.atStartOfDay(ZoneOffset.UTC).toEpochSecond() }
        val results = mutableListOf<HistoryDailyPrice>()
        val candlesByPair = mutableMapOf<String, Map<Long, BigDecimal>>()
        val requestsBySymbol = requests
            .filter { it.day <= latestCompletedOpen }
            .groupBy { Asset.normalizeLedgerAsset(it.rawAssetKey) }
        for ((symbol, symbolRequests) in requestsBySymbol) {
            val dayOpens = symbolRequests.map { it.day.atStartOfDay(ZoneOffset.UTC).toEpochSecond() }.toSet()
            if (symbol.equals(Asset.USD, ignoreCase = true)) {
                results += dayOpens.map { open ->
                    HistoryDailyPrice(
                        accountIdentityDigest = accountIdentityDigest,
                        rawAssetKey = Asset.USD,
                        pair = "USD",
                        candleOpen = Instant.ofEpochSecond(open),
                        candleEnd = Instant.ofEpochSecond(open + SECONDS_PER_DAY),
                        closeUsd = BigDecimal.ONE,
                        source = "EXACT_USD_UNIT",
                    )
                }
                continue
            }
            val directPair = Asset.tradingPair(symbol)
            val direct = candlesByPair.getOrPut(directPair) { fetchCompleted(directPair, earliestRequested, now) }

            for (open in dayOpens.sorted()) {
                val directClose = direct[open]
                val resolved = if (directClose != null) {
                    ResolvedPrice(directClose, directPair, listOf(HistoryDailyPriceLeg(directPair, directClose, "USD")))
                } else {
                    resolveFallback(symbol, open, earliestRequested, now, candlesByPair)
                } ?: continue
                results += HistoryDailyPrice(
                    accountIdentityDigest = accountIdentityDigest,
                    rawAssetKey = symbol,
                    pair = resolved.pair,
                    candleOpen = Instant.ofEpochSecond(open),
                    candleEnd = Instant.ofEpochSecond(open + SECONDS_PER_DAY),
                    closeUsd = resolved.closeUsd,
                    source = "KRAKEN_COMPLETED_DAILY_OHLC",
                    legs = resolved.legs,
                )
            }
        }
        return results
    }

    private suspend fun resolveFallback(
        symbol: String,
        open: Long,
        earliest: Long,
        now: Instant,
        candlesByPair: MutableMap<String, Map<Long, BigDecimal>>,
    ): ResolvedPrice? {
        val ticker = Asset.toKrakenTicker(symbol)
        val btcPair = listOf("${ticker}XBT", "${ticker}XXBT")
            .firstNotNullOfOrNull { pair ->
                val candles = candlesByPair.getOrPut(pair) { fetchCompleted(pair, earliest, now) }
                candles[open]?.let { pair to it }
            }
        if (btcPair != null) {
            val btcUsdPair = Asset.tradingPair(Asset.BTC)
            val btcUsd = candlesByPair.getOrPut(btcUsdPair) { fetchCompleted(btcUsdPair, earliest, now) }
            val usdClose = btcUsd[open]
            if (usdClose != null) {
                return ResolvedPrice(
                    closeUsd = btcPair.second.multiply(usdClose),
                    pair = "${btcPair.first} × $btcUsdPair",
                    legs = listOf(
                        HistoryDailyPriceLeg(btcPair.first, btcPair.second, "BTC"),
                        HistoryDailyPriceLeg(btcUsdPair, usdClose, "USD"),
                    ),
                )
            }
        }

        val eurPair = listOf("${ticker}ZEUR", "${ticker}EUR")
            .firstNotNullOfOrNull { pair ->
                val candles = candlesByPair.getOrPut(pair) { fetchCompleted(pair, earliest, now) }
                candles[open]?.let { pair to it }
            }
        if (eurPair != null) {
            val eurUsd = candlesByPair.getOrPut("EURUSD") { fetchCompleted("EURUSD", earliest, now) }
            val eurUsdClose = eurUsd[open]
            if (eurUsdClose != null) {
                return ResolvedPrice(
                    closeUsd = eurPair.second.multiply(eurUsdClose),
                    pair = "${eurPair.first} × EURUSD",
                    legs = listOf(
                        HistoryDailyPriceLeg(eurPair.first, eurPair.second, "EUR"),
                        HistoryDailyPriceLeg("EURUSD", eurUsdClose, "USD"),
                    ),
                )
            }
        }
        return null
    }

    private suspend fun fetchCompleted(pair: String, sinceSec: Long, now: Instant): Map<Long, BigDecimal> {
        val candles = try {
            krakenService.getOHLC(pair = pair, interval = DAILY_INTERVAL_MINUTES, since = sinceSec)
        } catch (error: KrakenPublicApiException) {
            if (error.errors != UNKNOWN_ASSET_PAIR_ERRORS) throw error
            return emptyMap()
        }
        return candles.asSequence()
            .filter { (open, close) ->
                open >= 0L && Instant.ofEpochSecond(open).plusSeconds(SECONDS_PER_DAY) <= now && close.signum() > 0
            }
            .associate { it.first to it.second }
    }

    private data class ResolvedPrice(val closeUsd: BigDecimal, val pair: String, val legs: List<HistoryDailyPriceLeg>)

    companion object {
        private const val DAILY_INTERVAL_MINUTES = 1_440
        private const val SECONDS_PER_DAY = 86_400L
        private val UNKNOWN_ASSET_PAIR_ERRORS = listOf("EQuery:Unknown asset pair")
    }
}
