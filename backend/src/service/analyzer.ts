import { Decimal } from 'decimal.js';
import { ConfigService } from '../config/config';
import { KrakenService } from './kraken';
import { PortfolioStatsRepository } from '../repository/stats';
import { Asset } from '../model/asset';

// Configure decimal.js for consistent RoundingMode.HALF_UP matching Kotlin
Decimal.set({ rounding: Decimal.ROUND_HALF_UP });

export interface AnalysisResult {
  buyOrders: Record<string, Decimal>;
  sellOrders: Record<string, Decimal>;
  actionLog: string[];
}

export interface PortfolioValues {
  totalValueUSD: Decimal;
  currentValuesUSD: Record<string, Decimal>;
}

export class PortfolioAnalyzer {
  private readonly krakenService: KrakenService;
  private readonly configService: ConfigService;
  private readonly portfolioStatsRepository: PortfolioStatsRepository;

  constructor(
    krakenService: KrakenService,
    configService: ConfigService,
    portfolioStatsRepository: PortfolioStatsRepository
  ) {
    this.krakenService = krakenService;
    this.configService = configService;
    this.portfolioStatsRepository = portfolioStatsRepository;
  }

  async fetchBalances(): Promise<Record<string, number>> {
    const balances = await this.krakenService.getBalances();
    console.log(`Available Balance Keys: ${Object.keys(balances).join(', ')}`);
    return balances;
  }

  async fetchPrices(): Promise<Record<string, Decimal>> {
    const allocations = this.configService.getConfig().allocations;
    const nonUsd = allocations.filter(a => a.symbol.toUpperCase() !== Asset.USD);
    if (nonUsd.length === 0) return {};

    const pairs = nonUsd.map(a => Asset.tradingPair(a.symbol)).join(',');
    const rawPrices = await this.krakenService.getTickerPrices(pairs);

    const prices: Record<string, Decimal> = {};
    for (const alloc of nonUsd) {
      prices[alloc.symbol] = this.resolvePriceFromTicker(alloc.symbol, rawPrices);
    }
    return prices;
  }

  resolvePriceFromTicker(symbol: string, rawPrices: Record<string, number>): Decimal {
    const expectedPair = Asset.tradingPair(symbol);
    if (rawPrices[expectedPair] !== undefined) {
      return new Decimal(rawPrices[expectedPair]);
    }

    const krakenTicker = Asset.toKrakenTicker(symbol);
    for (const key of Object.keys(rawPrices)) {
      if (key.includes(krakenTicker) && key.includes(Asset.USD)) {
        return new Decimal(rawPrices[key]);
      }
    }
    return new Decimal(0);
  }

  calculatePortfolioValues(
    balances: Record<string, number>,
    prices: Record<string, Decimal>
  ): PortfolioValues | null {
    const currentValuesUSD: Record<string, Decimal> = {};
    let totalPortfolioValueUSD = new Decimal(0);

    for (const alloc of this.configService.getConfig().allocations) {
      const symbol = alloc.symbol;
      const balance = this.resolveBalance(symbol, balances);
      const bal = new Decimal(balance);
      let price = new Decimal(1);

      if (symbol.toUpperCase() !== Asset.USD) {
        const p = prices[symbol];
        if (!p || p.isZero()) {
          console.error(
            `Price not found for ${symbol}. Aborting rebalance cycle to prevent erroneous trades.`
          );
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

  resolveBalance(symbol: string, balances: Record<string, number>): number {
    const s = symbol.toUpperCase();
    const ticker = Asset.toKrakenTicker(symbol);
    return (
      balances[symbol] ??
      balances[`X${symbol}`] ??
      balances[`Z${symbol}`] ??
      balances[ticker] ??
      balances[`X${ticker}`] ??
      0.0
    );
  }

  updateAthAndCalculateDrawdown(totalPortfolioValueUSD: Decimal): Decimal {
    const stats = this.portfolioStatsRepository.load();
    let ath = stats.allTimeHigh;

    if (!ath || ath.isZero() || ath.isNegative()) {
      ath = totalPortfolioValueUSD;
      console.log(`Initial ATH set to ${ath.toFixed(2)}`);
    } else if (totalPortfolioValueUSD.gt(ath)) {
      ath = totalPortfolioValueUSD;
      console.log(`New All-Time High detected: ${ath.toFixed(2)}`);
    }

    stats.allTimeHigh = ath;
    try {
      this.portfolioStatsRepository.save(stats);
    } catch (e) {
      console.error('Failed to persist portfolio ATH', e);
    }

    if (ath.gt(0) && totalPortfolioValueUSD.lt(ath)) {
      const diff = ath.sub(totalPortfolioValueUSD);
      return diff.div(ath).toDecimalPlaces(4).mul(100);
    } else {
      return new Decimal(0);
    }
  }

  calculateFiatDeployment(drawdownPct: Decimal, settings: any): Decimal {
    if (settings.fiatMaxDrawdown <= 0.0) return new Decimal(0);

    const maxDD = new Decimal(settings.fiatMaxDrawdown);
    let ratio = drawdownPct.div(maxDD).toDecimalPlaces(4);
    if (ratio.gt(1)) {
      ratio = new Decimal(1);
    }

    const deployDouble = Math.pow(ratio.toNumber(), settings.fiatDeploymentExponent) * 100.0;
    return new Decimal(deployDouble);
  }

  calculateEffectiveUsdTarget(fiatDeploymentPct: Decimal): Decimal {
    const baseUsdTarget = new Decimal(
      this.configService
        .getConfig()
        .allocations.filter(a => a.symbol.toUpperCase() === Asset.USD)
        .reduce((sum, a) => sum + a.targetPercent, 0)
    );

    if (fiatDeploymentPct.gt(0)) {
      const factor = new Decimal(1).sub(fiatDeploymentPct.div(100).toDecimalPlaces(4));
      return baseUsdTarget.mul(factor);
    } else {
      return baseUsdTarget;
    }
  }

  calculateCryptoScaleFactor(effectiveUsdTarget: Decimal): Decimal {
    const totalNonUsdTarget = new Decimal(
      this.configService
        .getConfig()
        .allocations.filter(a => a.symbol.toUpperCase() !== Asset.USD)
        .reduce((sum, a) => sum + a.targetPercent, 0)
    );

    const remainingForCrypto = new Decimal(100).sub(effectiveUsdTarget);
    if (totalNonUsdTarget.gt(0)) {
      return remainingForCrypto.div(totalNonUsdTarget).toDecimalPlaces(8);
    } else {
      return new Decimal(1);
    }
  }

  analyzeDeviations(
    totalPortfolioValueUSD: Decimal,
    currentValuesUSD: Record<string, Decimal>,
    effectiveUsdTarget: Decimal,
    cryptoScaleFactor: Decimal
  ): AnalysisResult {
    const buyOrders: Record<string, Decimal> = {};
    const sellOrders: Record<string, Decimal> = {};
    const actionLog: string[] = [];

    const settings = this.configService.getConfig().settings;
    let usdTriggered = false;
    let usdDeviationAmount = new Decimal(0);
    const allDeviations: Record<string, Decimal> = {};

    for (const alloc of this.configService.getConfig().allocations) {
      const symbol = alloc.symbol;
      const isUsd = symbol.toUpperCase() === Asset.USD;

      let targetPct = new Decimal(alloc.targetPercent);
      if (isUsd) {
        targetPct = effectiveUsdTarget;
      } else {
        targetPct = targetPct.mul(cryptoScaleFactor);
      }

      targetPct = targetPct.div(100).toDecimalPlaces(4);
      const targetValue = totalPortfolioValueUSD.mul(targetPct);
      const currentVal = currentValuesUSD[symbol] || new Decimal(0);

      const deviationUSD = currentVal.sub(targetValue);
      let deviationPct = new Decimal(0);

      if (targetValue.gt(0)) {
        deviationPct = deviationUSD.abs().div(targetValue).toDecimalPlaces(4).mul(100);
      } else if (currentVal.gt(0)) {
        deviationPct = new Decimal(100);
      }

      allDeviations[symbol] = deviationUSD;

      console.log(
        `Analysis [${symbol}]: Dev: ${deviationPct.toFixed(2)}% ($ ${deviationUSD.toFixed(2)}). Threshold: ${settings.deviationTriggerPercent}%`
      );

      const isDeviationSignificant = deviationUSD.abs().gte(settings.dustThresholdUSD);

      if (deviationPct.toNumber() >= settings.deviationTriggerPercent && isDeviationSignificant) {
        actionLog.push(`Deviation Triggered details: ${symbol} Dev: ${deviationPct.toFixed(2)}%`);
      }

      if (isUsd) {
        if (deviationPct.toNumber() >= settings.deviationTriggerPercent && isDeviationSignificant) {
          console.log(
            `Asset USD Deviation: ${deviationPct.toFixed(2)}% (Trigger: ${settings.deviationTriggerPercent}%). USD Dev: ${deviationUSD.toFixed(2)}`
          );
          usdTriggered = true;
          usdDeviationAmount = deviationUSD;
        }
      } else {
        if (deviationPct.toNumber() >= settings.deviationTriggerPercent && isDeviationSignificant) {
          console.log(
            `Asset ${symbol} Deviation: ${deviationPct.toFixed(2)}% (Trigger: ${settings.deviationTriggerPercent}%). USD Dev: ${deviationUSD.toFixed(2)}`
          );

          if (deviationUSD.gt(0)) {
            sellOrders[symbol] = deviationUSD;
          } else {
            buyOrders[symbol] = deviationUSD.abs();
          }
        }
      }
    }

    if (Object.keys(buyOrders).length === 0 && Object.keys(sellOrders).length === 0 && usdTriggered) {
      console.log(
        'USD Deviation triggered but no individual asset triggers. Enforcing fiat correction.'
      );
      actionLog.push('USD Deviation Triggered. Enforcing fiat correction.');
      this.distributeFiatCorrection(
        usdDeviationAmount,
        allDeviations,
        buyOrders,
        sellOrders,
        actionLog
      );
    }

    return { buyOrders, sellOrders, actionLog };
  }

  distributeFiatCorrection(
    usdDev: Decimal,
    allDevs: Record<string, Decimal>,
    buyOrders: Record<string, Decimal>,
    sellOrders: Record<string, Decimal>,
    actionLog: string[]
  ): void {
    const deviationAbs = usdDev.abs();
    const isDeposit = usdDev.gt(0);
    let totalCounterDev = new Decimal(0);
    const candidates: string[] = [];

    for (const symbol of Object.keys(allDevs)) {
      if (symbol.toUpperCase() === Asset.USD) continue;

      const d = allDevs[symbol];
      if (isDeposit && d.lt(0)) {
        candidates.push(symbol);
        totalCounterDev = totalCounterDev.add(d.abs());
      } else if (!isDeposit && d.gt(0)) {
        candidates.push(symbol);
        totalCounterDev = totalCounterDev.add(d);
      }
    }

    if (totalCounterDev.isZero()) {
      console.log('Fiat correction required but no suitable counter-balancing assets found.');
      return;
    }

    console.log(
      `Distributing Fiat Correction ($${deviationAbs.toFixed(2)}) among ${candidates.length} candidates. Total Counter-Dev: $${totalCounterDev.toFixed(2)}`
    );
    actionLog.push(
      `Distributing Fiat Correction ($${deviationAbs.toFixed(2)}) among ${candidates.length} candidates.`
    );

    for (const symbol of candidates) {
      const assetDev = allDevs[symbol].abs();
      const ratio = assetDev.div(totalCounterDev).toDecimalPlaces(8);
      const share = deviationAbs.mul(ratio);

      if (isDeposit) {
        buyOrders[symbol] = share;
      } else {
        sellOrders[symbol] = share;
      }
    }
  }
}
