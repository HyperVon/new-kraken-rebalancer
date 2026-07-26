package com.gemini.krakenbot.model

import com.gemini.krakenbot.service.isWithinRelativeTolerance
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import kotlin.math.abs

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
    val slippagePercent: BigDecimal? = null,
    val expectedPrice: BigDecimal? = null,
    val source: TradeSource? = null,
    val id: Int? = null,
    val cycleId: String? = null,
    val orderTxid: String? = null,
)

/**
 * Explicit [source] when set; otherwise infer from row shape for DB rows written before the
 * `source` column existed (settled fill vs local estimate heuristics).
 */
fun TradeRecord.effectiveSource(): TradeSource? = source ?: when {
    success && !dryRun && errorMessage == null && slippagePercent == null -> TradeSource.API_FILL
    slippagePercent != null -> TradeSource.LOCAL_ESTIMATE
    else -> null
}

fun TradeRecord.isSameSymbolAndSide(other: TradeRecord): Boolean =
    this.symbol.equals(other.symbol, ignoreCase = true) &&
        this.side.equals(other.side, ignoreCase = true)

/** Same fill under different Kraken pair strings (e.g. XBTUSD vs XXBTZUSD), within [tolerance]. */
fun TradeRecord.isPairAliasDuplicateOf(other: TradeRecord, tolerance: BigDecimal = BigDecimal("0.01")): Boolean =
    this.isSameSymbolAndSide(other) &&
        !this.pair.equals(other.pair, ignoreCase = true) &&
        this.success == other.success &&
        this.dryRun == other.dryRun &&
        isWithinRelativeTolerance(this.volume, other.volume, tolerance) &&
        isWithinRelativeTolerance(this.usdAmount, other.usdAmount, tolerance) &&
        (
            this.hasDifferentTradeProvenanceFrom(other) ||
                (
                    this.usdAmount.compareTo(other.usdAmount) == 0 &&
                        this.fee.compareTo(other.fee) == 0 &&
                        this.price.compareTo(other.price) == 0
                    )
            )

/** Same pair/side within [windowMillis]; volume and USD within [tolerance] (defaults: 10s, 1%). */
fun TradeRecord.isLocalEstimateDuplicateOf(
    other: TradeRecord,
    windowMillis: Long = 10_000L,
    tolerance: BigDecimal = BigDecimal("0.01"),
): Boolean {
    val diff = abs(this.timestamp.toEpochMilli() - other.timestamp.toEpochMilli())
    return this.isSameSymbolAndSide(other) &&
        this.pair.equals(other.pair, ignoreCase = true) &&
        diff <= windowMillis &&
        isWithinRelativeTolerance(this.volume, other.volume, tolerance) &&
        isWithinRelativeTolerance(this.usdAmount, other.usdAmount, tolerance)
}

/** True when |fee/usd| rates differ by ≥ 0.001 (0.1 percentage points). */
fun TradeRecord.feePercentDiffersMateriallyFrom(other: TradeRecord): Boolean {
    if (this.usdAmount.signum() == 0 || other.usdAmount.signum() == 0) return false
    val thisFeeRate = this.fee.divide(this.usdAmount, 8, RoundingMode.HALF_UP)
    val otherFeeRate = other.fee.divide(other.usdAmount, 8, RoundingMode.HALF_UP)
    return thisFeeRate.subtract(otherFeeRate).abs() >= BigDecimal("0.001")
}

fun TradeRecord.isLocalEstimate(): Boolean = effectiveSource() == TradeSource.LOCAL_ESTIMATE

fun TradeRecord.isSettledApiFill(): Boolean = effectiveSource() == TradeSource.API_FILL

fun TradeRecord.hasDifferentTradeProvenanceFrom(other: TradeRecord): Boolean =
    (this.isLocalEstimate() && other.isSettledApiFill()) ||
        (other.isLocalEstimate() && this.isSettledApiFill())

/**
 * Local order row vs Kraken fill for sync reconcile: same side/symbol within [windowMillis],
 * volume within [tolerance], and USD also within tolerance unless volumes are exact.
 *
 * Dry-run locals never hit the exchange, so they must not match an API fill — otherwise sync
 * would rewrite a dry-run estimate into a live [TradeSource.API_FILL] (CQ-8-L1).
 */
fun TradeRecord.isMatchingApiTrade(
    apiTrade: TradeRecord,
    allocations: List<String>,
    windowMillis: Long = 10_000L,
    tolerance: BigDecimal = BigDecimal("0.01"),
): Boolean {
    if (this.dryRun) return false
    val timeDifference = abs(this.timestamp.toEpochMilli() - apiTrade.timestamp.toEpochMilli())
    if (timeDifference > windowMillis || !this.side.equals(apiTrade.side, ignoreCase = true)) {
        return false
    }
    val thisSymbol = Asset.fromTradingPair(this.pair, allocations) ?: this.symbol
    val apiSymbol = Asset.fromTradingPair(apiTrade.pair, allocations) ?: apiTrade.symbol
    return thisSymbol.equals(apiSymbol, ignoreCase = true) &&
        isWithinRelativeTolerance(this.volume, apiTrade.volume, tolerance) &&
        (
            this.volume.compareTo(apiTrade.volume) == 0 ||
                isWithinRelativeTolerance(this.usdAmount, apiTrade.usdAmount, tolerance)
            )
}
