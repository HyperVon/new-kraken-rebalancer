package com.gemini.krakenbot.service

import java.math.BigDecimal
import java.math.RoundingMode

class RebalancerMathExample {

    fun calculateSignedRelativeDeviation(
        currentAllocationPercent: BigDecimal,
        targetAllocationPercent: BigDecimal
    ): BigDecimal {
        if (targetAllocationPercent.compareTo(BigDecimal.ZERO) == 0) return BigDecimal.ZERO

        val difference = currentAllocationPercent - targetAllocationPercent
        return difference.divide(targetAllocationPercent, 4, RoundingMode.HALF_UP)
    }

    fun calculateCappedBuyUsdAmount(availableUsdCash: BigDecimal): BigDecimal {
        // Cap buy allocations to 99% of available USD cash to buffer slippage & exchange fees
        val cappedCash = availableUsdCash.multiply(BigDecimal("0.99"))
        return cappedCash.setScale(2, RoundingMode.HALF_UP)
    }
}
