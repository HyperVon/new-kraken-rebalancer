package com.gemini.krakenbot.service.history

import com.gemini.krakenbot.config.AppConfig
import com.gemini.krakenbot.domain.RawBalances
import com.gemini.krakenbot.model.Asset
import com.gemini.krakenbot.model.KrakenAssetMetadata
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

    fun fullAccountScope(accountIdentityDigest: String): HistorySourceScope {
        val symbols = listOf(HISTORY_FULL_ACCOUNT_SCOPE_SYMBOL)
        return HistorySourceScope(
            mode = HistorySourceMode.ACCOUNT,
            managedScopeFingerprint = sha256("$HISTORY_ACCOUNT_INTRADAY_SCOPE\u0000${symbols.single()}"),
            managedScopeSymbols = symbols,
            accountIdentityDigest = accountIdentityDigest,
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
                "The combined balance and ticker request window exceeds " +
                    "$MAX_HISTORY_OBSERVATION_WINDOW_SECONDS seconds."

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

    /** Values every supported account balance partition without inheriting the configured asset set. */
    fun buildFullAccountDirect(
        observationId: String,
        cycleId: String,
        accountIdentityDigest: String,
        balances: DirectBalanceCapture,
        prices: DirectTickerCapture,
        assetMetadata: List<KrakenAssetMetadata>,
        authoritativeAssetClasses: Map<String, String> = emptyMap(),
        sampleSelection: HistorySampleSelection = HistorySampleSelection.FINAL,
        now: Instant = Instant.now(),
    ): HistoryObservation {
        val sourceScope = fullAccountScope(accountIdentityDigest)
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
        val maxWindow = Duration.ofSeconds(MAX_HISTORY_OBSERVATION_WINDOW_SECONDS)
        val maxGap = Duration.ofSeconds(MAX_HISTORY_BALANCE_PRICE_GAP_SECONDS)
        val windowValid = timestampsOrdered && balances.resultShapeValid && prices.resultShapeValid &&
            observationWindow?.let { it <= maxWindow } == true && gap <= maxGap
        val windowReason = when {
            !timestampsOrdered -> "Balance or ticker request timestamps are invalid."

            !balances.resultShapeValid || !prices.resultShapeValid -> "Balance or ticker response data is invalid."

            observationWindow?.let { it > maxWindow } == true ->
                "The combined balance and ticker request window exceeds " +
                    "$MAX_HISTORY_OBSERVATION_WINDOW_SECONDS seconds."

            gap > maxGap ->
                "Balance and ticker responses are more than $MAX_HISTORY_BALANCE_PRICE_GAP_SECONDS seconds apart."

            else -> null
        }
        val metadataByRawKey = assetMetadata.groupBy { it.assetId.trim().uppercase(Locale.ROOT) }
        val metadataBySymbol = assetMetadata.groupBy { canonicalLedgerAsset(it.assetId) }
        val assets = balances.valuesByAssetId.keys
            .groupBy(::canonicalLedgerAsset)
            .toSortedMap()
            .mapNotNull { (symbol, rawKeys) ->
                val assetClass = resolveAssetClass(
                    rawKeys = rawKeys,
                    symbol = symbol,
                    metadataByRawKey = metadataByRawKey,
                    metadataBySymbol = metadataBySymbol,
                    authoritativeAssetClasses = authoritativeAssetClasses,
                )
                if (assetClass.value == EQUITY_ASSET_CLASS) return@mapNotNull null

                val evidence = rawKeys.map { rawKey -> rawKey to balances.valuesByAssetId.getValue(rawKey) }
                val rawValues = evidence.associate { (key, value) -> key to value.rawValue }
                val quantityValid = balances.resultShapeValid && evidence.all { (_, value) ->
                    value.status == DirectEvidenceStatus.VALID && value.value != null && value.value.signum() >= 0
                }
                val quantity = if (quantityValid) {
                    evidence.fold(BigDecimal.ZERO) { total, (_, value) -> total.add(checkNotNull(value.value)) }
                } else {
                    null
                }
                val isUsd = symbol == Asset.USD
                val mark = prices.marksBySymbol[symbol]
                val priceUsd = when {
                    isUsd -> BigDecimal.ONE
                    quantity?.signum() == 0 -> null
                    else -> mark?.price
                }
                val assetClassSupported = assetClass.value in SUPPORTED_ACCOUNT_ASSET_CLASSES
                val priceInvalid = mark?.status != DirectEvidenceStatus.VALID || priceUsd == null ||
                    priceUsd.signum() <= 0
                val status = when {
                    !windowValid -> HistoryAssetValueStatus.INVALID_TIME_WINDOW

                    !quantityValid || quantity == null -> HistoryAssetValueStatus.INVALID_BALANCE

                    assetClass.reason != null -> HistoryAssetValueStatus.UNAVAILABLE_CYCLE_EVIDENCE

                    !assetClassSupported -> HistoryAssetValueStatus.UNAVAILABLE_CYCLE_EVIDENCE

                    !isUsd && quantity.signum() != 0 && (mark == null || mark.status == DirectEvidenceStatus.MISSING) ->
                        HistoryAssetValueStatus.MISSING_PRICE

                    !isUsd && quantity.signum() != 0 && priceInvalid -> HistoryAssetValueStatus.INVALID_PRICE

                    else -> HistoryAssetValueStatus.COMPLETE
                }
                val reason = when {
                    !windowValid -> windowReason

                    !quantityValid || quantity == null -> "Balance amount is invalid or negative."

                    assetClass.reason != null -> assetClass.reason

                    !assetClassSupported -> "Kraken does not classify this balance as a supported spot asset."

                    status == HistoryAssetValueStatus.MISSING_PRICE -> "No direct USD ticker price is available."

                    status == HistoryAssetValueStatus.INVALID_PRICE ->
                        "Direct USD ticker price is invalid or ambiguous."

                    else -> null
                }
                val value = if (status == HistoryAssetValueStatus.COMPLETE) {
                    checkNotNull(quantity).multiply(if (isUsd) BigDecimal.ONE else priceUsd ?: BigDecimal.ZERO)
                } else {
                    null
                }
                HistoryAssetValue(
                    symbol = symbol,
                    status = status,
                    rawBalanceKeys = rawKeys.sorted(),
                    rawBalanceValues = rawValues,
                    quantity = quantity,
                    requestedPair = if (isUsd) "USD" else mark?.requestedPair,
                    responsePair = if (isUsd) "USD" else mark?.responsePair,
                    candidateResponsePairs = if (isUsd) emptyList() else mark?.candidateResponsePairs.orEmpty(),
                    candidateRawPrices = if (isUsd) {
                        emptyMap()
                    } else {
                        mark?.candidateValuesByPair.orEmpty()
                            .mapValues { (_, value) -> value.rawValue }
                    },
                    rawPrice = if (isUsd) "1" else mark?.rawPrice,
                    priceUsd = if (status == HistoryAssetValueStatus.COMPLETE && isUsd) BigDecimal.ONE else priceUsd,
                    valueUsd = value,
                    reason = reason,
                )
            }
        val reasons = assets.filter { it.status != HistoryAssetValueStatus.COMPLETE }
            .map { asset -> "${asset.symbol}: ${asset.reason ?: asset.status.name}" }
        val complete = windowValid && reasons.isEmpty()
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
            totalUsd = if (complete) {
                assets.fold(BigDecimal.ZERO) { total, asset -> total.add(checkNotNull(asset.valueUsd)) }
            } else {
                null
            },
            incompleteReasons = reasons.ifEmpty {
                if (complete) emptyList() else listOf(windowReason ?: "Full-account evidence is incomplete.")
            },
            assets = assets,
        )
    }

    /** Returns positive, supported non-USD account assets that still need a direct ticker read. */
    fun fullAccountTickerSymbols(
        balances: DirectBalanceCapture,
        prices: DirectTickerCapture?,
        assetMetadata: List<KrakenAssetMetadata>,
        authoritativeAssetClasses: Map<String, String> = emptyMap(),
    ): List<String> {
        if (!balances.resultShapeValid) return emptyList()
        val metadataByRawKey = assetMetadata.groupBy { it.assetId.trim().uppercase(Locale.ROOT) }
        val metadataBySymbol = assetMetadata.groupBy { canonicalLedgerAsset(it.assetId) }
        return balances.valuesByAssetId.keys
            .groupBy(::canonicalLedgerAsset)
            .filter { (symbol, rawKeys) ->
                symbol != Asset.USD && rawKeys.all { key ->
                    val value = balances.valuesByAssetId.getValue(key)
                    value.status == DirectEvidenceStatus.VALID && value.value != null && value.value.signum() >= 0
                } && rawKeys.fold(BigDecimal.ZERO) { total, key ->
                    total.add(checkNotNull(balances.valuesByAssetId.getValue(key).value))
                }.signum() > 0 && resolveAssetClass(
                    rawKeys = rawKeys,
                    symbol = symbol,
                    metadataByRawKey = metadataByRawKey,
                    metadataBySymbol = metadataBySymbol,
                    authoritativeAssetClasses = authoritativeAssetClasses,
                ).value in SUPPORTED_ACCOUNT_ASSET_CLASSES
            }
            .keys
            .filter { symbol ->
                prices?.marksBySymbol?.get(symbol)?.let { mark ->
                    mark.status == DirectEvidenceStatus.VALID && mark.price?.signum() == 1
                } != true
            }
            .sorted()
    }

    /** Builds an explicitly incomplete full-account observation when pricing evidence could not be completed. */
    fun buildIncompleteFullAccountDirect(
        observationId: String,
        cycleId: String,
        accountIdentityDigest: String,
        balances: DirectBalanceCapture,
        reason: String,
        authoritativeAssetClasses: Map<String, String> = emptyMap(),
        sampleSelection: HistorySampleSelection = HistorySampleSelection.FINAL,
        now: Instant = Instant.now(),
    ): HistoryObservation {
        val sourceScope = fullAccountScope(accountIdentityDigest)
        val assets = balances.valuesByAssetId.keys
            .groupBy(::canonicalLedgerAsset)
            .toSortedMap()
            .mapNotNull { (symbol, rawKeys) ->
                val isEquity = rawKeys.any { key ->
                    authoritativeAssetClasses[key.trim().uppercase(Locale.ROOT)] == EQUITY_ASSET_CLASS
                } || authoritativeAssetClasses[symbol.trim().uppercase(Locale.ROOT)] == EQUITY_ASSET_CLASS
                if (isEquity) return@mapNotNull null

                val evidence = rawKeys.map { rawKey -> rawKey to balances.valuesByAssetId.getValue(rawKey) }
                val quantityValid = balances.resultShapeValid && evidence.all { (_, value) ->
                    value.status == DirectEvidenceStatus.VALID && value.value != null && value.value.signum() >= 0
                }
                val quantity = if (quantityValid) {
                    evidence.fold(BigDecimal.ZERO) { total, (_, value) -> total.add(checkNotNull(value.value)) }
                } else {
                    null
                }
                val isUsd = symbol == Asset.USD
                val status = when {
                    !quantityValid || quantity == null -> HistoryAssetValueStatus.INVALID_BALANCE
                    isUsd -> HistoryAssetValueStatus.COMPLETE
                    else -> HistoryAssetValueStatus.UNAVAILABLE_CYCLE_EVIDENCE
                }
                HistoryAssetValue(
                    symbol = symbol,
                    status = status,
                    rawBalanceKeys = rawKeys.sorted(),
                    rawBalanceValues = evidence.associate { (k, v) -> k to v.rawValue },
                    quantity = quantity,
                    requestedPair = if (isUsd) "USD" else null,
                    responsePair = if (isUsd) "USD" else null,
                    rawPrice = if (isUsd) "1" else null,
                    priceUsd = if (isUsd) BigDecimal.ONE else null,
                    valueUsd = if (isUsd && quantityValid) quantity else null,
                    reason = when {
                        !quantityValid || quantity == null -> "Balance amount is invalid or negative."
                        isUsd -> null
                        else -> reason
                    },
                )
            }
        val observedAt = balances.responseEndedAt
        require(now >= observedAt) { "History observation timestamps cannot be in the future." }
        return HistoryObservation(
            observationId = observationId,
            cycleId = cycleId,
            sourceScope = sourceScope,
            sampleSelection = sampleSelection,
            observedAt = observedAt,
            balanceRequestStartedAt = balances.requestStartedAt,
            balanceResponseEndedAt = balances.responseEndedAt,
            priceRequestStartedAt = observedAt,
            priceResponseEndedAt = observedAt,
            status = HistoryObservationStatus.INCOMPLETE,
            totalUsd = null,
            incompleteReasons = listOf(reason),
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

    private fun resolveAssetClass(
        rawKeys: List<String>,
        symbol: String,
        metadataByRawKey: Map<String, List<KrakenAssetMetadata>>,
        metadataBySymbol: Map<String, List<KrakenAssetMetadata>>,
        authoritativeAssetClasses: Map<String, String> = emptyMap(),
    ): AssetClassResolution {
        val resolvedClasses = mutableSetOf<String>()
        rawKeys.forEach { rawKey ->
            val normalizedRawKey = rawKey.trim().uppercase(Locale.ROOT)
            val candidates = metadataByRawKey[normalizedRawKey]
                .takeUnless { it.isNullOrEmpty() }
                ?: metadataBySymbol[symbol].orEmpty()

            val publicClass = if (candidates.isNotEmpty()) {
                val classes = candidates.map { it.assetClass.trim().lowercase(Locale.ROOT) }.toSet()
                if (classes.size != 1) {
                    return AssetClassResolution(null, "Kraken asset metadata is ambiguous for this balance.")
                }
                classes.single()
            } else {
                null
            }

            val ledgerClass = authoritativeAssetClasses[normalizedRawKey]?.trim()?.lowercase(Locale.ROOT)
                ?: authoritativeAssetClasses[symbol.trim().uppercase(Locale.ROOT)]?.trim()?.lowercase(Locale.ROOT)

            if (ledgerClass == "ambiguous") {
                return AssetClassResolution(
                    null,
                    "Kraken account ledger history reports conflicting asset classes for this balance.",
                )
            }

            val resolved = when {
                publicClass != null && ledgerClass != null -> {
                    if (publicClass != ledgerClass) {
                        return AssetClassResolution(
                            null,
                            "Kraken asset metadata conflicts with account ledger classification.",
                        )
                    }
                    publicClass
                }

                publicClass != null -> publicClass

                ledgerClass != null -> ledgerClass

                else -> return AssetClassResolution(null, "Kraken asset metadata is unavailable for this balance.")
            }
            resolvedClasses += resolved
        }
        return if (resolvedClasses.size == 1) {
            AssetClassResolution(resolvedClasses.single(), null)
        } else {
            AssetClassResolution(null, "Kraken asset metadata conflicts across balance partitions.")
        }
    }

    private fun canonicalLedgerAsset(rawAssetKey: String): String =
        Asset.normalizeLedgerAsset(rawAssetKey).uppercase(Locale.ROOT)

    private data class AssetClassResolution(val value: String?, val reason: String?)

    private const val EQUITY_ASSET_CLASS = "equity"
    private val SUPPORTED_ACCOUNT_ASSET_CLASSES = setOf("currency", "tokenized_asset")

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
