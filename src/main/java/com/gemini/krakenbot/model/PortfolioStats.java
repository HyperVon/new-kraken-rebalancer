package com.gemini.krakenbot.model;

import java.math.BigDecimal;

public class PortfolioStats {
    private BigDecimal allTimeHigh;

    public PortfolioStats() {
    }

    public PortfolioStats(BigDecimal allTimeHigh) {
        this.allTimeHigh = allTimeHigh;
    }

    public BigDecimal getAllTimeHigh() {
        return allTimeHigh;
    }

    public void setAllTimeHigh(BigDecimal allTimeHigh) {
        this.allTimeHigh = allTimeHigh;
    }
}
