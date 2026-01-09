package com.gemini.krakenbot.simulation;

public class SimulationTool {

    // Configuration from user settings
    private static final double TRIGGER_PERCENT = 5.0;
    private static final double INITIAL_PORTFOLIO = 12000.0;

    public static void main(String[] args) {
        System.out.println("================================================================");
        System.out.println("          DYNAMIC REBALANCING SIMULATION (TIME-SERIES)          ");
        System.out.println("================================================================");
        System.out.println("Simulating how rebalancing frequency and path dependence impact cash usage.");
        System.out.println("Trigger Threshold: " + TRIGGER_PERCENT + "%");
        System.out.println("Start Portfolio: $" + INITIAL_PORTFOLIO);
        System.out.println("----------------------------------------------------------------\n");

        // Test 1: Gradual Crash vs Flash Crash
        // Does catching the falling knife use more cash?
        System.out.println("SCENARIO: -30% Market Crash");
        System.out.println("Comparing 'Instant Drop' vs 'Slow Bleed' (checked every minute)");
        System.out.println("-----------------------------------------------------------------------------------------");
        System.out.printf("%-20s | %-12s | %-12s | %-15s | %-10s%n",
                "Strategy", "Start USD", "End USD", "Cash Deployed", "Trades");
        System.out.println("-----------------------------------------------------------------------------------------");

        runComparisons(8.0, -30);
        runComparisons(2.0, -30);

        System.out
                .println("-----------------------------------------------------------------------------------------\n");

        System.out.println("ANALYSIS:");
        System.out.println("1. 'Instant Drop' simulates checking once after the dust settles.");
        System.out.println("2. 'Slow Bleed' simulates checking every minute while the market drops 1% per step.");
        System.out.println("3. If 'Cash Deployed' is higher in Slow Bleed, the frequency matters.");
    }

    private static void runComparisons(double usdAllocPercent, int totalDropPercent) {
        // Run Instant
        SimulationState instantState = new SimulationState(INITIAL_PORTFOLIO, usdAllocPercent);
        // Step 1: Market drops instantly
        instantState.applyMarketMove(totalDropPercent / 100.0);
        // Step 2: Check Rebalance
        instantState.checkAndRebalance();

        printRow(usdAllocPercent + "% (Instant)", instantState);

        // Run Slow Gradual
        SimulationState slowState = new SimulationState(INITIAL_PORTFOLIO, usdAllocPercent);
        // Simulate 30 steps of dropping ~1% each (approximately) to reach total drop
        // Compound math: (1 - x)^30 = 0.70 -> 1-x = 0.7^(1/30) -> x = 1 - 0.988 = 1.2%
        // drop per step
        double stepDrop = 1.0 - Math.pow((1.0 + (totalDropPercent / 100.0)), 1.0 / 30.0);

        for (int i = 0; i < 30; i++) {
            slowState.applyMarketMove(-stepDrop);
            slowState.checkAndRebalance();
        }
        printRow(usdAllocPercent + "% (Gradual)", slowState);
    }

    private static void printRow(String label, SimulationState state) {
        double cashDeployed = state.initialUsd - state.currentUsd;
        System.out.printf("%-20s | $%,-11.2f | $%,-11.2f | $%,-14.2f | %-10d%n",
                label, state.initialUsd, state.currentUsd, cashDeployed, state.tradeCount);
    }

    static class SimulationState {
        double currentUsd;
        double currentCrypto;
        double targetUsdPercent;

        double initialUsd;
        int tradeCount = 0;

        public SimulationState(double totalValue, double targetUsdInfo) {
            this.targetUsdPercent = targetUsdInfo / 100.0;
            this.currentUsd = totalValue * this.targetUsdPercent;
            this.currentCrypto = totalValue - this.currentUsd;
            this.initialUsd = this.currentUsd;
        }

        void applyMarketMove(double pctChange) {
            this.currentCrypto = this.currentCrypto * (1.0 + pctChange);
        }

        void checkAndRebalance() {
            double totalValue = currentUsd + currentCrypto;
            double targetUsd = totalValue * targetUsdPercent;

            // Deviation Calc: Current - Target
            double deviationUsd = currentUsd - targetUsd;
            double deviationPercent = Math.abs(deviationUsd) / targetUsd * 100.0;

            if (deviationPercent >= TRIGGER_PERCENT) {
                // Execute Trade
                // If dev > 0 (Overweight USD), we SELL USD (Buy Crypto)
                // Wait, deviationUsd = Current - Target.
                // If Current (100) > Target (80), Dev is +20. Overweight USD.
                // We need to reduce USD by 20.

                // In my logic:
                // Overweight USD -> BUY Crypto (Spend USD)
                // Underweight USD -> SELL Crypto (Get USD)

                // Note: The main logic usually triggers on CRYPTO deviation, but here we
                // simplify to holistic.
                // If USD is deviation > 5%, we trade.

                currentUsd -= deviationUsd; // Remove deviation from USD
                currentCrypto += deviationUsd; // Add to Crypto (ignoring fees for simplicity)
                tradeCount++;
            }
        }
    }
}
