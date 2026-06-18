import { Decimal } from 'decimal.js';
import { ConfigService } from '../config/config';
import { TradeHistoryService } from './history';
import { PortfolioAnalyzer } from './analyzer';
import { OrderExecutor } from './executor';
import { PortfolioSnapshot, AssetSnapshot } from '../model/snapshot';
import { Asset } from '../model/asset';

Decimal.set({ rounding: Decimal.ROUND_HALF_UP });

export class PortfolioManager {
  private readonly configService: ConfigService;
  private readonly tradeHistoryService: TradeHistoryService;
  private readonly portfolioAnalyzer: PortfolioAnalyzer;
  private readonly orderExecutor: OrderExecutor;

  private isRunning = false;
  private timer: NodeJS.Timeout | null = null;

  constructor(
    configService: ConfigService,
    tradeHistoryService: TradeHistoryService,
    portfolioAnalyzer: PortfolioAnalyzer,
    orderExecutor: OrderExecutor
  ) {
    this.configService = configService;
    this.tradeHistoryService = tradeHistoryService;
    this.portfolioAnalyzer = portfolioAnalyzer;
    this.orderExecutor = orderExecutor;
  }

  startRebalancingLoop(): void {
    if (this.isRunning) return;
    this.isRunning = true;
    console.log('Rebalancing loop started.');
    this.runLoop();
  }

  stopRebalancingLoop(): void {
    this.isRunning = false;
    if (this.timer) {
      clearTimeout(this.timer);
      this.timer = null;
    }
    console.log('Rebalancing loop stopped.');
  }

  private async runLoop(): Promise<void> {
    if (!this.isRunning) return;

    const settings = this.configService.getConfig().settings;
    try {
      console.log(`Starting Rebalance Cycle. DryRun: ${settings.dryRun}`);
      await this.performRebalanceCycle();
    } catch (e) {
      console.error('Error in rebalancing cycle', e);
    }

    if (this.isRunning) {
      const delayMs = settings.loopDelaySeconds * 1000;
      this.timer = setTimeout(() => this.runLoop(), delayMs);
    }
  }

  async performRebalanceCycle(): Promise<void> {
    console.log('--- Starting Snapshot Phase ---');
    const actionLog: string[] = [];

    const balances = await this.portfolioAnalyzer.fetchBalances();
    const prices = await this.portfolioAnalyzer.fetchPrices();
    const values = this.portfolioAnalyzer.calculatePortfolioValues(balances, prices);
    if (!values) {
      return;
    }

    const { totalValueUSD, currentValuesUSD } = values;
    console.log(`Total Portfolio Value: $${totalValueUSD.toFixed(2)}`);

    const drawdownPct = this.portfolioAnalyzer.updateAthAndCalculateDrawdown(totalValueUSD);
    const fiatDeploymentPct = this.portfolioAnalyzer.calculateFiatDeployment(
      drawdownPct,
      this.configService.getConfig().settings
    );

    if (fiatDeploymentPct.gt(0)) {
      console.log(
        `Drawdown Detected: ${drawdownPct.toFixed(2)}%. Fiat Deployment: ${fiatDeploymentPct.toFixed(2)}%`
      );
    }

    const effectiveUsdTarget = this.portfolioAnalyzer.calculateEffectiveUsdTarget(fiatDeploymentPct);
    const cryptoScaleFactor = this.portfolioAnalyzer.calculateCryptoScaleFactor(effectiveUsdTarget);

    const { buyOrders, sellOrders, actionLog: cycleActions } = this.portfolioAnalyzer.analyzeDeviations(
      totalValueUSD,
      currentValuesUSD,
      effectiveUsdTarget,
      cryptoScaleFactor
    );
    actionLog.push(...cycleActions);

    await this.orderExecutor.executeOrders(
      buyOrders,
      sellOrders,
      currentValuesUSD,
      prices,
      this.configService.getConfig().settings,
      actionLog
    );

    const snapshot = this.buildSnapshot(
      balances,
      prices,
      currentValuesUSD,
      totalValueUSD,
      effectiveUsdTarget,
      cryptoScaleFactor,
      drawdownPct,
      fiatDeploymentPct,
      actionLog
    );

    try {
      this.tradeHistoryService.addSnapshot(snapshot);
    } catch (e: unknown) {
      const message = e instanceof Error ? e.message : String(e);
      console.error('Failed to persist trade history snapshot', e);
      actionLog.push(`ERROR: Failed to persist trade history: ${message}`);
    }

    console.log('--- Cycle Complete ---');
  }

  private buildSnapshot(
    balances: Record<string, number>,
    prices: Record<string, Decimal>,
    currentValuesUSD: Record<string, Decimal>,
    totalPortfolioValueUSD: Decimal,
    effectiveUsdTarget: Decimal,
    cryptoScaleFactor: Decimal,
    drawdownPct: Decimal,
    fiatDeploymentPct: Decimal,
    actionLog: string[]
  ): PortfolioSnapshot {
    const assets: Record<string, AssetSnapshot> = {};

    for (const alloc of this.configService.getConfig().allocations) {
      const symbol = alloc.symbol;
      const isUsd = symbol.toUpperCase() === Asset.USD;

      const balanceVal = this.portfolioAnalyzer.resolveBalance(symbol, balances);
      const balance = new Decimal(balanceVal);
      const valUSD = currentValuesUSD[symbol] || new Decimal(0);
      const price = isUsd ? new Decimal(1) : prices[symbol] || new Decimal(1);

      const baseTargetPct = new Decimal(alloc.targetPercent);
      let snapshotTargetPct = baseTargetPct;
      let calcTargetPct: Decimal;

      if (isUsd) {
        calcTargetPct = effectiveUsdTarget;
      } else {
        calcTargetPct = baseTargetPct.mul(cryptoScaleFactor);
        snapshotTargetPct = calcTargetPct;
      }

      let currentPct = new Decimal(0);
      if (totalPortfolioValueUSD.gt(0)) {
        currentPct = valUSD.div(totalPortfolioValueUSD).toDecimalPlaces(4).mul(100);
      }

      const targetVal = totalPortfolioValueUSD.mul(calcTargetPct).div(100).toDecimalPlaces(4);
      const deviationUSD = valUSD.sub(targetVal);
      let devPct = new Decimal(0);

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
