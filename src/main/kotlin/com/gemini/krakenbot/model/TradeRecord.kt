package com.gemini.krakenbot.model

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import com.gemini.krakenbot.service.isWithinRelativeTolerance
import kotlin.math.abs

/**
 * Represents a single executed trade/order event.
 * Provides structured data instead of relying on string-based action logs.
 */
data class TradeRecord(
    val timestamp: Instant,
    val pair: String,
    val side: String,
    val symbol: String,
    val volume: BigDecimal,
    val usdAmount: BigDecimal,
    val success: Boolean,
    val dryRun: Boolean,
    val errorMessage: String? = null,
    val price: BigDecimal = BigDecimal.ZERO,
    val fee: BigDecimal = BigDecimal.ZERO,
    val slippagePercent: BigDecimal? = null
)

fun TradeRecord.isSameSymbolAndSide(other: TradeRecord): Boolean =
    this.symbol.equals(other.symbol, ignoreCase = true) &&
            this.side.equals(other.side, ignoreCase = true)

fun TradeRecord.isPairAliasDuplicateOf(other: TradeRecord): Boolean =
    this.isSameSymbolAndSide(other) &&
            this.volume.compareTo(other.volume) == 0 &&
            this.pair != other.pair

fun TradeRecord.isLocalEstimateDuplicateOf(
    other: TradeRecord,
    windowMillis: Long = 10_000L,
    tolerance: BigDecimal = BigDecimal("0.01")
): Boolean {
    val diff = abs(this.timestamp.toEpochMilli() - other.timestamp.toEpochMilli())
    return this.isSameSymbolAndSide(other) &&
            this.pair.equals(other.pair, ignoreCase = true) &&
            diff <= windowMillis &&
            isWithinRelativeTolerance(this.volume, other.volume, tolerance) &&
            isWithinRelativeTolerance(this.usdAmount, other.usdAmount, tolerance)
}

fun TradeRecord.feePercentDiffersMateriallyFrom(other: TradeRecord): Boolean {
    if (this.usdAmount.signum() == 0 || other.usdAmount.signum() == 0) return false
    val thisFeeRate = this.fee.divide(this.usdAmount, 8, RoundingMode.HALF_UP)
    val otherFeeRate = other.fee.divide(other.usdAmount, 8, RoundingMode.HALF_UP)
    return thisFeeRate.subtract(otherFeeRate).abs() >= BigDecimal("0.001")
}

fun TradeRecord.isMatchingApiTrade(
    apiTrade: TradeRecord,
    allocations: List<String>,
    windowMillis: Long = 10_000L,
    tolerance: BigDecimal = BigDecimal("0.01")
): Boolean {
    val timeDifference = abs(this.timestamp.toEpochMilli() - apiTrade.timestamp.toEpochMilli())
    if (timeDifference > windowMillis || !this.side.equals(apiTrade.side, ignoreCase = true)) {
        return false
    }
    val thisSymbol = Asset.fromTradingPair(this.pair, allocations) ?: this.symbol
    val apiSymbol = Asset.fromTradingPair(apiTrade.pair, allocations) ?: apiTrade.symbol
    return thisSymbol.equals(apiSymbol, ignoreCase = true) &&
            isWithinRelativeTolerance(this.volume, apiTrade.volume, tolerance) &&
            (this.volume.compareTo(apiTrade.volume) == 0 ||
                    isWithinRelativeTolerance(this.usdAmount, apiTrade.usdAmount, tolerance))
}
