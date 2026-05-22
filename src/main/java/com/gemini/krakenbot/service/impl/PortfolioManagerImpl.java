package com.gemini.krakenbot.service.impl;

import com.gemini.krakenbot.service.ConfigService;
import com.gemini.krakenbot.service.KrakenService;
import com.gemini.krakenbot.service.TradeHistoryService;
import lombok.RequiredArgsConstructor;


import lombok.extern.slf4j.Slf4j;


import com.gemini.krakenbot.service.PortfolioManager;

import com.gemini.krakenbot.config.Allocation;
import com.gemini.krakenbot.config.Settings;
import com.gemini.krakenbot.model.PortfolioSnapshot;
import com.gemini.krakenbot.model.PortfolioStats;
import com.gemini.krakenbot.repository.PortfolioStatsRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.scheduling.annotation.Scheduled;

@Service
@Slf4j
@RequiredArgsConstructor
public class PortfolioManagerImpl implements PortfolioManager {

    private final KrakenService krakenService;
    private final ConfigService configService;
    private final TradeHistoryService tradeHistoryService;
    private final PortfolioStatsRepository portfolioStatsRepository;

    private volatile boolean isRunning = false;
    private final AtomicLong lastRunTime = new AtomicLong(0);

    @Override
    public synchronized void stopRebalancingLoop() {
        this.isRunning = false;
        log.info("Rebalancing loop stopped.");
    }

    @Override
    public synchronized void startRebalancingLoop() {
        this.isRunning = true;
        log.info("Rebalancing loop started.");
    }

    @Scheduled(fixedDelay = 1000)
    public void checkAndRunCycle() {
        if (!isRunning) return;

        Settings settings = configService.getConfig().settings();
        long now = System.currentTimeMillis();
        long delayMillis = settings.loopDelaySeconds() * 1000L;

        if (now - lastRunTime.get() >= delayMillis) {
            // It's time to run
            lastRunTime.set(now);
            try {
                log.info("Starting Rebalance Cycle. DryRun: {}", settings.dryRun());
                performRebalanceCycle();
            } catch (Exception e) {
                log.error("Error in rebalancing cycle", e);
            }
        }
    }

    private void performRebalanceCycle() {
        log.info("--- Starting Snapshot Phase ---");
        List<String> actionLog = new ArrayList<>();

        // 1. Snapshot — fetch balances and prices, compute USD values
        Map<String, Double> balances = fetchBalances();
        Map<String, Double> prices = fetchPrices();
        Map<String, BigDecimal> currentValuesUSD = new HashMap<>();
        BigDecimal totalPortfolioValueUSD = calculatePortfolioValues(balances, prices, currentValuesUSD);
        if (totalPortfolioValueUSD == null) {
            return; // Price lookup failed, cycle aborted
        }

        log.info("Total Portfolio Value: ${}", totalPortfolioValueUSD.setScale(2, RoundingMode.HALF_UP));

        // 2. Drawdown assessment — ATH tracking and fiat deployment
        BigDecimal drawdownPct = updateAthAndCalculateDrawdown(totalPortfolioValueUSD);
        BigDecimal fiatDeploymentPct = calculateFiatDeployment(drawdownPct);

        if (fiatDeploymentPct.compareTo(BigDecimal.ZERO) > 0) {
            log.info("Drawdown Detected: {}%. Fiat Deployment: {}%", drawdownPct.setScale(2, RoundingMode.HALF_UP),
                    fiatDeploymentPct.setScale(2, RoundingMode.HALF_UP));
        }

        // 3. Analysis — compute deviations and generate orders
        BigDecimal effectiveUsdTarget = calculateEffectiveUsdTarget(fiatDeploymentPct);
        BigDecimal cryptoScaleFactor = calculateCryptoScaleFactor(effectiveUsdTarget);

        Map<String, BigDecimal> buyOrders = new HashMap<>();
        Map<String, BigDecimal> sellOrders = new HashMap<>();
        analyzeDeviations(totalPortfolioValueUSD, currentValuesUSD, effectiveUsdTarget, cryptoScaleFactor,
                buyOrders, sellOrders, actionLog);

        // 4. Execution — sell first, then buy
        Settings s = configService.getConfig().settings();
        executeOrders(buyOrders, sellOrders, currentValuesUSD, prices, s, actionLog);

        // 5. Record snapshot
        PortfolioSnapshot snapshot = buildSnapshot(balances, prices, currentValuesUSD,
                totalPortfolioValueUSD, effectiveUsdTarget, cryptoScaleFactor,
                drawdownPct, fiatDeploymentPct, actionLog);
        tradeHistoryService.addSnapshot(snapshot);

        log.info("--- Cycle Complete ---");
    }

    // ========================================================================
    // Phase 1: Snapshot
    // ========================================================================

    private Map<String, Double> fetchBalances() {
        Map<String, Double> balances = krakenService.getBalances();
        if (balances == null) {
            balances = new HashMap<>();
        }
        log.info("Available Balance Keys: {}", balances.keySet());
        return balances;
    }

    private Map<String, Double> fetchPrices() {
        StringBuilder pairs = new StringBuilder();
        for (Allocation a : configService.getConfig().allocations()) {
            if (!a.symbol().equalsIgnoreCase("USD")) {
                if (!pairs.isEmpty())
                    pairs.append(",");
                pairs.append(mapToKrakenTicker(a.symbol())).append("USD");
            }
        }
        return krakenService.getTickerPrices(pairs.toString());
    }

    /**
     * Calculates USD values for all configured assets and returns the total portfolio value.
     * Returns null if a price lookup fails (cycle should be aborted).
     */
    private BigDecimal calculatePortfolioValues(Map<String, Double> balances, Map<String, Double> prices,
            Map<String, BigDecimal> currentValuesUSD) {
        BigDecimal totalPortfolioValueUSD = BigDecimal.ZERO;

        for (Allocation a : configService.getConfig().allocations()) {
            String symbol = a.symbol();
            Double balance = resolveBalance(symbol, balances);
            BigDecimal bal = BigDecimal.valueOf(balance);
            BigDecimal price = BigDecimal.ONE; // default for USD

            if (!symbol.equalsIgnoreCase("USD")) {
                BigDecimal p = getCurrentPrice(symbol, prices);
                if (p.compareTo(BigDecimal.ZERO) == 0) {
                    log.error("Price not found for {}. Aborting rebalance cycle to prevent erroneous trades.", symbol);
                    return null; // Abort cycle
                }
                price = p;
            }

            BigDecimal val = bal.multiply(price);
            currentValuesUSD.put(symbol, val);
            totalPortfolioValueUSD = totalPortfolioValueUSD.add(val);
        }

        return totalPortfolioValueUSD;
    }

    /**
     * Resolves a balance from Kraken's response, handling their quirky key naming
     * (e.g., XXBT for BTC, ZUSD for USD).
     */
    private Double resolveBalance(String symbol, Map<String, Double> balances) {
        return balances.getOrDefault(symbol,
                balances.getOrDefault("X" + symbol, // Common Kraken quirk
                        balances.getOrDefault("Z" + symbol, // Common Fiat quirk
                                balances.getOrDefault(mapToKrakenTicker(symbol),
                                        balances.getOrDefault("X" + mapToKrakenTicker(symbol), 0.0)))));
    }

    // ========================================================================
    // Phase 2: Drawdown Assessment
    // ========================================================================

    private BigDecimal updateAthAndCalculateDrawdown(BigDecimal totalPortfolioValueUSD) {
        PortfolioStats stats = portfolioStatsRepository.load();
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
        return drawdownPct;
    }

    private BigDecimal calculateFiatDeployment(BigDecimal drawdownPct) {
        Settings s = configService.getConfig().settings();
        if (s.fiatMaxDrawdown() <= 0) {
            return BigDecimal.ZERO;
        }

        BigDecimal maxDD = BigDecimal.valueOf(s.fiatMaxDrawdown());
        BigDecimal ratio = drawdownPct.divide(maxDD, 4, RoundingMode.HALF_UP);
        if (ratio.compareTo(BigDecimal.ONE) > 0) {
            ratio = BigDecimal.ONE;
        }

        double deployDouble = Math.pow(ratio.doubleValue(), s.fiatDeploymentExponent()) * 100.0;
        return BigDecimal.valueOf(deployDouble);
    }

    // ========================================================================
    // Phase 3: Analysis
    // ========================================================================

    private BigDecimal calculateEffectiveUsdTarget(BigDecimal fiatDeploymentPct) {
        BigDecimal baseUsdTarget = BigDecimal.ZERO;
        for (Allocation a : configService.getConfig().allocations()) {
            if (a.symbol().equalsIgnoreCase("USD")) {
                baseUsdTarget = baseUsdTarget.add(BigDecimal.valueOf(a.targetPercent()));
            }
        }

        if (fiatDeploymentPct.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal factor = BigDecimal.ONE
                    .subtract(fiatDeploymentPct.divide(BigDecimal.valueOf(100), 4, RoundingMode.HALF_UP));
            return baseUsdTarget.multiply(factor);
        }
        return baseUsdTarget;
    }

    private BigDecimal calculateCryptoScaleFactor(BigDecimal effectiveUsdTarget) {
        BigDecimal totalNonUsdTarget = BigDecimal.ZERO;
        for (Allocation a : configService.getConfig().allocations()) {
            if (!a.symbol().equalsIgnoreCase("USD")) {
                totalNonUsdTarget = totalNonUsdTarget.add(BigDecimal.valueOf(a.targetPercent()));
            }
        }

        BigDecimal remainingForCrypto = BigDecimal.valueOf(100).subtract(effectiveUsdTarget);
        if (totalNonUsdTarget.compareTo(BigDecimal.ZERO) > 0) {
            return remainingForCrypto.divide(totalNonUsdTarget, 8, RoundingMode.HALF_UP);
        }
        return BigDecimal.ONE;
    }

    private void analyzeDeviations(BigDecimal totalPortfolioValueUSD, Map<String, BigDecimal> currentValuesUSD,
            BigDecimal effectiveUsdTarget, BigDecimal cryptoScaleFactor,
            Map<String, BigDecimal> buyOrders, Map<String, BigDecimal> sellOrders, List<String> actionLog) {

        Settings s = configService.getConfig().settings();
        boolean usdTriggered = false;
        BigDecimal usdDeviationAmount = BigDecimal.ZERO;
        Map<String, BigDecimal> allDeviations = new HashMap<>();

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
    }

    // ========================================================================
    // Phase 4: Execution
    // ========================================================================

    private void executeOrders(Map<String, BigDecimal> buyOrders, Map<String, BigDecimal> sellOrders,
            Map<String, BigDecimal> currentValuesUSD, Map<String, Double> prices, Settings s, List<String> actionLog) {

        BigDecimal projectedCash = currentValuesUSD.getOrDefault("USD", BigDecimal.ZERO);

        // Execute SELLS first to generate liquidity
        boolean executedSells = false;
        for (Map.Entry<String, BigDecimal> entry : sellOrders.entrySet()) {
            String symbol = entry.getKey();
            BigDecimal usdToSell = entry.getValue();

            if (usdToSell.compareTo(BigDecimal.valueOf(s.dustThresholdUSD())) < 0) {
                log.info("Skipping dust sell for {} (${})", symbol, usdToSell);
                actionLog.add("Skipping dust sell for " + symbol + " ($" + usdToSell + ")");
                continue;
            }

            BigDecimal price = getCurrentPrice(symbol, prices);
            if (price.compareTo(BigDecimal.ZERO) == 0)
                continue;

            BigDecimal volume = usdToSell.divide(price, 8, RoundingMode.HALF_UP);
            krakenService.executeOrder(symbol + "USD", "market", "sell", volume.doubleValue());
            projectedCash = projectedCash.add(usdToSell);
            executedSells = true;
            actionLog.add("SELL " + symbol + " Volume: " + volume + " Value: $" + usdToSell);
        }

        BigDecimal actualCash = projectedCash;
        if (executedSells && !s.dryRun()) {
            try {
                TimeUnit.MILLISECONDS.sleep(100); // Wait for orders to settle (use 100ms for faster tests)
                Map<String, Double> updatedBalances = krakenService.getBalances();
                if (updatedBalances != null && !updatedBalances.isEmpty()) {
                    Double usdBalance = resolveBalance("USD", updatedBalances);
                    if (usdBalance != null && usdBalance > 0) {
                        actualCash = BigDecimal.valueOf(usdBalance);
                        log.info("Updated USD balance after sells: ${}", actualCash);
                    }
                }
            } catch (Exception e) {
                log.warn("Failed to fetch updated USD balance before buys, using previous snapshot.", e);
            }
        }

        // Execute BUYS second, verifying cash sufficiency
        for (Map.Entry<String, BigDecimal> entry : buyOrders.entrySet()) {
            String symbol = entry.getKey();
            BigDecimal cost = entry.getValue();

            if (cost.compareTo(actualCash) > 0) {
                log.warn("Not enough cash to buy {}. Cost: {}, Cash: {}. Reducing.", symbol, cost, actualCash);
                cost = actualCash.multiply(BigDecimal.valueOf(0.99)); // Safety buffer
            }

            if (cost.compareTo(BigDecimal.valueOf(s.dustThresholdUSD())) < 0) {
                log.info("Skipping dust buy for {} (${})", symbol, cost);
                actionLog.add("Skipping dust buy for " + symbol + " ($" + cost + ")");
                continue;
            }

            BigDecimal price = getCurrentPrice(symbol, prices);
            if (price.compareTo(BigDecimal.ZERO) == 0)
                continue;

            BigDecimal volume = cost.divide(price, 8, RoundingMode.HALF_UP);
            krakenService.executeOrder(symbol + "USD", "market", "buy", volume.doubleValue());
            actualCash = actualCash.subtract(cost);
            actionLog.add("BUY " + symbol + " Volume: " + volume + " Cost: $" + cost);
        }
    }

    // ========================================================================
    // Phase 5: Record Snapshot
    // ========================================================================

    private PortfolioSnapshot buildSnapshot(Map<String, Double> balances, Map<String, Double> prices,
            Map<String, BigDecimal> currentValuesUSD, BigDecimal totalPortfolioValueUSD,
            BigDecimal effectiveUsdTarget, BigDecimal cryptoScaleFactor,
            BigDecimal drawdownPct, BigDecimal fiatDeploymentPct, List<String> actionLog) {

        Map<String, PortfolioSnapshot.AssetSnapshot> assetSnapshots = new HashMap<>();
        for (Allocation a : configService.getConfig().allocations()) {
            String symbol = a.symbol();
            BigDecimal balance = BigDecimal.valueOf(resolveBalance(symbol, balances));
            BigDecimal valUSD = currentValuesUSD.getOrDefault(symbol, BigDecimal.ZERO);
            BigDecimal price = BigDecimal.ONE;
            if (!symbol.equalsIgnoreCase("USD")) {
                price = getCurrentPrice(symbol, prices);
            }

            // Recalculate percentages for snapshot
            BigDecimal baseTargetPct = BigDecimal.valueOf(a.targetPercent());
            BigDecimal snapshotTargetPct = baseTargetPct;
            BigDecimal calcTargetPct;

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

            BigDecimal targetVal = totalPortfolioValueUSD.multiply(calcTargetPct).divide(BigDecimal.valueOf(100), 4,
                    RoundingMode.HALF_UP);
            BigDecimal deviationUSD = valUSD.subtract(targetVal);
            BigDecimal devPct = BigDecimal.ZERO;
            if (targetVal.compareTo(BigDecimal.ZERO) > 0) {
                devPct = deviationUSD.divide(targetVal, 4, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100));
            }

            assetSnapshots.put(symbol, new PortfolioSnapshot.AssetSnapshot(
                    symbol, balance, price, valUSD, snapshotTargetPct, currentPct, devPct, deviationUSD));
        }

        return new PortfolioSnapshot(
                Instant.now(),
                totalPortfolioValueUSD,
                assetSnapshots,
                actionLog,
                drawdownPct,
                fiatDeploymentPct,
                effectiveUsdTarget);
    }

    // ========================================================================
    // Fiat Correction
    // ========================================================================

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

    // ========================================================================
    // Utilities
    // ========================================================================

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
