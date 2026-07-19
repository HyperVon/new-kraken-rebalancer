package com.gemini.krakenbot.model

import java.math.BigDecimal

data class PortfolioStats(
    val allTimeHigh: BigDecimal = BigDecimal.ZERO
)
