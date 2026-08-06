package com.gemini.krakenbot.util

import com.gemini.krakenbot.model.OrderResult
import com.gemini.krakenbot.model.OrderSide
import com.gemini.krakenbot.model.TradeRecord
import com.gemini.krakenbot.model.TradeSource
import com.gemini.krakenbot.service.AssetPrices
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant

/** Local trade economics at placement time (slippage, fee estimate, executed price). */
object TradeCalculator {
    fun calculateExecutedPrice(usdAmount: BigDecimal, volume: BigDecimal): BigDecimal = if (volume.isPositive) {
        usdAmount.divide(volume, PrecisionConstants.SCALE_CRYPTO, RoundingMode.HALF_UP)
    } else {
        BigDecimal.ZERO
    }

    /**
     * Adverse slippage as a positive percent: buy pays above expected, sell fills below expected.
     * Favorable fills are negative.
     */
    fun calculateSlippage(side: String, executedPrice: BigDecimal, expectedPrice: BigDecimal): BigDecimal {
        if (expectedPrice.isZero) return BigDecimal.ZERO

        val diff =
            when {
                OrderSide.isBuy(side) -> executedPrice.subtract(expectedPrice)
                OrderSide.isSell(side) -> expectedPrice.subtract(executedPrice)
                else -> return BigDecimal.ZERO
            }

        return diff
            .multiply(PrecisionConstants.HUNDRED)
            .divide(expectedPrice, PrecisionConstants.SCALE_PERCENT, RoundingMode.HALF_UP)
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
        cycleId: String? = null,
    ): TradeRecord {
        val expectedPrice = prices[symbol] ?: BigDecimal.ZERO
        val executedPrice = calculateExecutedPrice(usdAmount, volume)
        val slippage = calculateSlippage(side, executedPrice, expectedPrice)
        val estimatedFee = estimateFee(usdAmount)

        // LOCAL_ESTIMATE: planning-time price/fee, not a Kraken fill (API_FILL comes from sync).
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
            cycleId = cycleId,
            orderTxid = result.orderTxid,
        )
    }
}
