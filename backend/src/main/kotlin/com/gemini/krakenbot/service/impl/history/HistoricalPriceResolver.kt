package com.gemini.krakenbot.service.impl.history

import com.gemini.krakenbot.model.Asset
import com.gemini.krakenbot.repository.ConsumedOhlcDependency
import com.gemini.krakenbot.repository.TradeRepository
import com.gemini.krakenbot.service.KrakenService
import com.gemini.krakenbot.util.PrecisionConstants
import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant

/**
 * Operational price-source failure (network, rate limit, malformed response). Distinct from
 * [HistoricalPriceResolver.resolveHistoricalPrice] returning null, which means the available
 * evidence simply contains no trustworthy price. Callers must not persist the former as a
 * permanent "no price exists" conclusion.
 */
class HistoricalPriceSourceException(val asset: String, message: String, val eventTime: Instant) :
    RuntimeException(message)

object HistoricalPriceResolver {
    private val log = LoggerFactory.getLogger(HistoricalPriceResolver::class.java)

    const val MAX_EVENT_TIME_TRADE_OR_SNAPSHOT_AGE_SECONDS = 180L
    const val HISTORICAL_OHLC_INTERVAL_MINUTES = 15
    const val MAX_OHLC_LOOKBACK_SECONDS = 86400L

    /**
     * Fine-to-coarse candle ladder. Kraken serves only ~720 recent candles per interval, so
     * older valuation instants (for example an approved start months in the past) are only
     * reachable through the coarser tiers; the daily tier covers roughly two years.
     */
    val HISTORICAL_OHLC_INTERVAL_CANDIDATES = listOf(15, 60, 240, 1440)

    suspend fun resolveHistoricalPrice(
        asset: String,
        eventTime: Instant,
        tradesRepo: TradeRepository,
        krakenService: KrakenService,
        candidatePriceException: BigDecimal? = null,
        marketPairs: List<String> = emptyList(),
        tradeLookbackSeconds: Long = MAX_EVENT_TIME_TRADE_OR_SNAPSHOT_AGE_SECONDS,
        futureTradeSkewSeconds: Long = MAX_EVENT_TIME_TRADE_OR_SNAPSHOT_AGE_SECONDS,
        marketPairsByBase: Map<String, List<String>> = emptyMap(),
        quoteConversionDepth: Int = 0,
        ohlcCache: HistoricalOhlcCache? = null,
        futureTradeUpperBound: Instant? = null,
        onOhlcDependencyConsumed: ((ConsumedOhlcDependency) -> Unit)? = null,
    ): BigDecimal? {
        val normalizedAsset = Asset.normalizeLedgerAsset(asset).uppercase()
        if (normalizedAsset == Asset.USD) {
            return BigDecimal.ONE
        }

        // 1. Narrow candidate-price exception for the candidate asset itself at inception baseline (candidateTime - 1ms)
        if (candidatePriceException != null && candidatePriceException > BigDecimal.ZERO) {
            return candidatePriceException
        }

        // 2. Prefer a near execution, allowing only a small forward skew. A wide lookback is
        // useful for retained contribution evidence, but it must never widen the future side of
        // the valuation window and introduce look-ahead.
        val tradeLookbackStart = eventTime.minusSeconds(tradeLookbackSeconds)
        val requestedTradeFutureEnd = runCatching {
            eventTime.plusSeconds(futureTradeSkewSeconds)
        }.getOrDefault(Instant.MAX)
        val tradeFutureEnd = minOf(requestedTradeFutureEnd, futureTradeUpperBound ?: Instant.MAX)
        val candidateTrades = if (tradeFutureEnd.isBefore(tradeLookbackStart)) {
            emptyList()
        } else {
            tradesRepo.getTradesInRange(tradeLookbackStart, tradeFutureEnd)
        }
            .filter {
                it.success &&
                    !it.dryRun &&
                    !it.timestamp.isBefore(tradeLookbackStart) &&
                    !it.timestamp.isAfter(tradeFutureEnd) &&
                    // TradesHistory reports non-USD quote costs in the quote currency, so only
                    // USD-quoted executions prove a USD price without a conversion contract.
                    Asset.splitTradingPair(it.pair)?.quote == Asset.USD &&
                    Asset.normalizeLedgerAsset(it.symbol).equals(normalizedAsset, ignoreCase = true)
            }
        val recentPastTrade = candidateTrades
            .filter {
                !it.timestamp.isAfter(eventTime) &&
                    !it.timestamp.isBefore(
                        eventTime.minusSeconds(MAX_EVENT_TIME_TRADE_OR_SNAPSHOT_AGE_SECONDS),
                    )
            }
            .maxByOrNull { it.timestamp }
        val earlierPastTrade = candidateTrades
            .filter { !it.timestamp.isAfter(eventTime) }
            .maxByOrNull { it.timestamp }
        val recentFutureTrade = candidateTrades
            .filter { it.timestamp.isAfter(eventTime) }
            .minByOrNull { it.timestamp }
        val recentTrade = recentPastTrade ?: earlierPastTrade ?: recentFutureTrade

        if (recentTrade != null) {
            if (recentTrade.volume > BigDecimal.ZERO && recentTrade.usdAmount > BigDecimal.ZERO) {
                return recentTrade.usdAmount.divide(
                    recentTrade.volume,
                    PrecisionConstants.SCALE_CRYPTO,
                    RoundingMode.HALF_UP,
                )
            }
            if (recentTrade.price > BigDecimal.ZERO) {
                return recentTrade.price
            }
        }

        // 3. Strict recent snapshot at or before eventTime within 180 seconds
        val snapshotWindowStart = eventTime.minusSeconds(MAX_EVENT_TIME_TRADE_OR_SNAPSHOT_AGE_SECONDS)
        val nearestSnap = tradesRepo.getSnapshotsInRange(snapshotWindowStart, eventTime)
            .filter {
                val observation = it.balancesObservedAt ?: Instant.MIN
                !it.timestamp.isBefore(snapshotWindowStart) &&
                    !it.timestamp.isAfter(eventTime) &&
                    !observation.isAfter(eventTime)
            }
            .minByOrNull { kotlin.math.abs(it.timestamp.toEpochMilli() - eventTime.toEpochMilli()) }

        val snapPrice = nearestSnap?.assets?.entries?.firstOrNull {
            Asset.normalizeLedgerAsset(it.key).equals(normalizedAsset, ignoreCase = true)
        }?.value?.price
        if (snapPrice != null && snapPrice > BigDecimal.ZERO) {
            return snapPrice
        }

        // 4. Completed candle from the finest interval that covers the valuation instant. Only
        //    candles that closed at or before eventTime and are at most one bucket old qualify;
        //    retained historical pairs cover markets that are no longer listed today.
        val candidatePairs = (listOf(Asset(normalizedAsset).tradingPair) + marketPairs)
            .map { it.trim().uppercase() }
            .filter(String::isNotEmpty)
            .distinct()
        var sourceFailed = false
        for (intervalMinutes in HISTORICAL_OHLC_INTERVAL_CANDIDATES) {
            val candleDurationSeconds = intervalMinutes * 60L
            val lookbackSeconds = maxOf(MAX_OHLC_LOOKBACK_SECONDS, candleDurationSeconds * 2)
            val earliestCandleStart = eventTime.minusSeconds(lookbackSeconds)
            var resolved: BigDecimal? = null
            for (pair in candidatePairs) {
                val candles = try {
                    if (ohlcCache != null) {
                        ohlcCache.getOHLC(
                            pair = pair,
                            intervalMinutes = intervalMinutes,
                            sinceEpochSecond = earliestCandleStart.epochSecond,
                            upTo = eventTime,
                            onDependencyResolved = onOhlcDependencyConsumed,
                        )
                    } else {
                        krakenService.getOHLC(
                            pair = pair,
                            interval = intervalMinutes,
                            since = earliestCandleStart.epochSecond,
                        )
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    sourceFailed = true
                    log.warn(
                        "Failed to fetch OHLC price for asset {} pair {} interval {}: {}",
                        normalizedAsset,
                        pair,
                        intervalMinutes,
                        e.message,
                    )
                    continue
                }
                val matched = candles.filter {
                    val candleStart = Instant.ofEpochSecond(it.first)
                    val candleClose = candleStart.plusSeconds(candleDurationSeconds)
                    !candleStart.isBefore(earliestCandleStart) &&
                        candleClose <= eventTime &&
                        eventTime.epochSecond - candleClose.epochSecond <= candleDurationSeconds
                }
                    .maxByOrNull { it.first }
                if (matched != null && matched.second > BigDecimal.ZERO) {
                    // A candle in a non-USD market proves a USD price only through a trustworthy
                    // conversion of the quote leg; without one the price stays unresolved.
                    val quote = Asset.splitTradingPair(pair)?.quote ?: continue
                    val converted = if (quote == Asset.USD) {
                        matched.second
                    } else {
                        convertQuoteToUsd(
                            quote = quote,
                            quotePrice = matched.second,
                            eventTime = eventTime,
                            tradesRepo = tradesRepo,
                            krakenService = krakenService,
                            tradeLookbackSeconds = tradeLookbackSeconds,
                            futureTradeSkewSeconds = futureTradeSkewSeconds,
                            marketPairsByBase = marketPairsByBase,
                            quoteConversionDepth = quoteConversionDepth,
                            ohlcCache = ohlcCache,
                            futureTradeUpperBound = futureTradeUpperBound,
                            onOhlcDependencyConsumed = onOhlcDependencyConsumed,
                        )
                    }
                    if (converted != null) {
                        resolved = converted
                        break
                    }
                }
            }
            if (resolved != null) {
                return resolved
            }
        }
        if (sourceFailed) {
            throw HistoricalPriceSourceException(
                normalizedAsset,
                "Historical OHLC sources failed for $normalizedAsset at $eventTime",
                eventTime,
            )
        }

        return null
    }

    /**
     * Converts a quote-leg price into USD by pricing the quote asset itself. One conversion hop is
     * allowed so a stablecoin-quoted candle can prove a USD price from the stablecoin's own
     * historical USD rate; deeper chains stay unresolved rather than compounding weak evidence.
     */
    private suspend fun convertQuoteToUsd(
        quote: String,
        quotePrice: BigDecimal,
        eventTime: Instant,
        tradesRepo: TradeRepository,
        krakenService: KrakenService,
        tradeLookbackSeconds: Long,
        futureTradeSkewSeconds: Long,
        marketPairsByBase: Map<String, List<String>>,
        quoteConversionDepth: Int,
        ohlcCache: HistoricalOhlcCache?,
        futureTradeUpperBound: Instant?,
        onOhlcDependencyConsumed: ((ConsumedOhlcDependency) -> Unit)? = null,
    ): BigDecimal? {
        if (quoteConversionDepth >= 1) return null
        val quoteUsdPrice = resolveHistoricalPrice(
            asset = quote,
            eventTime = eventTime,
            tradesRepo = tradesRepo,
            krakenService = krakenService,
            tradeLookbackSeconds = tradeLookbackSeconds,
            futureTradeSkewSeconds = futureTradeSkewSeconds,
            marketPairs = marketPairsByBase[quote].orEmpty(),
            marketPairsByBase = marketPairsByBase,
            quoteConversionDepth = quoteConversionDepth + 1,
            ohlcCache = ohlcCache,
            futureTradeUpperBound = futureTradeUpperBound,
            onOhlcDependencyConsumed = onOhlcDependencyConsumed,
        ) ?: return null
        return quotePrice
            .multiply(quoteUsdPrice)
            .setScale(PrecisionConstants.SCALE_CRYPTO, RoundingMode.HALF_UP)
    }
}
