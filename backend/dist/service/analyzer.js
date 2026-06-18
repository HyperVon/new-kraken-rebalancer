"use strict";
var __decorate = (this && this.__decorate) || function (decorators, target, key, desc) {
    var c = arguments.length, r = c < 3 ? target : desc === null ? desc = Object.getOwnPropertyDescriptor(target, key) : desc, d;
    if (typeof Reflect === "object" && typeof Reflect.decorate === "function") r = Reflect.decorate(decorators, target, key, desc);
    else for (var i = decorators.length - 1; i >= 0; i--) if (d = decorators[i]) r = (c < 3 ? d(r) : c > 3 ? d(target, key, r) : d(target, key)) || r;
    return c > 3 && r && Object.defineProperty(target, key, r), r;
};
var __metadata = (this && this.__metadata) || function (k, v) {
    if (typeof Reflect === "object" && typeof Reflect.metadata === "function") return Reflect.metadata(k, v);
};
var __param = (this && this.__param) || function (paramIndex, decorator) {
    return function (target, key) { decorator(target, key, paramIndex); }
};
Object.defineProperty(exports, "__esModule", { value: true });
exports.PortfolioAnalyzer = void 0;
const decimal_js_1 = require("decimal.js");
const config_1 = require("../config/config");
const kraken_1 = require("./kraken");
const stats_1 = require("../repository/stats");
const asset_1 = require("../model/asset");
const common_1 = require("@nestjs/common");
// Configure decimal.js for consistent RoundingMode.HALF_UP matching Kotlin
decimal_js_1.Decimal.set({ rounding: decimal_js_1.Decimal.ROUND_HALF_UP });
let PortfolioAnalyzer = class PortfolioAnalyzer {
    krakenService;
    configService;
    portfolioStatsRepository;
    constructor(krakenService, configService, portfolioStatsRepository) {
        this.krakenService = krakenService;
        this.configService = configService;
        this.portfolioStatsRepository = portfolioStatsRepository;
    }
    async fetchBalances() {
        const balances = await this.krakenService.getBalances();
        console.log(`Available Balance Keys: ${Object.keys(balances).join(', ')}`);
        return balances;
    }
    async fetchPrices() {
        const allocations = this.configService.getConfig().allocations;
        const nonUsd = allocations.filter(a => a.symbol.toUpperCase() !== asset_1.Asset.USD);
        if (nonUsd.length === 0)
            return {};
        const pairs = nonUsd.map(a => asset_1.Asset.tradingPair(a.symbol)).join(',');
        const rawPrices = await this.krakenService.getTickerPrices(pairs);
        const prices = {};
        for (const alloc of nonUsd) {
            prices[alloc.symbol] = this.resolvePriceFromTicker(alloc.symbol, rawPrices);
        }
        return prices;
    }
    resolvePriceFromTicker(symbol, rawPrices) {
        const expectedPair = asset_1.Asset.tradingPair(symbol);
        if (rawPrices[expectedPair] !== undefined) {
            return new decimal_js_1.Decimal(rawPrices[expectedPair]);
        }
        const krakenTicker = asset_1.Asset.toKrakenTicker(symbol);
        for (const key of Object.keys(rawPrices)) {
            if (key.includes(krakenTicker) && key.includes(asset_1.Asset.USD)) {
                return new decimal_js_1.Decimal(rawPrices[key]);
            }
        }
        return new decimal_js_1.Decimal(0);
    }
    calculatePortfolioValues(balances, prices) {
        const currentValuesUSD = {};
        let totalPortfolioValueUSD = new decimal_js_1.Decimal(0);
        for (const alloc of this.configService.getConfig().allocations) {
            const symbol = alloc.symbol;
            const balance = this.resolveBalance(symbol, balances);
            const bal = new decimal_js_1.Decimal(balance);
            let price = new decimal_js_1.Decimal(1);
            if (symbol.toUpperCase() !== asset_1.Asset.USD) {
                const p = prices[symbol];
                if (!p || p.isZero()) {
                    console.error(`Price not found for ${symbol}. Aborting rebalance cycle to prevent erroneous trades.`);
                    return null;
                }
                price = p;
            }
            const valUSD = bal.mul(price);
            currentValuesUSD[symbol] = valUSD;
            totalPortfolioValueUSD = totalPortfolioValueUSD.add(valUSD);
        }
        return { totalValueUSD: totalPortfolioValueUSD, currentValuesUSD };
    }
    resolveBalance(symbol, balances) {
        const s = symbol.toUpperCase();
        const ticker = asset_1.Asset.toKrakenTicker(symbol);
        return (balances[symbol] ??
            balances[`X${symbol}`] ??
            balances[`Z${symbol}`] ??
            balances[ticker] ??
            balances[`X${ticker}`] ??
            0.0);
    }
    updateAthAndCalculateDrawdown(totalPortfolioValueUSD) {
        const stats = this.portfolioStatsRepository.load();
        let ath = stats.allTimeHigh;
        if (!ath || ath.isZero() || ath.isNegative()) {
            ath = totalPortfolioValueUSD;
            console.log(`Initial ATH set to ${ath.toFixed(2)}`);
        }
        else if (totalPortfolioValueUSD.gt(ath)) {
            ath = totalPortfolioValueUSD;
            console.log(`New All-Time High detected: ${ath.toFixed(2)}`);
        }
        stats.allTimeHigh = ath;
        try {
            this.portfolioStatsRepository.save(stats);
        }
        catch (e) {
            console.error('Failed to persist portfolio ATH', e);
        }
        if (ath.gt(0) && totalPortfolioValueUSD.lt(ath)) {
            const diff = ath.sub(totalPortfolioValueUSD);
            return diff.div(ath).toDecimalPlaces(4).mul(100);
        }
        else {
            return new decimal_js_1.Decimal(0);
        }
    }
    calculateFiatDeployment(drawdownPct, settings) {
        if (settings.fiatMaxDrawdown <= 0.0)
            return new decimal_js_1.Decimal(0);
        const maxDD = new decimal_js_1.Decimal(settings.fiatMaxDrawdown);
        let ratio = drawdownPct.div(maxDD).toDecimalPlaces(4);
        if (ratio.gt(1)) {
            ratio = new decimal_js_1.Decimal(1);
        }
        const deployDouble = Math.pow(ratio.toNumber(), settings.fiatDeploymentExponent) * 100.0;
        return new decimal_js_1.Decimal(deployDouble);
    }
    calculateEffectiveUsdTarget(fiatDeploymentPct) {
        const baseUsdTarget = new decimal_js_1.Decimal(this.configService
            .getConfig()
            .allocations.filter(a => a.symbol.toUpperCase() === asset_1.Asset.USD)
            .reduce((sum, a) => sum + a.targetPercent, 0));
        if (fiatDeploymentPct.gt(0)) {
            const factor = new decimal_js_1.Decimal(1).sub(fiatDeploymentPct.div(100).toDecimalPlaces(4));
            return baseUsdTarget.mul(factor);
        }
        else {
            return baseUsdTarget;
        }
    }
    calculateCryptoScaleFactor(effectiveUsdTarget) {
        const totalNonUsdTarget = new decimal_js_1.Decimal(this.configService
            .getConfig()
            .allocations.filter(a => a.symbol.toUpperCase() !== asset_1.Asset.USD)
            .reduce((sum, a) => sum + a.targetPercent, 0));
        const remainingForCrypto = new decimal_js_1.Decimal(100).sub(effectiveUsdTarget);
        if (totalNonUsdTarget.gt(0)) {
            return remainingForCrypto.div(totalNonUsdTarget).toDecimalPlaces(8);
        }
        else {
            return new decimal_js_1.Decimal(1);
        }
    }
    analyzeDeviations(totalPortfolioValueUSD, currentValuesUSD, effectiveUsdTarget, cryptoScaleFactor) {
        const buyOrders = {};
        const sellOrders = {};
        const actionLog = [];
        const settings = this.configService.getConfig().settings;
        let usdTriggered = false;
        let usdDeviationAmount = new decimal_js_1.Decimal(0);
        const allDeviations = {};
        for (const alloc of this.configService.getConfig().allocations) {
            const symbol = alloc.symbol;
            const isUsd = symbol.toUpperCase() === asset_1.Asset.USD;
            let targetPct = new decimal_js_1.Decimal(alloc.targetPercent);
            if (isUsd) {
                targetPct = effectiveUsdTarget;
            }
            else {
                targetPct = targetPct.mul(cryptoScaleFactor);
            }
            targetPct = targetPct.div(100).toDecimalPlaces(4);
            const targetValue = totalPortfolioValueUSD.mul(targetPct);
            const currentVal = currentValuesUSD[symbol] || new decimal_js_1.Decimal(0);
            const deviationUSD = currentVal.sub(targetValue);
            let deviationPct = new decimal_js_1.Decimal(0);
            if (targetValue.gt(0)) {
                deviationPct = deviationUSD.abs().div(targetValue).toDecimalPlaces(4).mul(100);
            }
            else if (currentVal.gt(0)) {
                deviationPct = new decimal_js_1.Decimal(100);
            }
            allDeviations[symbol] = deviationUSD;
            console.log(`Analysis [${symbol}]: Dev: ${deviationPct.toFixed(2)}% ($ ${deviationUSD.toFixed(2)}). Threshold: ${settings.deviationTriggerPercent}%`);
            const isDeviationSignificant = deviationUSD.abs().gte(settings.dustThresholdUSD);
            if (deviationPct.toNumber() >= settings.deviationTriggerPercent && isDeviationSignificant) {
                actionLog.push(`Deviation Triggered details: ${symbol} Dev: ${deviationPct.toFixed(2)}%`);
            }
            if (isUsd) {
                if (deviationPct.toNumber() >= settings.deviationTriggerPercent && isDeviationSignificant) {
                    console.log(`Asset USD Deviation: ${deviationPct.toFixed(2)}% (Trigger: ${settings.deviationTriggerPercent}%). USD Dev: ${deviationUSD.toFixed(2)}`);
                    usdTriggered = true;
                    usdDeviationAmount = deviationUSD;
                }
            }
            else {
                if (deviationPct.toNumber() >= settings.deviationTriggerPercent && isDeviationSignificant) {
                    console.log(`Asset ${symbol} Deviation: ${deviationPct.toFixed(2)}% (Trigger: ${settings.deviationTriggerPercent}%). USD Dev: ${deviationUSD.toFixed(2)}`);
                    if (deviationUSD.gt(0)) {
                        sellOrders[symbol] = deviationUSD;
                    }
                    else {
                        buyOrders[symbol] = deviationUSD.abs();
                    }
                }
            }
        }
        if (Object.keys(buyOrders).length === 0 && Object.keys(sellOrders).length === 0 && usdTriggered) {
            console.log('USD Deviation triggered but no individual asset triggers. Enforcing fiat correction.');
            actionLog.push('USD Deviation Triggered. Enforcing fiat correction.');
            this.distributeFiatCorrection(usdDeviationAmount, allDeviations, buyOrders, sellOrders, actionLog);
        }
        return { buyOrders, sellOrders, actionLog };
    }
    distributeFiatCorrection(usdDev, allDevs, buyOrders, sellOrders, actionLog) {
        const deviationAbs = usdDev.abs();
        const isDeposit = usdDev.gt(0);
        let totalCounterDev = new decimal_js_1.Decimal(0);
        const candidates = [];
        for (const symbol of Object.keys(allDevs)) {
            if (symbol.toUpperCase() === asset_1.Asset.USD)
                continue;
            const d = allDevs[symbol];
            if (isDeposit && d.lt(0)) {
                candidates.push(symbol);
                totalCounterDev = totalCounterDev.add(d.abs());
            }
            else if (!isDeposit && d.gt(0)) {
                candidates.push(symbol);
                totalCounterDev = totalCounterDev.add(d);
            }
        }
        if (totalCounterDev.isZero()) {
            console.log('Fiat correction required but no suitable counter-balancing assets found.');
            return;
        }
        console.log(`Distributing Fiat Correction ($${deviationAbs.toFixed(2)}) among ${candidates.length} candidates. Total Counter-Dev: $${totalCounterDev.toFixed(2)}`);
        actionLog.push(`Distributing Fiat Correction ($${deviationAbs.toFixed(2)}) among ${candidates.length} candidates.`);
        for (const symbol of candidates) {
            const assetDev = allDevs[symbol].abs();
            const ratio = assetDev.div(totalCounterDev).toDecimalPlaces(8);
            const share = deviationAbs.mul(ratio);
            if (isDeposit) {
                buyOrders[symbol] = share;
            }
            else {
                sellOrders[symbol] = share;
            }
        }
    }
};
exports.PortfolioAnalyzer = PortfolioAnalyzer;
exports.PortfolioAnalyzer = PortfolioAnalyzer = __decorate([
    (0, common_1.Injectable)(),
    __param(0, (0, common_1.Inject)(kraken_1.KRAKEN_SERVICE_TOKEN)),
    __metadata("design:paramtypes", [Object, config_1.ConfigService,
        stats_1.PortfolioStatsRepository])
], PortfolioAnalyzer);
