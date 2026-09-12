package com.gemini.krakenbot.service.impl.history

import com.gemini.krakenbot.model.Asset
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
class HistoricalPriceSourceException(val asset: String, message: String) : RuntimeException(message)

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
    ): BigDecimal? {
        val normalizedAsset = Asset.normalizeLedgerAsset(asset).uppercase()
        if (normalizedAsset == Asset.USD) {
            return BigDecimal.ONE
        }

        // 1. Narrow candidate-price exception for the candidate asset itself at inception baseline (candidateTime - 1ms)
        if (candidatePriceException != null && candidatePriceException > BigDecimal.ZERO) {
            return candidatePriceException
        }

        // 2. Authoritative execution price inside a bounded window around the valuation instant:
        //    a fill booked shortly after the instant still proves the market price there.
        val tradeWindowStart = eventTime.minusSeconds(MAX_EVENT_TIME_TRADE_OR_SNAPSHOT_AGE_SECONDS)
        val tradeWindowEnd = eventTime.plusSeconds(MAX_EVENT_TIME_TRADE_OR_SNAPSHOT_AGE_SECONDS)
        val recentTrade = tradesRepo.getTradesInRange(tradeWindowStart, tradeWindowEnd)
            .filter {
                it.success &&
                    !it.dryRun &&
                    !it.timestamp.isBefore(tradeWindowStart) &&
                    !it.timestamp.isAfter(tradeWindowEnd) &&
                    // TradesHistory reports non-USD quote costs in the quote currency, so only
                    // USD-quoted executions prove a USD price without a conversion contract.
                    Asset.splitTradingPair(it.pair)?.quote == Asset.USD &&
                    Asset.normalizeLedgerAsset(it.symbol).equals(normalizedAsset, ignoreCase = true)
            }
            .minByOrNull { kotlin.math.abs(it.timestamp.toEpochMilli() - eventTime.toEpochMilli()) }

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
                !it.timestamp.isBefore(snapshotWindowStart) &&
                    !it.timestamp.isAfter(eventTime) &&
                    !(it.balancesObservedAt ?: it.timestamp).isAfter(eventTime)
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
                    krakenService.getOHLC(
                        pair = pair,
                        interval = intervalMinutes,
                        since = earliestCandleStart.epochSecond,
                    )
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
                    resolved = matched.second
                    break
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
            )
        }

        return null
    }
}
