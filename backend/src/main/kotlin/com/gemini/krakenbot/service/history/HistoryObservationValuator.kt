package com.gemini.krakenbot.service.history

import com.gemini.krakenbot.config.AppConfig
import com.gemini.krakenbot.domain.RawBalances
import com.gemini.krakenbot.model.Asset
import com.gemini.krakenbot.model.PortfolioSnapshot
import com.gemini.krakenbot.service.DirectBalanceCapture
import com.gemini.krakenbot.service.DirectEvidenceStatus
import com.gemini.krakenbot.service.DirectTickerCapture
import java.math.BigDecimal
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.util.Locale

const val MAX_HISTORY_OBSERVATION_WINDOW_SECONDS = 120L
const val MAX_HISTORY_BALANCE_PRICE_GAP_SECONDS = 60L

/** Keeps the existing direct-read classifiers while assigning evidence to the clean History scope. */
object HistoryObservationValuator {
    fun scopeSymbols(config: AppConfig): List<String> = config.allocations
        .map { Asset.normalizeLedgerAsset(it.symbol.value).uppercase(Locale.ROOT) }
        .distinct()
        .sorted()

    fun scopeFingerprint(symbols: List<String>): String = sha256(
        "history-managed-scope-v1\u0000${symbols.distinct().sorted().joinToString("\u0000")}",
    )

    fun accountScope(config: AppConfig, accountIdentityDigest: String): HistorySourceScope = scope(
        symbols = scopeSymbols(config),
        mode = HistorySourceMode.ACCOUNT,
        accountIdentityDigest = accountIdentityDigest,
    )

    fun simulationScope(config: AppConfig, simulationSessionId: String): HistorySourceScope = scope(
        symbols = scopeSymbols(config),
        mode = HistorySourceMode.SIMULATION,
        simulationSessionId = simulationSessionId,
    )

    fun scope(
        symbols: List<String>,
        mode: HistorySourceMode,
        accountIdentityDigest: String? = null,
        simulationSessionId: String? = null,
    ): HistorySourceScope {
        val normalized = symbols.map { Asset.normalizeLedgerAsset(it).uppercase(Locale.ROOT) }.distinct().sorted()
        return HistorySourceScope(
            mode = mode,
            managedScopeFingerprint = scopeFingerprint(normalized),
            managedScopeSymbols = normalized,
            accountIdentityDigest = accountIdentityDigest,
            simulationSessionId = simulationSessionId,
        )
    }

    fun buildDirect(
        observationId: String,
        cycleId: String,
        sourceScope: HistorySourceScope,
        balances: DirectBalanceCapture,
        prices: DirectTickerCapture,
        sampleSelection: HistorySampleSelection = HistorySampleSelection.FINAL,
        additionalIncompleteReasons: List<String> = emptyList(),
        now: Instant = Instant.now(),
    ): HistoryObservation {
        require(sourceScope.mode == HistorySourceMode.ACCOUNT) {
            "Direct Kraken evidence must use an authenticated account scope."
        }
        val timestampsOrdered = balances.requestStartedAt <= balances.responseEndedAt &&
            prices.requestStartedAt <= prices.responseEndedAt
        val observationWindow = if (timestampsOrdered) {
            Duration.between(
                minOf(balances.requestStartedAt, prices.requestStartedAt),
                maxOf(balances.responseEndedAt, prices.responseEndedAt),
            )
        } else {
            null
        }
        val gap = if (balances.responseEndedAt.isBefore(prices.requestStartedAt)) {
            Duration.between(balances.responseEndedAt, prices.requestStartedAt)
        } else if (prices.responseEndedAt.isBefore(balances.requestStartedAt)) {
            Duration.between(prices.responseEndedAt, balances.requestStartedAt)
        } else {
            Duration.ZERO
        }
        val maxObservationWindow = Duration.ofSeconds(MAX_HISTORY_OBSERVATION_WINDOW_SECONDS)
        val maxBalancePriceGap = Duration.ofSeconds(MAX_HISTORY_BALANCE_PRICE_GAP_SECONDS)
        val windowValid = timestampsOrdered && balances.resultShapeValid && prices.resultShapeValid &&
            observationWindow?.let { it <= maxObservationWindow } == true && gap <= maxBalancePriceGap
        val invalidWindowReason = when {
            !timestampsOrdered -> "Balance or ticker request timestamps are invalid."

            !balances.resultShapeValid || !prices.resultShapeValid -> "Balance or ticker response data is invalid."

            observationWindow?.let { it > maxObservationWindow } == true ->
                "The combined balance and ticker request window exceeds $MAX_HISTORY_OBSERVATION_WINDOW_SECONDS seconds."

            gap > maxBalancePriceGap ->
                "Balance and ticker observations are more than $MAX_HISTORY_BALANCE_PRICE_GAP_SECONDS seconds apart."

            else -> null
        }

        val assets = sourceScope.managedScopeSymbols.map { symbol ->
            val balance = balances.forConfiguredAsset(symbol)
            val price = prices.marksBySymbol[symbol]
            val isUsd = Asset.normalizeLedgerAsset(symbol).equals(Asset.USD, ignoreCase = true)
            val quantity = balance.value
            val priceUsd = if (isUsd) BigDecimal.ONE else price?.price
            val rawPrice = if (isUsd) "1" else price?.rawPrice
            val priceInvalid = price?.status != DirectEvidenceStatus.VALID || priceUsd == null || priceUsd.signum() <= 0
            val status = when {
                !windowValid -> HistoryAssetValueStatus.INVALID_TIME_WINDOW

                balance.status == DirectEvidenceStatus.MISSING -> HistoryAssetValueStatus.MISSING_BALANCE

                balance.status == DirectEvidenceStatus.AMBIGUOUS -> HistoryAssetValueStatus.AMBIGUOUS_BALANCE

                balance.status != DirectEvidenceStatus.VALID || quantity == null || quantity.signum() < 0 ->
                    HistoryAssetValueStatus.INVALID_BALANCE

                !isUsd && (price == null || price.status == DirectEvidenceStatus.MISSING) ->
                    HistoryAssetValueStatus.MISSING_PRICE

                !isUsd && priceInvalid -> HistoryAssetValueStatus.INVALID_PRICE

                else -> HistoryAssetValueStatus.COMPLETE
            }
            val value = if (status == HistoryAssetValueStatus.COMPLETE) {
                checkNotNull(quantity).multiply(checkNotNull(priceUsd))
            } else {
                null
            }
            HistoryAssetValue(
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
                    price?.candidateValuesByPair.orEmpty().mapValues { (_, evidence) -> evidence.rawValue }
                },
                rawPrice = rawPrice,
                priceUsd = priceUsd,
                valueUsd = value,
                reason = reasonFor(status, invalidWindowReason, balance.status, price?.status),
            )
        }
        val reasons = assets.filter { it.status != HistoryAssetValueStatus.COMPLETE }
            .mapNotNull { asset -> "${asset.symbol}: ${asset.reason ?: asset.status.name}" } +
            additionalIncompleteReasons
        val complete = reasons.isEmpty() && balances.resultShapeValid && prices.resultShapeValid
        val total = if (complete) {
            assets.fold(BigDecimal.ZERO) { sum, asset ->
                sum.add(checkNotNull(asset.valueUsd))
            }
        } else {
            null
        }
        val observedAt = maxOf(balances.responseEndedAt, prices.responseEndedAt)
        require(now >= observedAt) { "History observation timestamps cannot be in the future." }
        return HistoryObservation(
            observationId = observationId,
            cycleId = cycleId,
            sourceScope = sourceScope,
            sampleSelection = sampleSelection,
            observedAt = observedAt,
            balanceRequestStartedAt = balances.requestStartedAt,
            balanceResponseEndedAt = balances.responseEndedAt,
            priceRequestStartedAt = prices.requestStartedAt,
            priceResponseEndedAt = prices.responseEndedAt,
            status = if (complete) HistoryObservationStatus.COMPLETE else HistoryObservationStatus.INCOMPLETE,
            totalUsd = total,
            incompleteReasons = reasons,
            assets = assets,
        )
    }

    fun buildSimulation(
        cycleId: String,
        sourceScope: HistorySourceScope,
        snapshot: PortfolioSnapshot,
    ): HistoryObservation {
        require(sourceScope.mode == HistorySourceMode.SIMULATION)
        val assets = sourceScope.managedScopeSymbols.map { symbol ->
            val asset = snapshot.assets.values.singleOrNull { it.symbol.value.equals(symbol, ignoreCase = true) }
            if (asset == null || asset.balance.signum() < 0 || asset.price.signum() <= 0 ||
                asset.valueUSD.signum() < 0
            ) {
                HistoryAssetValue(
                    symbol = symbol,
                    status = HistoryAssetValueStatus.INVALID_BALANCE,
                    quantity = asset?.balance,
                    reason = "Simulated cycle snapshot does not contain a valid managed asset value.",
                )
            } else {
                HistoryAssetValue(
                    symbol = symbol,
                    status = HistoryAssetValueStatus.SIMULATED,
                    quantity = asset.balance,
                    requestedPair = if (Asset.normalizeLedgerAsset(symbol) == Asset.USD) "USD" else null,
                    responsePair = if (Asset.normalizeLedgerAsset(symbol) == Asset.USD) "USD" else null,
                    rawPrice = asset.price.toPlainString(),
                    priceUsd = asset.price,
                    valueUsd = asset.valueUSD,
                )
            }
        }
        val complete = assets.all { it.status == HistoryAssetValueStatus.SIMULATED }
        val observedAt = snapshot.balancesObservedAt ?: snapshot.timestamp
        return HistoryObservation(
            observationId = cycleId,
            cycleId = cycleId,
            sourceScope = sourceScope,
            balanceSource = "SIMULATED_CYCLE_STATE",
            priceSource = "SIMULATED_CYCLE_STATE",
            sampleSelection = HistorySampleSelection.FINAL,
            observedAt = observedAt,
            balanceRequestStartedAt = observedAt,
            balanceResponseEndedAt = observedAt,
            priceRequestStartedAt = observedAt,
            priceResponseEndedAt = observedAt,
            status = if (complete) HistoryObservationStatus.COMPLETE else HistoryObservationStatus.INCOMPLETE,
            totalUsd = if (complete) snapshot.totalValueUSD else null,
            incompleteReasons = if (complete) {
                emptyList()
            } else {
                listOf(
                    "Simulated cycle snapshot contains incomplete values.",
                )
            },
            assets = assets,
        )
    }

    fun buildIncompleteSimulation(
        cycleId: String,
        sourceScope: HistorySourceScope,
        observedAt: Instant,
        reason: String,
    ): HistoryObservation {
        require(sourceScope.mode == HistorySourceMode.SIMULATION)
        return HistoryObservation(
            observationId = cycleId,
            cycleId = cycleId,
            sourceScope = sourceScope,
            balanceSource = "SIMULATED_CYCLE_STATE_UNAVAILABLE",
            priceSource = "SIMULATED_CYCLE_STATE_UNAVAILABLE",
            sampleSelection = HistorySampleSelection.PRE_TRADE,
            observedAt = observedAt,
            balanceRequestStartedAt = observedAt,
            balanceResponseEndedAt = observedAt,
            priceRequestStartedAt = observedAt,
            priceResponseEndedAt = observedAt,
            status = HistoryObservationStatus.INCOMPLETE,
            totalUsd = null,
            incompleteReasons = listOf(reason),
            assets = sourceScope.managedScopeSymbols.map { symbol ->
                HistoryAssetValue(
                    symbol = symbol,
                    status = HistoryAssetValueStatus.UNAVAILABLE_CYCLE_EVIDENCE,
                    quantity = null,
                    reason = reason,
                )
            },
        )
    }

    fun directCaptureFromBalances(balances: RawBalances, observedAt: Instant) =
        com.gemini.krakenbot.service.DirectBalanceCapture(
            requestStartedAt = observedAt,
            responseEndedAt = observedAt,
            resultShapeValid = true,
            valuesByAssetId = balances.mapValues { (_, amount) ->
                com.gemini.krakenbot.service.DirectValueEvidence(
                    rawValue = amount.toPlainString(),
                    value = amount,
                    status = if (amount.signum() < 0) DirectEvidenceStatus.NEGATIVE else DirectEvidenceStatus.VALID,
                )
            },
        )

    private fun reasonFor(
        status: HistoryAssetValueStatus,
        windowReason: String?,
        balanceStatus: DirectEvidenceStatus,
        priceStatus: DirectEvidenceStatus?,
    ): String? = when (status) {
        HistoryAssetValueStatus.COMPLETE -> null
        HistoryAssetValueStatus.INVALID_TIME_WINDOW -> windowReason
        HistoryAssetValueStatus.MISSING_BALANCE -> "No balance key for configured asset."
        HistoryAssetValueStatus.AMBIGUOUS_BALANCE -> "Multiple Kraken balance keys map to this asset."
        HistoryAssetValueStatus.INVALID_BALANCE -> "Balance amount is invalid or negative ($balanceStatus)."
        HistoryAssetValueStatus.MISSING_PRICE -> "No USD ticker mark for configured asset."
        HistoryAssetValueStatus.INVALID_PRICE -> "USD ticker mark is invalid ($priceStatus)."
        HistoryAssetValueStatus.SIMULATED -> null
        HistoryAssetValueStatus.UNAVAILABLE_CYCLE_EVIDENCE -> "Cycle balance or price evidence is unavailable."
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }
}
