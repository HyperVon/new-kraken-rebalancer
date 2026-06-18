"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.OrderExecutor = void 0;
const decimal_js_1 = require("decimal.js");
const asset_1 = require("../model/asset");
const promises_1 = require("timers/promises");
decimal_js_1.Decimal.set({ rounding: decimal_js_1.Decimal.ROUND_HALF_UP });
class OrderExecutor {
    krakenService;
    portfolioAnalyzer;
    constructor(krakenService, portfolioAnalyzer) {
        this.krakenService = krakenService;
        this.portfolioAnalyzer = portfolioAnalyzer;
    }
    async executeOrders(buyOrders, sellOrders, currentValuesUSD, prices, settings, actionLog) {
        let projectedCash = currentValuesUSD[asset_1.Asset.USD] || new decimal_js_1.Decimal(0);
        let executedSells = false;
        for (const [symbol, usdToSell] of Object.entries(sellOrders)) {
            if (usdToSell.lt(settings.dustThresholdUSD)) {
                console.log(`Skipping dust sell for ${symbol} ($ ${usdToSell.toFixed(2)})`);
                actionLog.push(`Skipping dust sell for ${symbol} ($${usdToSell.toFixed(2)})`);
                continue;
            }
            const price = prices[symbol];
            if (!price || price.isZero())
                continue;
            const volume = usdToSell.div(price).toDecimalPlaces(8);
            const pair = asset_1.Asset.tradingPair(symbol);
            const result = await this.krakenService.executeOrder(pair, 'market', 'sell', volume);
            this.logOrderResult(result, actionLog, symbol, volume, usdToSell, 'SELL');
            if (result.success) {
                projectedCash = projectedCash.add(usdToSell);
                executedSells = true;
            }
        }
        let actualCash = projectedCash;
        if (executedSells && !settings.dryRun) {
            actualCash = await this.refreshUsdBalanceAfterSells(projectedCash);
        }
        for (const [symbol, originalCost] of Object.entries(buyOrders)) {
            let cost = originalCost;
            if (cost.gt(actualCash)) {
                console.warn(`Not enough cash to buy ${symbol}. Cost: ${cost.toFixed(2)}, Cash: ${actualCash.toFixed(2)}. Reducing.`);
                cost = actualCash.mul(0.99);
            }
            if (cost.lt(settings.dustThresholdUSD)) {
                console.log(`Skipping dust buy for ${symbol} ($ ${cost.toFixed(2)})`);
                actionLog.push(`Skipping dust buy for ${symbol} ($${cost.toFixed(2)})`);
                continue;
            }
            const price = prices[symbol];
            if (!price || price.isZero())
                continue;
            const volume = cost.div(price).toDecimalPlaces(8);
            const pair = asset_1.Asset.tradingPair(symbol);
            const result = await this.krakenService.executeOrder(pair, 'market', 'buy', volume);
            this.logOrderResult(result, actionLog, symbol, volume, cost, 'BUY');
            if (result.success) {
                actualCash = actualCash.sub(cost);
            }
        }
    }
    async refreshUsdBalanceAfterSells(projectedCash) {
        const maxAttempts = 3;
        const delayMs = 250;
        let bestCash = projectedCash;
        for (let attempt = 0; attempt < maxAttempts; attempt++) {
            await (0, promises_1.setTimeout)(delayMs);
            try {
                const updatedBalances = await this.krakenService.getBalances();
                if (updatedBalances && Object.keys(updatedBalances).length > 0) {
                    const usdBalance = this.portfolioAnalyzer.resolveBalance(asset_1.Asset.USD, updatedBalances);
                    if (usdBalance > 0) {
                        bestCash = new decimal_js_1.Decimal(usdBalance);
                        console.log(`Updated USD balance after sells (attempt ${attempt + 1}): $${bestCash.toFixed(2)}`);
                        if (bestCash.gte(projectedCash.mul(0.95))) {
                            return bestCash;
                        }
                    }
                }
            }
            catch (e) {
                console.warn(`Failed to fetch updated USD balance (attempt ${attempt + 1})`, e);
            }
        }
        console.warn(`Using best observed USD balance after sell refresh: $${bestCash.toFixed(2)}`);
        return bestCash;
    }
    logOrderResult(result, actionLog, symbol, volume, usdAmount, side) {
        if (result.success) {
            const prefix = result.dryRun ? '[DRY RUN] ' : '';
            if (side === 'SELL') {
                actionLog.push(`${prefix}SELL ${symbol} Volume: ${volume} Value: $${usdAmount.toFixed(2)}`);
            }
            else {
                actionLog.push(`${prefix}BUY ${symbol} Volume: ${volume} Cost: $${usdAmount.toFixed(2)}`);
            }
        }
        else {
            actionLog.push(`FAILED ${side} ${symbol}: ${result.errorMessage}`);
        }
    }
}
exports.OrderExecutor = OrderExecutor;
