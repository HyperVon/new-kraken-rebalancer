"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.PortfolioManager = void 0;
const decimal_js_1 = require("decimal.js");
const asset_1 = require("../model/asset");
decimal_js_1.Decimal.set({ rounding: decimal_js_1.Decimal.ROUND_HALF_UP });
class PortfolioManager {
    configService;
    tradeHistoryService;
    portfolioAnalyzer;
    orderExecutor;
    isRunning = false;
    timer = null;
    constructor(configService, tradeHistoryService, portfolioAnalyzer, orderExecutor) {
        this.configService = configService;
        this.tradeHistoryService = tradeHistoryService;
        this.portfolioAnalyzer = portfolioAnalyzer;
        this.orderExecutor = orderExecutor;
    }
    startRebalancingLoop() {
        if (this.isRunning)
            return;
        this.isRunning = true;
        console.log('Rebalancing loop started.');
        this.runLoop();
    }
    stopRebalancingLoop() {
        this.isRunning = false;
        if (this.timer) {
            clearTimeout(this.timer);
            this.timer = null;
        }
        console.log('Rebalancing loop stopped.');
    }
    async runLoop() {
        if (!this.isRunning)
            return;
        const settings = this.configService.getConfig().settings;
        try {
            console.log(`Starting Rebalance Cycle. DryRun: ${settings.dryRun}`);
            await this.performRebalanceCycle();
        }
        catch (e) {
            console.error('Error in rebalancing cycle', e);
        }
        if (this.isRunning) {
            const delayMs = settings.loopDelaySeconds * 1000;
            this.timer = setTimeout(() => this.runLoop(), delayMs);
        }
    }
    async performRebalanceCycle() {
        console.log('--- Starting Snapshot Phase ---');
        const actionLog = [];
        const balances = await this.portfolioAnalyzer.fetchBalances();
        const prices = await this.portfolioAnalyzer.fetchPrices();
        const values = this.portfolioAnalyzer.calculatePortfolioValues(balances, prices);
        if (!values) {
            return;
        }
        const { totalValueUSD, currentValuesUSD } = values;
        console.log(`Total Portfolio Value: $${totalValueUSD.toFixed(2)}`);
        const drawdownPct = this.portfolioAnalyzer.updateAthAndCalculateDrawdown(totalValueUSD);
        const fiatDeploymentPct = this.portfolioAnalyzer.calculateFiatDeployment(drawdownPct, this.configService.getConfig().settings);
        if (fiatDeploymentPct.gt(0)) {
            console.log(`Drawdown Detected: ${drawdownPct.toFixed(2)}%. Fiat Deployment: ${fiatDeploymentPct.toFixed(2)}%`);
        }
        const effectiveUsdTarget = this.portfolioAnalyzer.calculateEffectiveUsdTarget(fiatDeploymentPct);
        const cryptoScaleFactor = this.portfolioAnalyzer.calculateCryptoScaleFactor(effectiveUsdTarget);
        const { buyOrders, sellOrders, actionLog: cycleActions } = this.portfolioAnalyzer.analyzeDeviations(totalValueUSD, currentValuesUSD, effectiveUsdTarget, cryptoScaleFactor);
        actionLog.push(...cycleActions);
        await this.orderExecutor.executeOrders(buyOrders, sellOrders, currentValuesUSD, prices, this.configService.getConfig().settings, actionLog);
        const snapshot = this.buildSnapshot(balances, prices, currentValuesUSD, totalValueUSD, effectiveUsdTarget, cryptoScaleFactor, drawdownPct, fiatDeploymentPct, actionLog);
        try {
            this.tradeHistoryService.addSnapshot(snapshot);
        }
        catch (e) {
            const message = e instanceof Error ? e.message : String(e);
            console.error('Failed to persist trade history snapshot', e);
            actionLog.push(`ERROR: Failed to persist trade history: ${message}`);
        }
        console.log('--- Cycle Complete ---');
    }
    buildSnapshot(balances, prices, currentValuesUSD, totalPortfolioValueUSD, effectiveUsdTarget, cryptoScaleFactor, drawdownPct, fiatDeploymentPct, actionLog) {
        const assets = {};
        for (const alloc of this.configService.getConfig().allocations) {
            const symbol = alloc.symbol;
            const isUsd = symbol.toUpperCase() === asset_1.Asset.USD;
            const balanceVal = this.portfolioAnalyzer.resolveBalance(symbol, balances);
            const balance = new decimal_js_1.Decimal(balanceVal);
            const valUSD = currentValuesUSD[symbol] || new decimal_js_1.Decimal(0);
            const price = isUsd ? new decimal_js_1.Decimal(1) : prices[symbol] || new decimal_js_1.Decimal(1);
            const baseTargetPct = new decimal_js_1.Decimal(alloc.targetPercent);
            let snapshotTargetPct = baseTargetPct;
            let calcTargetPct;
            if (isUsd) {
                calcTargetPct = effectiveUsdTarget;
            }
            else {
                calcTargetPct = baseTargetPct.mul(cryptoScaleFactor);
                snapshotTargetPct = calcTargetPct;
            }
            let currentPct = new decimal_js_1.Decimal(0);
            if (totalPortfolioValueUSD.gt(0)) {
                currentPct = valUSD.div(totalPortfolioValueUSD).toDecimalPlaces(4).mul(100);
            }
            const targetVal = totalPortfolioValueUSD.mul(calcTargetPct).div(100).toDecimalPlaces(4);
            const deviationUSD = valUSD.sub(targetVal);
            let devPct = new decimal_js_1.Decimal(0);
            if (targetVal.gt(0)) {
                devPct = deviationUSD.div(targetVal).toDecimalPlaces(4).mul(100);
            }
            assets[symbol] = {
                symbol,
                balance,
                price,
                valueUSD: valUSD,
                targetPercent: snapshotTargetPct,
                currentPercent: currentPct,
                deviationPercent: devPct,
                deviationUSD
            };
        }
        return {
            timestamp: new Date().toISOString(),
            totalValueUSD: totalPortfolioValueUSD,
            assets,
            actions: actionLog,
            drawdownPercent: drawdownPct,
            fiatDeploymentPercent: fiatDeploymentPct,
            effectiveUsdTargetPercent: effectiveUsdTarget
        };
    }
}
exports.PortfolioManager = PortfolioManager;
