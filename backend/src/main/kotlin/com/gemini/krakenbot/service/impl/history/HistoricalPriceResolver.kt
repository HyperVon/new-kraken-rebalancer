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

object HistoricalPriceResolver {
    private val log = LoggerFactory.getLogger(HistoricalPriceResolver::class.java)

    const val MAX_EVENT_TIME_TRADE_OR_SNAPSHOT_AGE_SECONDS = 180L
    const val HISTORICAL_OHLC_INTERVAL_MINUTES = 15
    const val MAX_OHLC_LOOKBACK_SECONDS = 86400L
    const val MAX_COMPLETED_OHLC_AGE_SECONDS = HISTORICAL_OHLC_INTERVAL_MINUTES * 60L

    suspend fun resolveHistoricalPrice(
        asset: String,
        eventTime: Instant,
        tradesRepo: TradeRepository,
        krakenService: KrakenService,
        candidatePriceException: BigDecimal? = null,
    ): BigDecimal? {
        val normalizedAsset = Asset.normalizeLedgerAsset(asset).uppercase()
        if (normalizedAsset == Asset.USD) {
            return BigDecimal.ONE
        }

        // 1. Narrow candidate-price exception for the candidate asset itself at inception baseline (candidateTime - 1ms)
        if (candidatePriceException != null && candidatePriceException > BigDecimal.ZERO) {
            return candidatePriceException
        }

        // 2. Strict recent trade at or before eventTime within 180 seconds
        val tradeWindowStart = eventTime.minusSeconds(MAX_EVENT_TIME_TRADE_OR_SNAPSHOT_AGE_SECONDS)
        val recentTrade = tradesRepo.getTradesInRange(tradeWindowStart, eventTime)
            .filter {
                it.success &&
                    !it.dryRun &&
                    !it.timestamp.isBefore(tradeWindowStart) &&
                    !it.timestamp.isAfter(eventTime) &&
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

        // 4. Completed 15-minute intraday OHLC candle within 24 hours
        try {
            val pair = Asset(normalizedAsset).tradingPair
            val sinceSec = eventTime.minusSeconds(MAX_OHLC_LOOKBACK_SECONDS).epochSecond
            val candles = krakenService.getOHLC(
                pair = pair,
                interval = HISTORICAL_OHLC_INTERVAL_MINUTES,
                since = sinceSec,
            )
            val candleDurationSeconds = HISTORICAL_OHLC_INTERVAL_MINUTES * 60L
            val earliestCandleStart = eventTime.minusSeconds(MAX_OHLC_LOOKBACK_SECONDS)
            val matched = candles.filter {
                val candleStart = Instant.ofEpochSecond(it.first)
                val candleClose = candleStart.plusSeconds(candleDurationSeconds)
                !candleStart.isBefore(earliestCandleStart) &&
                    candleClose <= eventTime &&
                    eventTime.epochSecond - candleClose.epochSecond <= MAX_COMPLETED_OHLC_AGE_SECONDS
            }
                .maxByOrNull { it.first }
            if (matched != null && matched.second > BigDecimal.ZERO) {
                return matched.second
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("Failed to fetch OHLC price for asset {} at {}: {}", normalizedAsset, eventTime, e.message)
        }

        return null
    }
}
