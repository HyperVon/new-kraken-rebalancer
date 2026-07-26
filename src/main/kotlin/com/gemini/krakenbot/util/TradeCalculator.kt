package com.gemini.krakenbot.util

import com.gemini.krakenbot.model.OrderResult
import com.gemini.krakenbot.model.OrderSide
import com.gemini.krakenbot.model.TradeRecord
import com.gemini.krakenbot.model.TradeSource
import com.gemini.krakenbot.service.AssetPrices
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant

/**
 * Utility functions for calculating trade execution metrics (slippage, fees, executed prices).
 */
object TradeCalculator {
    fun calculateExecutedPrice(usdAmount: BigDecimal, volume: BigDecimal): BigDecimal = if (volume.isPositive) {
        usdAmount.divide(volume, PrecisionConstants.SCALE_CRYPTO, RoundingMode.HALF_UP)
    } else {
        BigDecimal.ZERO
    }

    fun calculateSlippage(side: String, executedPrice: BigDecimal, expectedPrice: BigDecimal): BigDecimal {
        if (expectedPrice.isZero) return BigDecimal.ZERO

        val diff =
            when {
                OrderSide.isBuy(side) -> executedPrice.subtract(expectedPrice)
                OrderSide.isSell(side) -> expectedPrice.subtract(executedPrice)
                else -> return BigDecimal.ZERO
            }

        return diff
            .divide(expectedPrice, PrecisionConstants.SCALE_PERCENT, RoundingMode.HALF_UP)
            .multiply(PrecisionConstants.HUNDRED)
    }

    fun estimateFee(usdAmount: BigDecimal): BigDecimal = usdAmount
        .multiply(PrecisionConstants.FEE_RATE_ESTIMATE)
        .setScale(PrecisionConstants.SCALE_FEE, RoundingMode.HALF_UP)

    fun createTradeRecord(
        result: OrderResult,
        symbol: String,
        pair: String,
        side: String,
        volume: BigDecimal,
        usdAmount: BigDecimal,
        prices: AssetPrices,
        timestamp: Instant = Instant.now(),
    ): TradeRecord {
        val expectedPrice = prices[symbol] ?: BigDecimal.ZERO
        val executedPrice = calculateExecutedPrice(usdAmount, volume)
        val slippage = calculateSlippage(side, executedPrice, expectedPrice)
        val estimatedFee = estimateFee(usdAmount)

        return TradeRecord(
            timestamp = timestamp,
            pair = pair,
            side = OrderSide.normalize(side),
            symbol = symbol,
            volume = volume,
            usdAmount = usdAmount,
            success = result.success,
            dryRun = result.dryRun,
            errorMessage = result.errorMessage,
            price = executedPrice,
            fee = estimatedFee,
            slippagePercent = slippage,
            expectedPrice = expectedPrice,
            source = TradeSource.LOCAL_ESTIMATE,
        )
    }
}
