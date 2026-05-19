package com.gemini.krakenbot.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

public class PortfolioSnapshot {
    private Instant timestamp;
    private BigDecimal totalValueUSD;
    private Map<String, AssetSnapshot> assets;
    private List<String> actions;
    private BigDecimal drawdownPercent;
    private BigDecimal fiatDeploymentPercent;
    private BigDecimal effectiveUsdTargetPercent;

    public PortfolioSnapshot() {
    }

    public PortfolioSnapshot(Instant timestamp, BigDecimal totalValueUSD, Map<String, AssetSnapshot> assets,
            List<String> actions, BigDecimal drawdownPercent, BigDecimal fiatDeploymentPercent,
            BigDecimal effectiveUsdTargetPercent) {
        this.timestamp = timestamp;
        this.totalValueUSD = totalValueUSD;
        this.assets = assets;
        this.actions = actions;
        this.drawdownPercent = drawdownPercent;
        this.fiatDeploymentPercent = fiatDeploymentPercent;
        this.effectiveUsdTargetPercent = effectiveUsdTargetPercent;
    }

    public Instant getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(Instant timestamp) {
        this.timestamp = timestamp;
    }

    public BigDecimal getTotalValueUSD() {
        return totalValueUSD;
    }

    public void setTotalValueUSD(BigDecimal totalValueUSD) {
        this.totalValueUSD = totalValueUSD;
    }

    public Map<String, AssetSnapshot> getAssets() {
        return assets;
    }

    public void setAssets(Map<String, AssetSnapshot> assets) {
        this.assets = assets;
    }

    public List<String> getActions() {
        return actions;
    }

    public void setActions(List<String> actions) {
        this.actions = actions;
    }

    public BigDecimal getDrawdownPercent() {
        return drawdownPercent;
    }

    public void setDrawdownPercent(BigDecimal drawdownPercent) {
        this.drawdownPercent = drawdownPercent;
    }

    public BigDecimal getFiatDeploymentPercent() {
        return fiatDeploymentPercent;
    }

    public void setFiatDeploymentPercent(BigDecimal fiatDeploymentPercent) {
        this.fiatDeploymentPercent = fiatDeploymentPercent;
    }

    public BigDecimal getEffectiveUsdTargetPercent() {
        return effectiveUsdTargetPercent;
    }

    public static class AssetSnapshot {
        private String symbol;
        private BigDecimal balance;
        private BigDecimal price;
        private BigDecimal valueUSD;
        private BigDecimal targetPercent;
        private BigDecimal currentPercent;
        private BigDecimal deviationPercent;
        private BigDecimal deviationUSD;

        public AssetSnapshot() {
        }

        public AssetSnapshot(String symbol, BigDecimal balance, BigDecimal price, BigDecimal valueUSD,
                BigDecimal targetPercent, BigDecimal currentPercent, BigDecimal deviationPercent,
                BigDecimal deviationUSD) {
            this.symbol = symbol;
            this.balance = balance;
            this.price = price;
            this.valueUSD = valueUSD;
            this.targetPercent = targetPercent;
            this.currentPercent = currentPercent;
            this.deviationPercent = deviationPercent;
            this.deviationUSD = deviationUSD;
        }

        public String getSymbol() {
            return symbol;
        }

        public void setSymbol(String symbol) {
            this.symbol = symbol;
        }

        public BigDecimal getBalance() {
            return balance;
        }

        public void setBalance(BigDecimal balance) {
            this.balance = balance;
        }

        public BigDecimal getPrice() {
            return price;
        }

        public void setPrice(BigDecimal price) {
            this.price = price;
        }

        public BigDecimal getValueUSD() {
            return valueUSD;
        }

        public void setValueUSD(BigDecimal valueUSD) {
            this.valueUSD = valueUSD;
        }

        public BigDecimal getTargetPercent() {
            return targetPercent;
        }

        public void setTargetPercent(BigDecimal targetPercent) {
            this.targetPercent = targetPercent;
        }

        public BigDecimal getCurrentPercent() {
            return currentPercent;
        }

        public void setCurrentPercent(BigDecimal currentPercent) {
            this.currentPercent = currentPercent;
        }

        public BigDecimal getDeviationPercent() {
            return deviationPercent;
        }

        public void setDeviationPercent(BigDecimal deviationPercent) {
            this.deviationPercent = deviationPercent;
        }

        public BigDecimal getDeviationUSD() {
            return deviationUSD;
        }

        public void setDeviationUSD(BigDecimal deviationUSD) {
            this.deviationUSD = deviationUSD;
        }
    }
}
