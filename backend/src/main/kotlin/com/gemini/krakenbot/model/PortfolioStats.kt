package com.gemini.krakenbot.model

import java.math.BigDecimal

data class PortfolioStats(
    val allTimeHigh: BigDecimal = BigDecimal.ZERO,
    /**
     * Most recent drawdown computed against a trusted ATH. Preserved across
     * cycles whose balance observation postdates ledger coverage
     * ([AthUpdateResult.Deferred]) so the dashboard and snapshots keep
     * showing the last trustworthy value instead of a misleading one.
     */
    val lastTrustedDrawdownPct: BigDecimal? = null,
)
