import { Decimal } from 'decimal.js';
import { KrakenService } from './kraken';
import { PortfolioAnalyzer } from './analyzer';
import { OrderResult } from '../model/order';
import { Asset } from '../model/asset';

Decimal.set({ rounding: Decimal.ROUND_HALF_UP });

const delay = (ms: number) => new Promise(resolve => setTimeout(resolve, ms));

export class OrderExecutor {
  private readonly krakenService: KrakenService;
  private readonly portfolioAnalyzer: PortfolioAnalyzer;

  constructor(krakenService: KrakenService, portfolioAnalyzer: PortfolioAnalyzer) {
    this.krakenService = krakenService;
    this.portfolioAnalyzer = portfolioAnalyzer;
  }

  async executeOrders(
    buyOrders: Record<string, Decimal>,
    sellOrders: Record<string, Decimal>,
    currentValuesUSD: Record<string, Decimal>,
    prices: Record<string, Decimal>,
    settings: any,
    actionLog: string[]
  ): Promise<void> {
    let projectedCash = currentValuesUSD[Asset.USD] || new Decimal(0);
    let executedSells = false;

    for (const [symbol, usdToSell] of Object.entries(sellOrders)) {
      if (usdToSell.lt(settings.dustThresholdUSD)) {
        console.log(`Skipping dust sell for ${symbol} ($ ${usdToSell.toFixed(2)})`);
        actionLog.push(`Skipping dust sell for ${symbol} ($${usdToSell.toFixed(2)})`);
        continue;
      }

      const price = prices[symbol];
      if (!price || price.isZero()) continue;

      const volume = usdToSell.div(price).toDecimalPlaces(8);
      const pair = Asset.tradingPair(symbol);
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
        console.warn(
          `Not enough cash to buy ${symbol}. Cost: ${cost.toFixed(2)}, Cash: ${actualCash.toFixed(2)}. Reducing.`
        );
        cost = actualCash.mul(0.99);
      }

      if (cost.lt(settings.dustThresholdUSD)) {
        console.log(`Skipping dust buy for ${symbol} ($ ${cost.toFixed(2)})`);
        actionLog.push(`Skipping dust buy for ${symbol} ($${cost.toFixed(2)})`);
        continue;
      }

      const price = prices[symbol];
      if (!price || price.isZero()) continue;

      const volume = cost.div(price).toDecimalPlaces(8);
      const pair = Asset.tradingPair(symbol);
      const result = await this.krakenService.executeOrder(pair, 'market', 'buy', volume);

      this.logOrderResult(result, actionLog, symbol, volume, cost, 'BUY');

      if (result.success) {
        actualCash = actualCash.sub(cost);
      }
    }
  }

  private async refreshUsdBalanceAfterSells(projectedCash: Decimal): Promise<Decimal> {
    const maxAttempts = 3;
    const delayMs = 250;
    let bestCash = projectedCash;

    for (let attempt = 0; attempt < maxAttempts; attempt++) {
      await delay(delayMs);
      try {
        const updatedBalances = await this.krakenService.getBalances();
        if (updatedBalances && Object.keys(updatedBalances).length > 0) {
          const usdBalance = this.portfolioAnalyzer.resolveBalance(Asset.USD, updatedBalances);
          if (usdBalance > 0) {
            bestCash = new Decimal(usdBalance);
            console.log(
              `Updated USD balance after sells (attempt ${attempt + 1}): $${bestCash.toFixed(2)}`
            );
            if (bestCash.gte(projectedCash.mul(0.95))) {
              return bestCash;
            }
          }
        }
      } catch (e) {
        console.warn(`Failed to fetch updated USD balance (attempt ${attempt + 1})`, e);
      }
    }

    console.warn(`Using best observed USD balance after sell refresh: $${bestCash.toFixed(2)}`);
    return bestCash;
  }

  private logOrderResult(
    result: OrderResult,
    actionLog: string[],
    symbol: string,
    volume: Decimal,
    usdAmount: Decimal,
    side: 'BUY' | 'SELL'
  ): void {
    if (result.success) {
      const prefix = result.dryRun ? '[DRY RUN] ' : '';
      if (side === 'SELL') {
        actionLog.push(`${prefix}SELL ${symbol} Volume: ${volume} Value: $${usdAmount.toFixed(2)}`);
      } else {
        actionLog.push(`${prefix}BUY ${symbol} Volume: ${volume} Cost: $${usdAmount.toFixed(2)}`);
      }
    } else {
      actionLog.push(`FAILED ${side} ${symbol}: ${result.errorMessage}`);
    }
  }
}
