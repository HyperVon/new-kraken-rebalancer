package com.gemini.krakenbot.model;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
public class PortfolioSnapshot {
    private Instant timestamp;
    private BigDecimal totalValueUSD;
    private Map<String, AssetSnapshot> assets;
    private List<String> actions;
    private BigDecimal drawdownPercent;
    private BigDecimal fiatDeploymentPercent;
    private BigDecimal effectiveUsdTargetPercent;

    @Getter
    @Setter
    @AllArgsConstructor
    @NoArgsConstructor
    public static class AssetSnapshot {
        private String symbol;
        private BigDecimal balance;
        private BigDecimal price;
        private BigDecimal valueUSD;
        private BigDecimal targetPercent;
        private BigDecimal currentPercent;
        private BigDecimal deviationPercent;
        private BigDecimal deviationUSD;
    }
}
