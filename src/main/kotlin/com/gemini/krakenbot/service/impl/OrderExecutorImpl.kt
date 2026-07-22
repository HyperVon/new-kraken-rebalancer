package com.gemini.krakenbot.service.impl

import com.gemini.krakenbot.config.Settings
import com.gemini.krakenbot.model.Asset
import com.gemini.krakenbot.model.OrderResult
import com.gemini.krakenbot.model.OrderSide
import com.gemini.krakenbot.model.OrderType
import com.gemini.krakenbot.model.TradeRecord
import com.gemini.krakenbot.service.AssetPrices
import com.gemini.krakenbot.service.AssetValues
import com.gemini.krakenbot.service.KrakenService
import com.gemini.krakenbot.service.OrderExecutor
import com.gemini.krakenbot.service.PortfolioAnalyzer
import com.gemini.krakenbot.service.RebalanceOrders
import com.gemini.krakenbot.service.TradeHistoryService
import com.gemini.krakenbot.util.PrecisionConstants
import com.gemini.krakenbot.util.CASH_RESERVE_FACTOR
import com.gemini.krakenbot.util.FEE_RATE_ESTIMATE
import com.gemini.krakenbot.util.HUNDRED
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.last
import org.slf4j.LoggerFactory
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import kotlin.time.Duration.Companion.milliseconds

class OrderExecutorImpl(
    private val krakenService: KrakenService,
    private val portfolioAnalyzer: PortfolioAnalyzer,
    private val tradeHistoryService: TradeHistoryService
) : OrderExecutor {
    private val log = LoggerFactory.getLogger(OrderExecutorImpl::class.java)

    companion object {
        val CASH_RESERVE_FACTOR: BigDecimal = PrecisionConstants.CASH_RESERVE_FACTOR
        const val MAX_REFRESH_ATTEMPTS = 3
        const val REFRESH_DELAY_MS = 250L
        val FEE_RATE_ESTIMATE: BigDecimal = PrecisionConstants.FEE_RATE_ESTIMATE
    }

    override suspend fun executeOrders(
        buyOrders: RebalanceOrders,
        sellOrders: RebalanceOrders,
        currentValuesUSD: AssetValues,
        prices: AssetPrices,
        settings: Settings,
        actionLog: MutableList<String>
    ) {
        var projectedCash = currentValuesUSD[Asset.USD] ?: BigDecimal.ZERO
        var executedSells = false

        for ((symbol, usdToSell) in sellOrders) {
            if (usdToSell < BigDecimal.valueOf(settings.dustThresholdUSD)) {
                log.info("Skipping dust sell for {} ($ {})", symbol, usdToSell)
                actionLog.add("Skipping dust sell for $symbol ($$usdToSell)")
                continue
            }

            val result = executeSingleOrder(symbol, usdToSell, OrderSide.SELL, prices, actionLog)
            if (result?.success == true) {
                projectedCash = projectedCash.add(usdToSell)
                executedSells = true
            }
        }

        var actualCash = projectedCash
        if (executedSells && !settings.dryRun) {
            actualCash = refreshUsdBalanceAfterSells(projectedCash)
        }

        for ((symbol, originalCost) in buyOrders) {
            var cost = originalCost
            if (cost > actualCash) {
                log.warn(
                    "Not enough cash to buy {}. Cost: {}, Cash: {}. Reducing.",
                    symbol,
                    cost,
                    actualCash
                )
                cost = actualCash.multiply(CASH_RESERVE_FACTOR)
            }

            if (cost < BigDecimal.valueOf(settings.dustThresholdUSD)) {
                log.info("Skipping dust buy for {} ($ {})", symbol, cost)
                actionLog.add("Skipping dust buy for $symbol ($$cost)")
                continue
            }

            val result = executeSingleOrder(symbol, cost, OrderSide.BUY, prices, actionLog)
            if (result?.success == true) {
                actualCash = actualCash.subtract(cost)
            }
        }
    }

    private suspend fun executeSingleOrder(
        symbol: String,
        usdAmount: BigDecimal,
        side: OrderSide,
        prices: AssetPrices,
        actionLog: MutableList<String>
    ): OrderResult? {
        val price = prices[symbol] ?: BigDecimal.ZERO
        if (price.signum() == 0) return null

        val volume = usdAmount.divide(price, PrecisionConstants.SCALE_CRYPTO, RoundingMode.HALF_UP)
        val pair = Asset.tradingPair(symbol)
        val result = krakenService.executeOrder(
            pair = pair,
            type = OrderType.MARKET.apiValue,
            side = side.apiValue,
            volume = volume
        )
        logOrderResult(
            result = result,
            actionLog = actionLog,
            symbol = symbol,
            volume = volume,
            usdAmount = usdAmount,
            side = side.uppercaseName
        )
        recordTrade(result, symbol, pair, side.uppercaseName, volume, usdAmount, prices)
        return result
    }

    private suspend fun refreshUsdBalanceAfterSells(projectedCash: BigDecimal): BigDecimal {
        return pollUsdBalanceAfterSells(projectedCash).last()
    }

    private fun pollUsdBalanceAfterSells(
        projectedCash: BigDecimal,
        targetThreshold: BigDecimal = projectedCash.multiply(BigDecimal("0.95"))
    ): Flow<BigDecimal> = flow {
        var lastBalance = projectedCash
        var backoffMs = REFRESH_DELAY_MS
        val maxAttempts = MAX_REFRESH_ATTEMPTS

        repeat(maxAttempts) { attempt ->
            delay(backoffMs.milliseconds)
            try {
                val updatedBalances = krakenService.getBalances()
                if (updatedBalances.isNotEmpty()) {
                    val usdBalance = portfolioAnalyzer.resolveBalance(Asset.USD, updatedBalances)
                    if (usdBalance > BigDecimal.ZERO) {
                        lastBalance = usdBalance
                        log.info("Updated USD balance after sells (attempt {}): $${lastBalance}", attempt + 1)
                        emit(lastBalance)
                        if (lastBalance >= targetThreshold) return@flow
                    }
                }
            } catch (e: Exception) {
                log.warn("Balance poll failed (attempt {})", attempt + 1, e)
            }
            backoffMs = (backoffMs * 2).coerceAtMost(32000L)
        }
        emit(lastBalance)
        log.warn("Using best observed USD balance after sell refresh: $${lastBalance}")
    }

    internal fun logOrderResult(
        result: OrderResult,
        actionLog: MutableList<String>,
        symbol: String,
        volume: BigDecimal,
        usdAmount: BigDecimal,
        side: String
    ) {
        if (result.success) {
            val prefix = if (result.dryRun) "[DRY RUN] " else ""
            if (side == OrderSide.SELL.uppercaseName) {
                actionLog.add("${prefix}SELL $symbol Volume: $volume Value: $$usdAmount")
            } else {
                actionLog.add("${prefix}BUY $symbol Volume: $volume Cost: $$usdAmount")
            }
        } else {
            actionLog.add("FAILED $side $symbol: ${result.errorMessage}")
        }
    }

    internal fun recordTrade(
        result: OrderResult,
        symbol: String,
        pair: String,
        side: String,
        volume: BigDecimal,
        usdAmount: BigDecimal,
        prices: AssetPrices
    ) {
        val expectedPrice = prices[symbol] ?: BigDecimal.ZERO
        val executedPrice = when {
            volume.signum() > 0 -> usdAmount.divide(volume, PrecisionConstants.SCALE_CRYPTO, RoundingMode.HALF_UP)
            else -> BigDecimal.ZERO
        }
        val slippage = when {
            expectedPrice.signum() > 0 -> {
                val diff =
                    when (side) {
                        OrderSide.BUY.uppercaseName -> executedPrice.subtract(expectedPrice)
                        else -> expectedPrice.subtract(
                            executedPrice
                        )
                    }
                diff.divide(expectedPrice, PrecisionConstants.SCALE_PERCENT, RoundingMode.HALF_UP)
                    .multiply(PrecisionConstants.HUNDRED)
            }
            else -> {
                BigDecimal.ZERO
            }
        }
        val estimatedFee =
            usdAmount
                .multiply(FEE_RATE_ESTIMATE)
                .setScale(PrecisionConstants.SCALE_FEE, RoundingMode.HALF_UP)

        val trade = TradeRecord(
            timestamp = Instant.now(),
            pair = pair,
            side = side,
            symbol = symbol,
            volume = volume,
            usdAmount = usdAmount,
            success = result.success,
            dryRun = result.dryRun,
            errorMessage = result.errorMessage,
            price = executedPrice,
            fee = estimatedFee,
            slippagePercent = slippage
        )
        tradeHistoryService.saveTrade(trade)
    }
}
