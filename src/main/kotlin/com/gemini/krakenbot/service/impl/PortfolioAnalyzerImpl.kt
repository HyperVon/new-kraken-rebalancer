package com.gemini.krakenbot.service.impl

import com.gemini.krakenbot.config.Settings
import com.gemini.krakenbot.model.Asset
import com.gemini.krakenbot.model.Result
import com.gemini.krakenbot.repository.PortfolioStatsRepository
import com.gemini.krakenbot.service.AnalysisResult
import com.gemini.krakenbot.service.AssetDeviations
import com.gemini.krakenbot.service.AssetPrices
import com.gemini.krakenbot.service.AssetValues
import com.gemini.krakenbot.service.ConfigService
import com.gemini.krakenbot.service.KrakenService
import com.gemini.krakenbot.service.MutableRebalanceOrders
import com.gemini.krakenbot.service.PortfolioAnalyzer
import com.gemini.krakenbot.service.PortfolioValues
import com.gemini.krakenbot.service.RawBalances
import com.gemini.krakenbot.service.RawPrices
import com.gemini.krakenbot.service.impl.PortfolioCalculations.HUNDRED
import com.gemini.krakenbot.service.impl.PortfolioCalculations.SCALE_PERCENT
import com.gemini.krakenbot.service.impl.PortfolioCalculations.SCALE_PRICE
import com.gemini.krakenbot.service.impl.PortfolioCalculations.SCALE_USD
import com.gemini.krakenbot.util.ActionLogFormatter
import com.gemini.krakenbot.util.toUsdScale
import com.gemini.krakenbot.view.util.ViewText
import org.slf4j.LoggerFactory
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.pow
import com.gemini.krakenbot.util.resolveBalance as resolveBalanceFromKeys

class PortfolioAnalyzerImpl(
    private val krakenService: KrakenService,
    private val configService: ConfigService,
    private val portfolioStatsRepository: PortfolioStatsRepository,
) : PortfolioAnalyzer {
    private val log = LoggerFactory.getLogger(PortfolioAnalyzerImpl::class.java)

    override suspend fun fetchBalances(): RawBalances {
        val balances = krakenService.getBalances()
        log.info("Available Balance Keys: {}", balances.keys)
        return balances
    }

    override suspend fun fetchPrices(): AssetPrices {
        val allocations = configService.getConfig().allocations
        val nonUsd = allocations.filter { !it.symbol.isUsd }
        if (nonUsd.isEmpty()) return emptyMap()

        val pairs =
            nonUsd.joinToString(",") {
                it.symbol.tradingPair
            }
        val rawPrices = krakenService.getTickerPrices(pairs)

        return nonUsd.associate { (symbol, _) ->
            symbol.value to
                resolvePriceFromTicker(
                    symbol.value,
                    rawPrices,
                )
        }
    }

    override fun resolvePriceFromTicker(symbol: String, rawPrices: RawPrices): BigDecimal {
        val expectedPair = Asset.tradingPair(symbol)
        rawPrices[expectedPair]?.let { return it }

        // Exact pair-alias match only (CQ-1-11 / #69) — never substring contains().
        for ((key, value) in rawPrices) {
            if (Asset.matchesUsdQuotedPair(key, symbol)) {
                return value
            }
        }
        return BigDecimal.ZERO
    }

    override fun calculatePortfolioValues(balances: RawBalances, prices: AssetPrices): Result<PortfolioValues> {
        val currentValuesUSD = mutableMapOf<String, BigDecimal>()
        var totalPortfolioValueUSD = BigDecimal.ZERO

        for ((asset, _) in configService.getConfig().allocations) {
            val symbol = asset.value
            val balance = resolveBalance(symbol, balances)
            var price = BigDecimal.ONE

            if (!asset.isUsd) {
                val p = prices[symbol] ?: BigDecimal.ZERO
                if (p.signum() == 0) {
                    log.error(
                        "Price not found for {}. Aborting rebalance cycle to prevent erroneous trades.",
                        symbol,
                    )
                    return Result.Failure(
                        IllegalStateException("${ViewText.PRICE_NOT_FOUND_PREFIX}$symbol"),
                    )
                }
                price = p
            }

            val rawValUSD = balance.multiply(price)
            // Per-asset values stay USD-scaled for order sizing; accumulate raw then round once.
            currentValuesUSD[symbol] = rawValUSD.setScale(SCALE_USD, RoundingMode.HALF_UP)
            totalPortfolioValueUSD = totalPortfolioValueUSD.add(rawValUSD)
        }

        return Result.Success(
            PortfolioValues(
                totalPortfolioValueUSD.setScale(SCALE_USD, RoundingMode.HALF_UP),
                currentValuesUSD,
            ),
        )
    }

    override fun resolveBalance(symbol: String, balances: RawBalances): BigDecimal =
        resolveBalanceFromKeys(symbol, balances)

    override suspend fun updateAthAndCalculateDrawdown(totalPortfolioValueUSD: BigDecimal): BigDecimal {
        val stats = portfolioStatsRepository.load()
        var ath = stats.allTimeHigh

        when {
            ath <= BigDecimal.ZERO -> {
                ath = totalPortfolioValueUSD
                log.info(
                    "Initial ATH set to {}",
                    ath.setScale(SCALE_USD, RoundingMode.HALF_UP),
                )
            }

            totalPortfolioValueUSD > ath -> {
                ath = totalPortfolioValueUSD
                log.info(
                    "New All-Time High detected: {}",
                    ath.setScale(SCALE_USD, RoundingMode.HALF_UP),
                )
            }
        }
        val updatedStats = stats.copy(allTimeHigh = ath)
        runCatching { portfolioStatsRepository.save(updatedStats) }
            .onFailure { e -> log.error("Failed to persist portfolio ATH", e) }

        return if (ath > BigDecimal.ZERO && totalPortfolioValueUSD < ath) {
            val diff = ath.subtract(totalPortfolioValueUSD)
            diff
                .divide(
                    ath,
                    SCALE_PERCENT,
                    RoundingMode.HALF_UP,
                ).multiply(HUNDRED)
        } else {
            BigDecimal.ZERO
        }
    }

    override fun calculateFiatDeployment(drawdownPct: BigDecimal, settings: Settings): BigDecimal {
        if (settings.fiatMaxDrawdown <= 0.0) return BigDecimal.ZERO

        val maxDD = BigDecimal.valueOf(settings.fiatMaxDrawdown)
        var ratio =
            drawdownPct.divide(
                maxDD,
                SCALE_PERCENT,
                RoundingMode.HALF_UP,
            )
        ratio = ratio.coerceAtMost(BigDecimal.ONE)

        // Deploy% = (DD / MaxDD)^exponent × 100; ratio already capped at 1 so Deploy% ≤ 100.
        // Fractional exponents require Double.pow; re-enter BigDecimal immediately and scale.
        val deployDouble =
            ratio.toDouble().pow(settings.fiatDeploymentExponent) * 100.0
        return BigDecimal
            .valueOf(deployDouble)
            .setScale(SCALE_PERCENT, RoundingMode.HALF_UP)
    }

    override fun calculateEffectiveUsdTarget(fiatDeploymentPct: BigDecimal): BigDecimal {
        val baseUsdTarget =
            configService
                .getConfig()
                .allocations
                .filter { it.symbol.isUsd }
                .sumOf { it.targetPercent.toBigDecimal() }

        // Shrink configured USD target by Deploy% so cash is freed for crypto on drawdowns.
        return if (fiatDeploymentPct > BigDecimal.ZERO) {
            val factor =
                BigDecimal.ONE.subtract(
                    fiatDeploymentPct.divide(HUNDRED, SCALE_PERCENT, RoundingMode.HALF_UP),
                )
            baseUsdTarget.multiply(factor)
        } else {
            baseUsdTarget
        }
    }

    override fun calculateCryptoScaleFactor(effectiveUsdTarget: BigDecimal): BigDecimal {
        val totalNonUsdTarget =
            configService
                .getConfig()
                .allocations
                .filter { !it.symbol.isUsd }
                .sumOf { it.targetPercent.toBigDecimal() }

        // Scale every crypto base target so (100 − effectiveUsd) is split in the same proportions.
        val remainingForCrypto = HUNDRED.subtract(effectiveUsdTarget)
        return if (totalNonUsdTarget > BigDecimal.ZERO) {
            remainingForCrypto.divide(
                totalNonUsdTarget,
                SCALE_PRICE,
                RoundingMode.HALF_UP,
            )
        } else {
            BigDecimal.ONE
        }
    }

    override fun analyzeDeviations(
        totalPortfolioValueUSD: BigDecimal,
        currentValuesUSD: AssetValues,
        effectiveUsdTarget: BigDecimal,
        cryptoScaleFactor: BigDecimal,
    ): AnalysisResult {
        val buyOrders = mutableMapOf<String, BigDecimal>()
        val sellOrders = mutableMapOf<String, BigDecimal>()
        val actionLog = mutableListOf<String>()

        val config = configService.getConfig()
        val s = config.settings
        var usdTriggered = false
        var usdDeviationAmount = BigDecimal.ZERO
        val allDeviations = mutableMapOf<String, BigDecimal>()

        config.allocations.forEach { (symbol, targetPercent) ->
            val symbolVal = symbol.value
            val currentVal = currentValuesUSD[symbolVal] ?: BigDecimal.ZERO

            val metrics =
                PortfolioCalculations.calculateAssetMetrics(
                    symbol = symbol,
                    baseTargetPercent = BigDecimal.valueOf(targetPercent),
                    currentValueUSD = currentVal,
                    totalPortfolioValueUSD = totalPortfolioValueUSD,
                    effectiveUsdTarget = effectiveUsdTarget,
                    cryptoScaleFactor = cryptoScaleFactor,
                    dustThresholdUSD = s.dustThresholdUSD,
                )

            allDeviations[symbolVal] = metrics.deviationUSD

            log.info(
                "Analysis [{}]: Dev: {}% ($ {}). Threshold: {}%",
                symbolVal,
                metrics.deviationPercent,
                metrics.deviationUSD.setScale(SCALE_USD, RoundingMode.HALF_UP),
                s.deviationTriggerPercent,
            )

            // Both gates required: |Dev%| ≥ trigger and |DevUSD| ≥ dust (isSignificant).
            val triggerThreshold = BigDecimal.valueOf(s.deviationTriggerPercent)
            val isTriggered =
                metrics.deviationPercent.abs() >= triggerThreshold && metrics.isSignificant

            if (isTriggered) {
                actionLog.add(
                    ActionLogFormatter.formatDeviationTrigger(symbolVal, metrics.deviationPercent),
                )
            }

            if (symbol.isUsd) {
                // USD never becomes a buy/sell row; only flags a fiat-correction candidate.
                if (isTriggered) {
                    log.info(
                        "Asset USD Deviation: {}% (Trigger: {}%). USD Dev: {}",
                        metrics.deviationPercent,
                        s.deviationTriggerPercent,
                        metrics.deviationUSD,
                    )
                    usdTriggered = true
                    usdDeviationAmount = metrics.deviationUSD
                }
            } else {
                if (isTriggered) {
                    log.info(
                        "Asset {} Deviation: {}% (Trigger: {}%). USD Dev: {}",
                        symbolVal,
                        metrics.deviationPercent,
                        s.deviationTriggerPercent,
                        metrics.deviationUSD,
                    )

                    // Overweight (positive DevUSD) → sell excess; underweight → buy deficit.
                    if (metrics.deviationUSD > BigDecimal.ZERO) {
                        sellOrders[symbolVal] = metrics.deviationUSD
                    } else {
                        buyOrders[symbolVal] = metrics.deviationUSD.abs()
                    }
                }
            }
        }

        // Fiat correction only when USD alone triggered (deposit/withdrawal); skip if crypto
        // already produced orders so we do not double-spend the same cash move.
        if (buyOrders.isEmpty() && sellOrders.isEmpty() && usdTriggered) {
            log.info(
                "USD Deviation triggered but no individual asset triggers. " +
                    "Enforcing fiat correction.",
            )
            actionLog.add(ActionLogFormatter.formatFiatCorrectionEnforced())
            distributeFiatCorrection(
                usdDev = usdDeviationAmount,
                allDevs = allDeviations,
                buyOrders = buyOrders,
                sellOrders = sellOrders,
                actionLog = actionLog,
            )
        }

        return AnalysisResult(buyOrders, sellOrders, actionLog)
    }

    override fun distributeFiatCorrection(
        usdDev: BigDecimal,
        allDevs: AssetDeviations,
        buyOrders: MutableRebalanceOrders,
        sellOrders: MutableRebalanceOrders,
        actionLog: MutableList<String>,
    ) {
        val deviationAbs = usdDev.abs()
        // Positive USD DevUSD = surplus cash (deposit) → buy underweights; negative = shortage → sell overweights.
        val isDeposit = usdDev > BigDecimal.ZERO
        var totalCounterDev = BigDecimal.ZERO
        val candidates = mutableListOf<String>()

        for ((symbol, d) in allDevs) {
            if (symbol.equals(Asset.USD, ignoreCase = true)) continue

            if (isDeposit && d < BigDecimal.ZERO) {
                candidates.add(symbol)
                totalCounterDev = totalCounterDev.add(d.abs())
            } else if (!isDeposit && d > BigDecimal.ZERO) {
                candidates.add(symbol)
                totalCounterDev = totalCounterDev.add(d)
            }
        }

        if (totalCounterDev.signum() == 0) {
            log.info(
                "Fiat correction required but no suitable " +
                    "counter-balancing assets found.",
            )
            return
        }

        log.info(
            "Distributing Fiat Correction ($${
                deviationAbs.setScale(
                    SCALE_USD,
                    RoundingMode.HALF_UP,
                )
            }) among ${candidates.size} candidates. Total Counter-Dev: $${
                totalCounterDev.setScale(
                    SCALE_USD,
                    RoundingMode.HALF_UP,
                )
            }",
        )
        actionLog.add(ActionLogFormatter.formatFiatCorrectionDistribution(deviationAbs, candidates.size))

        // Truncate rather than round the budget so the shares can never sum above the
        // deviation we are actually correcting (CQ-3-26 / #76).
        var remaining = deviationAbs.setScale(SCALE_USD, RoundingMode.DOWN)

        for (symbol in candidates) {
            val assetDev = allDevs.getValue(symbol).abs()
            val ratio =
                assetDev.divide(
                    totalCounterDev,
                    SCALE_PRICE,
                    RoundingMode.HALF_UP,
                )
            val share = deviationAbs.multiply(ratio).toUsdScale().min(remaining)

            // A tiny counter-deviation rounds to $0.00, and HALF_UP shares can collectively
            // exceed the budget; either way there is nothing left to trade for this symbol.
            if (share.signum() <= 0) continue
            remaining = remaining.subtract(share)

            if (isDeposit) {
                buyOrders[symbol] = share
            } else {
                sellOrders[symbol] = share
            }
        }
    }
}
