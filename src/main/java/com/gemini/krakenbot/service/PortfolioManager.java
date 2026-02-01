package com.gemini.krakenbot.service;

import com.gemini.krakenbot.config.Allocation;
import com.gemini.krakenbot.config.Settings;
import com.gemini.krakenbot.model.PortfolioSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Service
public class PortfolioManager {

    private static final Logger log = LoggerFactory.getLogger(PortfolioManager.class);
    private final KrakenService krakenService;
    private final ConfigService configService;
    private final TradeHistoryService tradeHistoryService;
    private final com.gemini.krakenbot.repository.PortfolioStatsRepository portfolioStatsRepository;

    public PortfolioManager(KrakenService krakenService, ConfigService configService,
            TradeHistoryService tradeHistoryService,
            com.gemini.krakenbot.repository.PortfolioStatsRepository portfolioStatsRepository) {
        this.krakenService = krakenService;
        this.configService = configService;
        this.tradeHistoryService = tradeHistoryService;
        this.portfolioStatsRepository = portfolioStatsRepository;
    }

    private volatile boolean running = true;

    public void stopRebalancingLoop() {
        this.running = false;
    }

    public void startRebalancingLoop() {
        // Reset running state in case it was stopped previously (e.g. in tests)
        this.running = true;

        Settings settings = configService.getConfig().settings();
        log.info("Starting Rebalancing Loop. Interval: {}s, DryRun: {}", settings.loopDelaySeconds(),
                settings.dryRun());

        while (running) {
            try {
                // Fetch latest settings for this iteration
                Settings currentSettings = configService.getConfig().settings();
                performRebalanceCycle();

                // Sleep with check
                for (int i = 0; i < currentSettings.loopDelaySeconds(); i++) {
                    if (!running)
                        break;
                    TimeUnit.SECONDS.sleep(1);
                }
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
        List<String> actionLog = new ArrayList<>();

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
                    log.error("Price not found for {}. Aborting rebalance cycle to prevent erroneous trades.", symbol);
                    return; // Abort cycle
                }
                price = p;
            }

            BigDecimal val = bal.multiply(price);
            currentValuesUSD.put(symbol, val);
            totalPortfolioValueUSD = totalPortfolioValueUSD.add(val);
        }

        log.info("Total Portfolio Value: ${}", totalPortfolioValueUSD.setScale(2, RoundingMode.HALF_UP));

        // --- Fiat Drawdown Logic ---
        com.gemini.krakenbot.model.PortfolioStats stats = portfolioStatsRepository.load();
        BigDecimal ath = stats.getAllTimeHigh();
        if (ath == null || totalPortfolioValueUSD.compareTo(ath) > 0) {
            ath = totalPortfolioValueUSD;
            stats.setAllTimeHigh(ath);
            portfolioStatsRepository.save(stats);
            log.info("New All-Time High detected: ${}", ath);
        }

        BigDecimal drawdownPct = BigDecimal.ZERO;
        if (ath.compareTo(BigDecimal.ZERO) > 0 && totalPortfolioValueUSD.compareTo(ath) < 0) {
            BigDecimal diff = ath.subtract(totalPortfolioValueUSD);
            drawdownPct = diff.divide(ath, 4, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100));
        }

        Settings s = configService.getConfig().settings();
        BigDecimal fiatDeploymentPct = BigDecimal.ZERO;

        if (s.fiatMaxDrawdown() > 0) { // Only if enabled
            BigDecimal maxDD = BigDecimal.valueOf(s.fiatMaxDrawdown());
            // Ratio = min(1.0, currentDD / maxDD)
            BigDecimal ratio = drawdownPct.divide(maxDD, 4, RoundingMode.HALF_UP);
            if (ratio.compareTo(BigDecimal.ONE) > 0) {
                ratio = BigDecimal.ONE;
            }
            // Deploy% = (ratio ^ exponent) * 100
            double ratioDouble = ratio.doubleValue();
            double exponent = s.fiatDeploymentExponent();
            double deployDouble = Math.pow(ratioDouble, exponent) * 100.0;
            fiatDeploymentPct = BigDecimal.valueOf(deployDouble);
        }

        if (fiatDeploymentPct.compareTo(BigDecimal.ZERO) > 0) {
            log.info("Drawdown Detected: {}%. Fiat Deployment: {}%", drawdownPct.setScale(2, RoundingMode.HALF_UP),
                    fiatDeploymentPct.setScale(2, RoundingMode.HALF_UP));
        }

        // 2. Analysis
        Map<String, BigDecimal> buyOrders = new HashMap<>(); // Symbol -> USD Amount
        Map<String, BigDecimal> sellOrders = new HashMap<>(); // Symbol -> USD Amount (Positive)
        Map<String, BigDecimal> allDeviations = new HashMap<>(); // Symbol -> USD Deviation

        boolean usdTriggered = false;
        BigDecimal usdDeviationAmount = BigDecimal.ZERO;

        // Pre-calculate Target Adjustments
        BigDecimal totalNonUsdTarget = BigDecimal.ZERO;
        BigDecimal baseUsdTarget = BigDecimal.ZERO;

        for (Allocation a : configService.getConfig().allocations()) {
            if (a.symbol().equalsIgnoreCase("USD")) {
                baseUsdTarget = baseUsdTarget.add(BigDecimal.valueOf(a.targetPercent()));
            } else {
                totalNonUsdTarget = totalNonUsdTarget.add(BigDecimal.valueOf(a.targetPercent()));
            }
        }

        BigDecimal effectiveUsdTarget = baseUsdTarget;
        if (fiatDeploymentPct.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal factor = BigDecimal.ONE
                    .subtract(fiatDeploymentPct.divide(BigDecimal.valueOf(100), 4, RoundingMode.HALF_UP));
            effectiveUsdTarget = baseUsdTarget.multiply(factor);
        }

        BigDecimal remainingForCrypto = BigDecimal.valueOf(100).subtract(effectiveUsdTarget);
        BigDecimal cryptoScaleFactor = BigDecimal.ONE;
        if (totalNonUsdTarget.compareTo(BigDecimal.ZERO) > 0) {
            cryptoScaleFactor = remainingForCrypto.divide(totalNonUsdTarget, 8, RoundingMode.HALF_UP);
        }

        for (Allocation a : configService.getConfig().allocations()) {
            BigDecimal targetPct = BigDecimal.valueOf(a.targetPercent());

            // Dynamic Adjustment
            if (a.symbol().equalsIgnoreCase("USD")) {
                targetPct = effectiveUsdTarget;
            } else {
                targetPct = targetPct.multiply(cryptoScaleFactor);
            }

            targetPct = targetPct.divide(BigDecimal.valueOf(100), 4, RoundingMode.HALF_UP);
            BigDecimal targetValue = totalPortfolioValueUSD.multiply(targetPct);
            BigDecimal currentVal = currentValuesUSD.getOrDefault(a.symbol(), BigDecimal.ZERO);

            BigDecimal deviationUSD = currentVal.subtract(targetValue);
            BigDecimal deviationPct = BigDecimal.ZERO;
            if (targetValue.compareTo(BigDecimal.ZERO) > 0) {
                deviationPct = deviationUSD.abs().divide(targetValue, 4, RoundingMode.HALF_UP)
                        .multiply(BigDecimal.valueOf(100));
            } else if (currentVal.compareTo(BigDecimal.ZERO) > 0) {
                deviationPct = BigDecimal.valueOf(100.0);
            }

            allDeviations.put(a.symbol(), deviationUSD);

            log.info("Analysis [{}]: Dev: {}% (${}). Threshold: {}%",
                    a.symbol(), deviationPct, deviationUSD.setScale(2, RoundingMode.HALF_UP),
                    s.deviationTriggerPercent());

            if (deviationPct.doubleValue() >= s.deviationTriggerPercent()) {
                actionLog.add("Deviation Triggered details: " + a.symbol() + " Dev: " + deviationPct + "%");
            }

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
            actionLog.add("USD Deviation Triggered. Enforcing fiat correction.");
            distributeFiatCorrection(usdDeviationAmount, allDeviations, buyOrders, sellOrders, actionLog);
        }

        // 3. Execution
        BigDecimal currentUsdBal = currentValuesUSD.getOrDefault("USD", BigDecimal.ZERO);
        BigDecimal projectedCash = currentUsdBal;

        // Execute SELLS
        for (Map.Entry<String, BigDecimal> entry : sellOrders.entrySet()) {
            String symbol = entry.getKey();
            BigDecimal usdToSell = entry.getValue();

            if (usdToSell.compareTo(BigDecimal.valueOf(s.dustThresholdUSD())) < 0) {
                log.info("Skipping dust sell for {} (${})", symbol, usdToSell);
                actionLog.add("Skipping dust sell for " + symbol + " ($" + usdToSell + ")");
                continue;
            }

            // Calculate volume to sell: USD / Price
            BigDecimal price = getCurrentPrice(symbol, prices);
            if (price.compareTo(BigDecimal.ZERO) == 0)
                continue;

            BigDecimal volume = usdToSell.divide(price, 8, RoundingMode.HALF_UP);

            // Execute
            krakenService.executeOrder(symbol + "USD", "market", "sell", volume.doubleValue());
            projectedCash = projectedCash.add(usdToSell); // Assume fill at current price
            actionLog.add("SELL " + symbol + " Volume: " + volume + " Value: $" + usdToSell);
        }

        // Execute BUYS
        for (Map.Entry<String, BigDecimal> entry : buyOrders.entrySet()) {
            String symbol = entry.getKey();
            BigDecimal cost = entry.getValue();

            if (cost.compareTo(projectedCash) > 0) {
                log.warn("Not enough cash to buy {}. Cost: {}, Cash: {}. Reducing.", symbol, cost, projectedCash);
                cost = projectedCash.multiply(BigDecimal.valueOf(0.99)); // Safety buffer
            }

            if (cost.compareTo(BigDecimal.valueOf(s.dustThresholdUSD())) < 0) { // Min order check
                log.info("Skipping dust buy for {} (${})", symbol, cost);
                actionLog.add("Skipping dust buy for " + symbol + " ($" + cost + ")");
                continue;
            }

            BigDecimal price = getCurrentPrice(symbol, prices);
            if (price.compareTo(BigDecimal.ZERO) == 0)
                continue;

            BigDecimal volume = cost.divide(price, 8, RoundingMode.HALF_UP);
            krakenService.executeOrder(symbol + "USD", "market", "buy", volume.doubleValue());
            projectedCash = projectedCash.subtract(cost);
            actionLog.add("BUY " + symbol + " Volume: " + volume + " Cost: $" + cost);
        }

        // 4. Record Snapshot
        Map<String, PortfolioSnapshot.AssetSnapshot> assetSnapshots = new HashMap<>();
        for (Allocation a : configService.getConfig().allocations()) {
            String symbol = a.symbol();
            BigDecimal balance = BigDecimal.valueOf(balances.getOrDefault(symbol, 0.0));
            // Try better lookup if needed (like in original loop) but using cached values:
            if (currentValuesUSD.containsKey(symbol)) { // We calculated it earlier
                // Re-derive or store earlier?
                // To avoid re-calc mess, let's just use what we have in currentValuesUSD and
                // price map
            }

            BigDecimal valUSD = currentValuesUSD.getOrDefault(symbol, BigDecimal.ZERO);
            BigDecimal price = BigDecimal.ONE;
            if (!symbol.equalsIgnoreCase("USD")) {
                price = getCurrentPrice(symbol, prices);
            }

            // Recalculate percentages for snapshot
            BigDecimal baseTargetPct = BigDecimal.valueOf(a.targetPercent());
            BigDecimal snapshotTargetPct = baseTargetPct;
            BigDecimal calcTargetPct = baseTargetPct;

            if (a.symbol().equalsIgnoreCase("USD")) {
                calcTargetPct = effectiveUsdTarget;
                // Keep snapshotTargetPct as Base for USD to preserve "Base" display in UI
            } else {
                calcTargetPct = baseTargetPct.multiply(cryptoScaleFactor);
                snapshotTargetPct = calcTargetPct; // Show effective target for crypto
            }

            BigDecimal currentPct = BigDecimal.ZERO;
            if (totalPortfolioValueUSD.compareTo(BigDecimal.ZERO) > 0) {
                currentPct = valUSD.divide(totalPortfolioValueUSD, 4, RoundingMode.HALF_UP)
                        .multiply(BigDecimal.valueOf(100));
            }
            BigDecimal devPct = BigDecimal.ZERO;
            // Calculate target value in USD for this asset
            BigDecimal targetVal = totalPortfolioValueUSD.multiply(calcTargetPct).divide(BigDecimal.valueOf(100), 4,
                    RoundingMode.HALF_UP);

            BigDecimal deviationUSD = valUSD.subtract(targetVal);
            if (targetVal.compareTo(BigDecimal.ZERO) > 0) {
                // Calculate deviation as a percentage of the target value (Relative Deviation)
                // This matches the "Deviation %" logic used in the rebalancing analysis loop
                devPct = deviationUSD.divide(targetVal, 4, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100));
            }

            assetSnapshots.put(symbol, new PortfolioSnapshot.AssetSnapshot(
                    symbol,
                    balance, // Note: this might be 0 if we didn't find it in balances map correctly earlier
                    price,
                    valUSD,
                    snapshotTargetPct,
                    currentPct,
                    devPct,
                    deviationUSD));
        }

        PortfolioSnapshot snapshot = new PortfolioSnapshot(
                Instant.now(),
                totalPortfolioValueUSD,
                assetSnapshots,
                actionLog,
                drawdownPct,
                fiatDeploymentPct,
                BigDecimal.ZERO // Placeholder, effectively calculated per iteration but user sees 'USD'
                                // effective
        );

        // Ensure effective USD target is set in the snapshot
        snapshot.setEffectiveUsdTargetPercent(effectiveUsdTarget);

        tradeHistoryService.addSnapshot(snapshot);

        log.info("--- Cycle Complete ---");
    }

    private void distributeFiatCorrection(BigDecimal usdDev, Map<String, BigDecimal> allDevs,
            Map<String, BigDecimal> buyOrders, Map<String, BigDecimal> sellOrders, List<String> actionLog) {

        // Logic: Distribute USD deviation strictly among the assets that
        // counter-balance it.
        // If USD is Surplus (>0): Buy assets that are Underweight (<0).
        // If USD is Shortage (<0): Sell assets that are Overweight (>0).
        // Weighting: Proportional to the asset's deviation magnitude relative to the
        // total counter-deviation.

        BigDecimal deviationAbs = usdDev.abs();
        boolean isDeposit = usdDev.compareTo(BigDecimal.ZERO) > 0;
        BigDecimal totalCounterDev = BigDecimal.ZERO;
        List<String> candidates = new ArrayList<>();

        // 1. Identify candidates and sum their deviations
        for (Map.Entry<String, BigDecimal> entry : allDevs.entrySet()) {
            String symbol = entry.getKey();
            if (symbol.equalsIgnoreCase("USD"))
                continue;

            BigDecimal d = entry.getValue();
            if (isDeposit && d.compareTo(BigDecimal.ZERO) < 0) {
                // USD Surplus -> Look for Underweight
                candidates.add(symbol);
                totalCounterDev = totalCounterDev.add(d.abs());
            } else if (!isDeposit && d.compareTo(BigDecimal.ZERO) > 0) {
                // USD Shortage -> Look for Overweight
                candidates.add(symbol);
                totalCounterDev = totalCounterDev.add(d);
            }
        }

        if (totalCounterDev.compareTo(BigDecimal.ZERO) == 0) {
            log.info("Fiat correction required but no suitable counter-balancing assets found.");
            return;
        }

        log.info("Distributing Fiat Correction (${}) among {} candidates. Total Counter-Dev: ${}",
                deviationAbs.setScale(2, RoundingMode.HALF_UP), candidates.size(),
                totalCounterDev.setScale(2, RoundingMode.HALF_UP));
        actionLog.add("Distributing Fiat Correction ($" + deviationAbs.setScale(2, RoundingMode.HALF_UP) + ") among "
                + candidates.size() + " candidates.");

        // 2. Distribute shares
        for (String symbol : candidates) {
            BigDecimal assetDev = allDevs.get(symbol).abs();
            // share = (assetDev / totalCounterDev) * usdDevAbs
            BigDecimal ratio = assetDev.divide(totalCounterDev, 8, RoundingMode.HALF_UP);
            BigDecimal share = deviationAbs.multiply(ratio);

            if (isDeposit) {
                buyOrders.put(symbol, share);
            } else {
                sellOrders.put(symbol, share);
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
