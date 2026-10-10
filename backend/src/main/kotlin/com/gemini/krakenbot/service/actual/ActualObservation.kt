package com.gemini.krakenbot.service.actual

import com.gemini.krakenbot.config.AppConfig
import com.gemini.krakenbot.model.Asset
import com.gemini.krakenbot.service.DirectBalanceCapture
import com.gemini.krakenbot.service.DirectEvidenceStatus
import com.gemini.krakenbot.service.DirectTickerCapture
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.util.Locale

const val DEFAULT_MANAGED_WALLET_SCOPE = "KRAKEN_DEFAULT_WALLET_CONFIGURED_ALLOCATIONS"
const val DIRECT_BALANCE_SOURCE = "KRAKEN_PRIVATE_BALANCE"
const val DIRECT_PRICE_SOURCE = "KRAKEN_PUBLIC_TICKER_LAST_TRADE_C0"
const val OBSERVATION_CURRENCY = "USD"
const val ACTUAL_OBSERVATION_SCHEMA_VERSION = 1
val MAX_BALANCE_PRICE_GAP = Duration.ofSeconds(60)

enum class ActualObservationStatus { COMPLETE, INCOMPLETE }

enum class ActualAssetStatus {
    COMPLETE,
    MISSING_BALANCE,
    INVALID_BALANCE,
    AMBIGUOUS_BALANCE,
    MISSING_PRICE,
    INVALID_PRICE,
    INVALID_TIME_WINDOW,
}

data class ActualAssetObservation(
    val symbol: String,
    val status: ActualAssetStatus,
    val rawBalanceKeys: List<String>,
    val rawBalanceValues: Map<String, String?>,
    val quantity: BigDecimal?,
    val requestedPair: String?,
    val responsePair: String?,
    val candidateResponsePairs: List<String>,
    val candidateRawPrices: Map<String, String?>,
    val rawPrice: String?,
    val priceUsd: BigDecimal?,
    val valueUsd: BigDecimal?,
    val reason: String?,
)

data class ActualObservation(
    val observationId: String,
    val accountIdentityDigest: String,
    val walletScope: String,
    val balanceSource: String = DIRECT_BALANCE_SOURCE,
    val priceSource: String = DIRECT_PRICE_SOURCE,
    val valuationCurrency: String = OBSERVATION_CURRENCY,
    val observationSchemaVersion: Int = ACTUAL_OBSERVATION_SCHEMA_VERSION,
    val scopeFingerprint: String,
    val scopeSymbols: List<String>,
    val observedAt: Instant,
    val balanceRequestStartedAt: Instant,
    val balanceResponseEndedAt: Instant,
    val priceRequestStartedAt: Instant,
    val priceResponseEndedAt: Instant,
    /** Assigned by the append-only store at append time. */
    val persistedAt: Instant? = null,
    val status: ActualObservationStatus,
    val totalUsd: BigDecimal?,
    val incompleteReasons: List<String>,
    val assets: List<ActualAssetObservation>,
)

object ActualObservationValuator {
    fun scopeSymbols(config: AppConfig): List<String> = config.allocations
        .map { Asset.normalizeLedgerAsset(it.symbol.value).uppercase(Locale.ROOT) }
        .distinct()
        .sorted()

    fun scopeFingerprint(symbols: List<String>): String = sha256(
        "actual-managed-scope-v1\u0000${symbols.distinct().sorted().joinToString("\u0000")}",
    )

    fun build(
        observationId: String,
        scopeSymbols: List<String>,
        accountIdentityDigest: String,
        balances: DirectBalanceCapture,
        prices: DirectTickerCapture,
        now: Instant = Instant.now(),
    ): ActualObservation {
        val symbols = scopeSymbols.map { Asset.normalizeLedgerAsset(it).uppercase(Locale.ROOT) }.distinct().sorted()
        val windowValid = isValidTimeWindow(balances, prices) &&
            gapBetween(balances, prices) <= MAX_BALANCE_PRICE_GAP
        val windowReason = when {
            !isValidTimeWindow(balances, prices) -> "Balance or ticker request timestamps are invalid."

            gapBetween(balances, prices) > MAX_BALANCE_PRICE_GAP ->
                "Balance and ticker observations are more than ${MAX_BALANCE_PRICE_GAP.seconds} seconds apart."

            else -> null
        }
        val assets = symbols.map { symbol ->
            val balance = balances.forConfiguredAsset(symbol)
            val price = prices.marksBySymbol[symbol]
            val isUsd = Asset.normalizeLedgerAsset(symbol).equals(Asset.USD, ignoreCase = true)
            val quantity = balance.value
            val priceUsd = if (isUsd) BigDecimal.ONE else price?.price
            val rawPrice = if (isUsd) "1" else price?.rawPrice
            val priceInvalid = price?.status != DirectEvidenceStatus.VALID ||
                priceUsd == null || priceUsd.signum() <= 0
            val status = when {
                !windowValid -> ActualAssetStatus.INVALID_TIME_WINDOW

                balance.status == DirectEvidenceStatus.MISSING -> ActualAssetStatus.MISSING_BALANCE

                balance.status == DirectEvidenceStatus.AMBIGUOUS -> ActualAssetStatus.AMBIGUOUS_BALANCE

                balance.status != DirectEvidenceStatus.VALID || quantity == null || quantity.signum() < 0 ->
                    ActualAssetStatus.INVALID_BALANCE

                !isUsd && (price == null || price.status == DirectEvidenceStatus.MISSING) ->
                    ActualAssetStatus.MISSING_PRICE

                !isUsd && priceInvalid -> ActualAssetStatus.INVALID_PRICE

                else -> ActualAssetStatus.COMPLETE
            }
            val value = if (status == ActualAssetStatus.COMPLETE) {
                checkNotNull(quantity).multiply(checkNotNull(priceUsd))
            } else {
                null
            }
            ActualAssetObservation(
                symbol = symbol,
                status = status,
                rawBalanceKeys = balance.rawKeys,
                rawBalanceValues = balance.rawValuesByKey,
                quantity = quantity,
                requestedPair = if (isUsd) "USD" else price?.requestedPair,
                responsePair = if (isUsd) "USD" else price?.responsePair,
                candidateResponsePairs = if (isUsd) emptyList() else price?.candidateResponsePairs.orEmpty(),
                candidateRawPrices = if (isUsd) {
                    emptyMap()
                } else {
                    price?.candidateValuesByPair.orEmpty()
                        .mapValues { (_, evidence) -> evidence.rawValue }
                },
                rawPrice = rawPrice,
                priceUsd = priceUsd,
                valueUsd = value,
                reason = reasonFor(status, windowReason, balance.status, price?.status),
            )
        }
        val reasons = assets.filter { it.status != ActualAssetStatus.COMPLETE }
            .mapNotNull { asset -> "${asset.symbol}: ${asset.reason ?: asset.status.name}" }
        val complete = reasons.isEmpty() && balances.resultShapeValid && prices.resultShapeValid
        return ActualObservation(
            observationId = observationId,
            accountIdentityDigest = accountIdentityDigest,
            walletScope = DEFAULT_MANAGED_WALLET_SCOPE,
            scopeFingerprint = scopeFingerprint(symbols),
            scopeSymbols = symbols,
            observedAt = balances.responseEndedAt,
            balanceRequestStartedAt = balances.requestStartedAt,
            balanceResponseEndedAt = balances.responseEndedAt,
            priceRequestStartedAt = prices.requestStartedAt,
            priceResponseEndedAt = prices.responseEndedAt,
            persistedAt = null,
            status = if (complete) ActualObservationStatus.COMPLETE else ActualObservationStatus.INCOMPLETE,
            totalUsd = assets.takeIf { complete }?.fold(BigDecimal.ZERO) { total, asset ->
                total.add(checkNotNull(asset.valueUsd))
            },
            incompleteReasons = reasons,
            assets = assets,
        ).also {
            require(accountIdentityDigest.matches(Regex("[0-9a-f]{64}"))) {
                "Actual observations require a verified account identity digest."
            }
            require(it.scopeSymbols.isNotEmpty()) { "Actual observation scope cannot be empty." }
            require(now >= it.observedAt) { "Actual observation timestamps cannot be in the future." }
        }
    }

    private fun isValidTimeWindow(balances: DirectBalanceCapture, prices: DirectTickerCapture): Boolean =
        balances.requestStartedAt <= balances.responseEndedAt &&
            prices.requestStartedAt <= prices.responseEndedAt && balances.resultShapeValid && prices.resultShapeValid

    private fun gapBetween(balances: DirectBalanceCapture, prices: DirectTickerCapture): Duration = when {
        balances.responseEndedAt.isBefore(prices.requestStartedAt) ->
            Duration.between(balances.responseEndedAt, prices.requestStartedAt)

        prices.responseEndedAt.isBefore(balances.requestStartedAt) ->
            Duration.between(prices.responseEndedAt, balances.requestStartedAt)

        else -> Duration.ZERO
    }

    private fun reasonFor(
        status: ActualAssetStatus,
        windowReason: String?,
        balanceStatus: DirectEvidenceStatus,
        priceStatus: DirectEvidenceStatus?,
    ): String? = when (status) {
        ActualAssetStatus.COMPLETE -> null
        ActualAssetStatus.INVALID_TIME_WINDOW -> windowReason
        ActualAssetStatus.MISSING_BALANCE -> "No balance key for configured asset."
        ActualAssetStatus.AMBIGUOUS_BALANCE -> "Multiple Kraken balance keys map to this asset."
        ActualAssetStatus.INVALID_BALANCE -> "Balance amount is invalid or negative ($balanceStatus)."
        ActualAssetStatus.MISSING_PRICE -> "No USD ticker mark for configured asset."
        ActualAssetStatus.INVALID_PRICE -> "USD ticker mark is invalid ($priceStatus)."
    }

    private fun sha256(value: String): String = java.security.MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }
}
