package com.gemini.krakenbot.model

import com.gemini.krakenbot.codegen.GenerateApiMapper
import com.gemini.krakenbot.domain.isWithinRelativeTolerance
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import kotlin.math.abs
import com.gemini.krakenbot.api.TradeRecord as ApiTradeRecord

@GenerateApiMapper(ApiTradeRecord::class)
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
    val tradeId: String? = null,
    val clientOrderId: String? = null,
    val submissionState: OrderSubmissionState? = null,
)

enum class OrderSubmissionState {
    PENDING,
    UNCERTAIN,
}

/**
 * Explicit [source] when set; otherwise infer only the unambiguous local-estimate shape for DB
 * rows written before the `source` column existed. A successful live row without slippage could
 * have been either a local estimate or a settled fill, so retain that ambiguity explicitly.
 */
fun TradeRecord.effectiveSource(): TradeSource? = source ?: when {
    slippagePercent != null -> TradeSource.LOCAL_ESTIMATE
    success && !dryRun && errorMessage == null -> TradeSource.LEGACY_UNKNOWN
    else -> null
}

fun TradeRecord.isSameSymbolAndSide(other: TradeRecord): Boolean =
    this.symbol.equals(other.symbol, ignoreCase = true) &&
        this.side.equals(other.side, ignoreCase = true)

/**
 * Same fill under different Kraken pair strings (e.g. XBTUSD vs XXBTZUSD), within [tolerance].
 * Pair aliases are safe to merge only when an authoritative Kraken trade or order id agrees.
 */
fun TradeRecord.isPairAliasDuplicateOf(other: TradeRecord, tolerance: BigDecimal = BigDecimal("0.01")): Boolean =
    this.isSameSymbolAndSide(other) &&
        !this.pair.equals(other.pair, ignoreCase = true) &&
        this.success == other.success &&
        this.dryRun == other.dryRun &&
        hasSharedAuthoritativeIdentity(other) &&
        isWithinRelativeTolerance(this.volume, other.volume, tolerance) &&
        isWithinRelativeTolerance(this.usdAmount, other.usdAmount, tolerance)

/** Same pair/side within [windowMillis]; volume and USD within [tolerance] (defaults: 10s, 1%). */
fun TradeRecord.isLocalEstimateDuplicateOf(
    other: TradeRecord,
    windowMillis: Long = 10_000L,
    tolerance: BigDecimal = BigDecimal("0.01"),
): Boolean {
    val diff = abs(this.timestamp.toEpochMilli() - other.timestamp.toEpochMilli())
    val pairMatches = this.pair.equals(other.pair, ignoreCase = true)
    return this.isSameSymbolAndSide(other) &&
        pairMatches &&
        diff <= windowMillis &&
        hasCompatibleCorrelationIdentity(other) &&
        isWithinRelativeTolerance(this.volume, other.volume, tolerance) &&
        isWithinRelativeTolerance(this.usdAmount, other.usdAmount, tolerance)
}

/** Strong duplicate identity for the same fill, including exact same-pair re-fetches. */
fun TradeRecord.hasSharedAuthoritativeIdentity(other: TradeRecord): Boolean {
    if (!hasCompatibleAuthoritativeIdentity(other)) return false
    val thisTradeId = tradeId?.takeIf(String::isNotBlank)
    val otherTradeId = other.tradeId?.takeIf(String::isNotBlank)
    val thisOrderTxid = orderTxid?.takeIf(String::isNotBlank)
    val otherOrderTxid = other.orderTxid?.takeIf(String::isNotBlank)
    return (thisTradeId != null && thisTradeId == otherTradeId) ||
        (thisOrderTxid != null && thisOrderTxid == otherOrderTxid)
}

private fun TradeRecord.hasCompatibleAuthoritativeIdentity(other: TradeRecord): Boolean {
    val thisTradeId = tradeId?.takeIf(String::isNotBlank)
    val otherTradeId = other.tradeId?.takeIf(String::isNotBlank)
    if (thisTradeId != null && otherTradeId != null && thisTradeId != otherTradeId) return false
    val thisOrderTxid = orderTxid?.takeIf(String::isNotBlank)
    val otherOrderTxid = other.orderTxid?.takeIf(String::isNotBlank)
    if (thisOrderTxid != null && otherOrderTxid != null && thisOrderTxid != otherOrderTxid) return false
    return true
}

/** Correlation ids may narrow a heuristic match, but a missing id is not a conflict. */
private fun TradeRecord.hasCompatibleCorrelationIdentity(other: TradeRecord): Boolean {
    if (!hasCompatibleAuthoritativeIdentity(other)) return false
    if (hasAuthoritativeIdentity() && other.hasAuthoritativeIdentity()) {
        val sharedTradeId = tradeId?.takeIf(String::isNotBlank)?.let { it == other.tradeId?.takeIf(String::isNotBlank) }
        val sharedOrderTxid = orderTxid?.takeIf(String::isNotBlank)
            ?.let { it == other.orderTxid?.takeIf(String::isNotBlank) }
        if (sharedTradeId != true && sharedOrderTxid != true) return false
    }
    val thisCycle = cycleId?.takeIf(String::isNotBlank)
    val otherCycle = other.cycleId?.takeIf(String::isNotBlank)
    if (thisCycle != null && otherCycle != null && thisCycle != otherCycle) return false
    val thisClient = clientOrderId?.takeIf(String::isNotBlank)
    val otherClient = other.clientOrderId?.takeIf(String::isNotBlank)
    if (thisClient != null && otherClient != null && thisClient != otherClient) return false
    return true
}

fun TradeRecord.hasAuthoritativeIdentity(): Boolean = tradeId?.isNotBlank() == true || orderTxid?.isNotBlank() == true

/** True when |fee/usd| rates differ by ≥ 0.001 (0.1 percentage points). */
fun TradeRecord.feePercentDiffersMateriallyFrom(other: TradeRecord): Boolean {
    if (this.usdAmount.signum() == 0 || other.usdAmount.signum() == 0) return false
    val thisFeeRate = this.fee.divide(this.usdAmount, 8, RoundingMode.HALF_UP)
    val otherFeeRate = other.fee.divide(other.usdAmount, 8, RoundingMode.HALF_UP)
    return thisFeeRate.subtract(otherFeeRate).abs() >= BigDecimal("0.001")
}

fun TradeRecord.isLocalEstimate(): Boolean = effectiveSource() == TradeSource.LOCAL_ESTIMATE

fun TradeRecord.isSettledApiFill(): Boolean = effectiveSource() == TradeSource.API_FILL

/** True for historical rows whose origin predated explicit trade provenance. */
fun TradeRecord.isLegacyUnknown(): Boolean = effectiveSource() == TradeSource.LEGACY_UNKNOWN

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
    if (!hasCompatibleCorrelationIdentity(apiTrade)) return false
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
