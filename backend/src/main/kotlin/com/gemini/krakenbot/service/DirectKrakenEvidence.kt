package com.gemini.krakenbot.service

import com.gemini.krakenbot.domain.RawBalances
import com.gemini.krakenbot.domain.RawPrices
import com.gemini.krakenbot.model.Asset
import java.math.BigDecimal
import java.time.Instant

/** Validation state for one value read directly from a Kraken response. */
enum class DirectEvidenceStatus {
    VALID,
    MISSING,
    MALFORMED,
    NEGATIVE,
    NON_POSITIVE,
    AMBIGUOUS,
}

/** Raw decimal text and its exact parsed value; invalid evidence keeps the raw input. */
data class DirectValueEvidence(val rawValue: String?, val value: BigDecimal?, val status: DirectEvidenceStatus)

data class DirectAssetBalanceEvidence(
    val symbol: String,
    val rawKeys: List<String>,
    val rawValuesByKey: Map<String, String?>,
    /** Populated only when exactly one response key resolves to this configured symbol. */
    val rawValue: String?,
    /** Exact decimal, retained for negative values too; consumers must check [status]. */
    val value: BigDecimal?,
    val status: DirectEvidenceStatus,
)

/** One successful ordinary `/private/Balance` response, with its local request bounds. */
data class DirectBalanceCapture(
    val requestStartedAt: Instant,
    val responseEndedAt: Instant,
    val resultShapeValid: Boolean,
    /** Exact Kraken asset IDs as keys; absence is not represented as a zero amount. */
    val valuesByAssetId: Map<String, DirectValueEvidence>,
) {
    /**
     * Resolve a configured asset using known Kraken aliases while preserving absence and
     * rejecting multiple aliases instead of silently selecting one.
     */
    fun forConfiguredAsset(symbol: String): DirectAssetBalanceEvidence {
        val canonical = Asset.canonicalSymbol(symbol)
        val rawKeys = Asset.possibleBalanceKeys(canonical).filter(valuesByAssetId::containsKey)
        val rawValues = rawKeys.associateWith { valuesByAssetId.getValue(it).rawValue }
        val single = rawKeys.singleOrNull()?.let(valuesByAssetId::get)
        val status = when {
            !resultShapeValid -> DirectEvidenceStatus.MALFORMED
            rawKeys.isEmpty() -> DirectEvidenceStatus.MISSING
            rawKeys.size > 1 -> DirectEvidenceStatus.AMBIGUOUS
            else -> single!!.status
        }
        return DirectAssetBalanceEvidence(
            symbol = canonical,
            rawKeys = rawKeys,
            rawValuesByKey = rawValues,
            rawValue = single?.rawValue,
            value = single?.value,
            status = status,
        )
    }
}

data class DirectTickerRequest(val symbol: String, val pair: String)

data class DirectTickerMark(
    val symbol: String,
    val requestedPair: String,
    val responsePair: String?,
    val candidateResponsePairs: List<String>,
    /** Exact raw `c[0]` text where present. */
    val rawPrice: String?,
    /** Exact decimal, retained for non-positive values too; consumers must check [status]. */
    val price: BigDecimal?,
    val status: DirectEvidenceStatus,
    /** Raw `c[0]` evidence per matching response pair, including ambiguous candidates. */
    val candidateValuesByPair: Map<String, DirectValueEvidence> = emptyMap(),
)

/** One successful public `/public/Ticker` response, retaining a mark for every requested symbol. */
data class DirectTickerCapture(
    val requestStartedAt: Instant,
    val responseEndedAt: Instant,
    val resultShapeValid: Boolean,
    val marksBySymbol: Map<String, DirectTickerMark>,
)

data class BalanceRead(val balances: RawBalances, val capture: DirectBalanceCapture? = null)

data class TickerRead(val prices: RawPrices, val capture: DirectTickerCapture? = null)
