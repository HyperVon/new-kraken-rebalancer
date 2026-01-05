package com.gemini.krakenbot.service;

import com.gemini.krakenbot.config.Allocation;
import com.gemini.krakenbot.config.Settings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Service
public class PortfolioManager {

    private static final Logger log = LoggerFactory.getLogger(PortfolioManager.class);
    private final KrakenService krakenService;
    private final ConfigService configService;

    public PortfolioManager(KrakenService krakenService, ConfigService configService) {
        this.krakenService = krakenService;
        this.configService = configService;
    }

    public void startRebalancingLoop() {
        Settings settings = configService.getConfig().settings();
        log.info("Starting Rebalancing Loop. Interval: {}s, DryRun: {}", settings.loopDelaySeconds(),
                settings.dryRun());

        while (true) {
            try {
                performRebalanceCycle();
                TimeUnit.SECONDS.sleep(settings.loopDelaySeconds());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("Rebalancing loop interrupted.");
                break;
            } catch (Exception e) {
                log.error("Error in rebalancing cycle", e);
                // Don't crash the loop on API errors, just wait and retry
                try {
                    TimeUnit.SECONDS.sleep(10);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    private void performRebalanceCycle() {
        log.info("--- Starting Snapshot Phase ---");
        // 1. Snapshot
        Map<String, Double> balances = krakenService.getBalances();
        log.info("Available Balance Keys: {}", balances.keySet());

        // Filter for Allocations to know what to fetch
        // Note: Kraken balances keys might differ from symbols (e.g. ZUSD vs USD).
        // For simplicity, we assume mapping is handled or keys match decently.
        // Or we map "USD" -> "ZUSD" / "USDT", etc.
        // Let's assume standard Kraken keys: XXBT, ZUSD, or the user config uses Kraken
        // Balance keys as symbols.

        // Prepare symbols for Ticker
        StringBuilder pairs = new StringBuilder();
        for (Allocation a : configService.getConfig().allocations()) {
            if (!a.symbol().equalsIgnoreCase("USD")) {
                if (pairs.length() > 0)
                    pairs.append(",");
                pairs.append(mapToKrakenTicker(a.symbol())).append("USD");
            }
        }

        Map<String, Double> prices = krakenService.getTickerPrices(pairs.toString());

        BigDecimal totalPortfolioValueUSD = BigDecimal.ZERO;
        Map<String, BigDecimal> currentValuesUSD = new HashMap<>();

        // Calculate Holdings Value
        for (Allocation a : configService.getConfig().allocations()) {
            String symbol = a.symbol();
            // Try to find balance directly or with prefix/suffix quirks if needed.
            // Simplified: User config symbol MUST match Kraken Balance Key roughly.
            // Actually, Kraken Balance Keys are strange (XXBT, ZUSD).
            // We'll rely on a fuzzy match or exact match if user config is good.
            // Let's assume valid mapping for now or try standard keys.

            Double balance = balances.getOrDefault(symbol,
                    balances.getOrDefault("X" + symbol, // Common Kraken quirk
                            balances.getOrDefault("Z" + symbol, // Common Fiat quirk
                                    balances.getOrDefault(mapToKrakenTicker(symbol),
                                            balances.getOrDefault("X" + mapToKrakenTicker(symbol), 0.0)))));

            BigDecimal bal = BigDecimal.valueOf(balance);
            BigDecimal price = BigDecimal.ONE; // default for USD

            if (!symbol.equalsIgnoreCase("USD")) {
                BigDecimal p = getCurrentPrice(symbol, prices);
                if (p.compareTo(BigDecimal.ZERO) == 0) {
                    log.error("Price not found for {}", symbol);
                    continue; // Skip this asset or abort? Abort safer.
                }
                price = p;
            }

            BigDecimal val = bal.multiply(price);
            currentValuesUSD.put(symbol, val);
            totalPortfolioValueUSD = totalPortfolioValueUSD.add(val);
        }

        log.info("Total Portfolio Value: ${}", totalPortfolioValueUSD.setScale(2, RoundingMode.HALF_UP));

        // 2. Analysis
        Map<String, BigDecimal> buyOrders = new HashMap<>(); // Symbol -> USD Amount
        Map<String, BigDecimal> sellOrders = new HashMap<>(); // Symbol -> USD Amount (Positive)
        Map<String, BigDecimal> allDeviations = new HashMap<>(); // Symbol -> USD Deviation

        boolean usdTriggered = false;
        BigDecimal usdDeviationAmount = BigDecimal.ZERO;

        Settings s = configService.getConfig().settings();

        for (Allocation a : configService.getConfig().allocations()) {
            BigDecimal targetPct = BigDecimal.valueOf(a.targetPercent()).divide(BigDecimal.valueOf(100), 4,
                    RoundingMode.HALF_UP);
            BigDecimal targetValue = totalPortfolioValueUSD.multiply(targetPct);
            BigDecimal currentVal = currentValuesUSD.getOrDefault(a.symbol(), BigDecimal.ZERO);

            BigDecimal deviationUSD = currentVal.subtract(targetValue);
            BigDecimal deviationPct = BigDecimal.ZERO;
            if (targetValue.compareTo(BigDecimal.ZERO) > 0) {
                deviationPct = deviationUSD.abs().divide(targetValue, 4, RoundingMode.HALF_UP)
                        .multiply(BigDecimal.valueOf(100));
            }

            allDeviations.put(a.symbol(), deviationUSD);

            if (a.symbol().equalsIgnoreCase("USD")) {
                if (deviationPct.doubleValue() >= s.deviationTriggerPercent()) {
                    log.info("Asset USD Deviation: {}% (Trigger: {}%). USD Dev: {}", deviationPct,
                            s.deviationTriggerPercent(), deviationUSD);
                    usdTriggered = true;
                    usdDeviationAmount = deviationUSD;
                }
            } else {
                if (deviationPct.doubleValue() >= s.deviationTriggerPercent()) {
                    log.info("Asset {} Deviation: {}% (Trigger: {}%). USD Dev: {}", a.symbol(), deviationPct,
                            s.deviationTriggerPercent(), deviationUSD);
                    if (deviationUSD.compareTo(BigDecimal.ZERO) > 0) {
                        // Overweight -> SELL
                        sellOrders.put(a.symbol(), deviationUSD);
                    } else {
                        // Underweight -> BUY
                        buyOrders.put(a.symbol(), deviationUSD.abs());
                    }
                }
            }
        }

        // Special Case: USD Triggered but no Crypto Triggered
        // We need to correct the USD imbalance by trading the most off-balance assets
        if (buyOrders.isEmpty() && sellOrders.isEmpty() && usdTriggered) {
            log.info("USD Deviation triggered but no individual asset triggers. Enforcing fiat correction.");
            distributeFiatCorrection(usdDeviationAmount, allDeviations, buyOrders, sellOrders);
        }

        // 3. Execution
        BigDecimal currentUsdBal = currentValuesUSD.getOrDefault("USD", BigDecimal.ZERO);
        BigDecimal projectedCash = currentUsdBal;

        // Execute SELLS
        for (Map.Entry<String, BigDecimal> entry : sellOrders.entrySet()) {
            String symbol = entry.getKey();
            BigDecimal usdToSell = entry.getValue();

            // Calculate volume to sell: USD / Price
            BigDecimal price = getCurrentPrice(symbol, prices);
            if (price.compareTo(BigDecimal.ZERO) == 0)
                continue;

            BigDecimal volume = usdToSell.divide(price, 8, RoundingMode.HALF_UP);

            // Execute
            krakenService.executeOrder(symbol + "USD", "market", "sell", volume.doubleValue());
            projectedCash = projectedCash.add(usdToSell); // Assume fill at current price
        }

        // Execute BUYS
        for (Map.Entry<String, BigDecimal> entry : buyOrders.entrySet()) {
            String symbol = entry.getKey();
            BigDecimal cost = entry.getValue();

            if (cost.compareTo(projectedCash) > 0) {
                log.warn("Not enough cash to buy {}. Cost: {}, Cash: {}. Reducing.", symbol, cost, projectedCash);
                cost = projectedCash.multiply(BigDecimal.valueOf(0.99)); // Safety buffer
            }

            if (cost.compareTo(BigDecimal.valueOf(1.0)) < 0) { // Min order check roughly
                log.info("Skipping dust buy for {} (${})", symbol, cost);
                continue;
            }

            BigDecimal price = getCurrentPrice(symbol, prices);
            if (price.compareTo(BigDecimal.ZERO) == 0)
                continue;

            BigDecimal volume = cost.divide(price, 8, RoundingMode.HALF_UP);
            krakenService.executeOrder(symbol + "USD", "market", "buy", volume.doubleValue());
            projectedCash = projectedCash.subtract(cost);
        }

        log.info("--- Cycle Complete ---");
    }

    private void distributeFiatCorrection(BigDecimal usdDev, Map<String, BigDecimal> allDevs,
            Map<String, BigDecimal> buyOrders, Map<String, BigDecimal> sellOrders) {

        // Logic: specific handling for isolated USD deviation (e.g. Deposit/Withdrawal
        // or Drift)
        // If USD is high (positive dev), we BUY assets.
        // If USD is low (negative dev), we SELL assets.
        // Amount per asset = AssetTarget% / 100 * Abs(Deviation)

        BigDecimal deviationAbs = usdDev.abs();
        boolean isDeposit = usdDev.compareTo(BigDecimal.ZERO) > 0; // Surplus USD -> Buy

        for (Allocation a : configService.getConfig().allocations()) {
            if (a.symbol().equalsIgnoreCase("USD"))
                continue;

            // Math: targetPercent is e.g. 50. div(100) -> 0.5. mul(deviation)
            BigDecimal share = BigDecimal.valueOf(a.targetPercent())
                    .divide(BigDecimal.valueOf(100), 4, RoundingMode.HALF_UP)
                    .multiply(deviationAbs);

            if (isDeposit) {
                // USD Excess -> BUY
                buyOrders.put(a.symbol(), share);
            } else {
                // USD Shortage -> SELL
                sellOrders.put(a.symbol(), share);
            }
        }
    }

    private String mapToKrakenTicker(String symbol) {
        if ("BTC".equalsIgnoreCase(symbol))
            return "XBT";
        if ("DOGE".equalsIgnoreCase(symbol))
            return "XDG";
        return symbol;
    }

    private BigDecimal getCurrentPrice(String symbol, Map<String, Double> prices) {
        String krakenSymbol = mapToKrakenTicker(symbol);
        for (String k : prices.keySet()) {
            // Kraken keys usually conform to something like XXBTZUSD, XETHZUSD, SOLUSD
            // We look for the symbol code and "USD"
            if (k.contains(krakenSymbol) && k.contains("USD")) {
                return BigDecimal.valueOf(prices.get(k));
            }
        }
        return BigDecimal.ZERO;
    }
}
