import { describe, it, expect, beforeEach, vi } from 'vitest';
import { Decimal } from 'decimal.js';
import { FakeKrakenService } from './fakeKraken';
import { PortfolioAnalyzer } from '../../src/service/analyzer';
import { OrderExecutor } from '../../src/service/executor';
import { PortfolioManager } from '../../src/service/manager';
import { AppConfig, Allocation } from '../../src/config/config';
import { createOrderResult } from '../../src/model/order';

Decimal.set({ rounding: Decimal.ROUND_HALF_UP });

describe('PortfolioManager edge cases', () => {
  let krakenService: FakeKrakenService;
  let configService: any;
  let tradeHistoryService: any;
  let portfolioStatsRepository: any;
  let portfolioAnalyzer: PortfolioAnalyzer;
  let orderExecutor: OrderExecutor;
  let portfolioManager: PortfolioManager;

  beforeEach(() => {
    krakenService = new FakeKrakenService();
    configService = {
      getConfig: vi.fn(),
      updateConfig: vi.fn(),
      loadConfig: vi.fn()
    };
    tradeHistoryService = {
      addSnapshot: vi.fn(),
      getLatestSnapshot: vi.fn(() => null),
      getHistory: vi.fn(() => []),
      subscribe: vi.fn(() => () => {})
    };
    portfolioStatsRepository = {
      load: vi.fn(() => ({ allTimeHigh: new Decimal(0) })),
      save: vi.fn()
    };

    portfolioAnalyzer = new PortfolioAnalyzer(
      krakenService,
      configService,
      portfolioStatsRepository
    );
    orderExecutor = new OrderExecutor(krakenService, portfolioAnalyzer);
    portfolioManager = new PortfolioManager(
      configService,
      tradeHistoryService,
      portfolioAnalyzer,
      orderExecutor
    );
  });

  it('performRebalanceCycle_NullBalances', async () => {
    krakenService.balanceSupplier = () => ({});
    const allocs = [{ symbol: 'USD', targetPercent: 100.0 }];
    configService.getConfig.mockReturnValue({
      kraken: { apiKey: 'k', privateKey: 's' },
      settings: {
        loopDelaySeconds: 0,
        deviationTriggerPercent: 2.0,
        dustThresholdUSD: 1.0,
        dryRun: true,
        fiatMaxDrawdown: 0.0,
        fiatDeploymentExponent: 1.0
      },
      allocations: allocs
    });

    await portfolioManager.performRebalanceCycle();
    expect(krakenService.getBalancesCallCount).toBe(1);
  });

  it('performRebalanceCycle_PriceNotFoundAbort', async () => {
    krakenService.balanceSupplier = () => ({ BTC: 1.0 });
    krakenService.pricesSupplier = () => ({});

    const allocs = [{ symbol: 'BTC', targetPercent: 100.0 }];
    configService.getConfig.mockReturnValue({
      kraken: { apiKey: 'k', privateKey: 's' },
      settings: {
        loopDelaySeconds: 0,
        deviationTriggerPercent: 2.0,
        dustThresholdUSD: 1.0,
        dryRun: true,
        fiatMaxDrawdown: 0.0,
        fiatDeploymentExponent: 1.0
      },
      allocations: allocs
    });

    await portfolioManager.performRebalanceCycle();
    expect(tradeHistoryService.addSnapshot).not.toHaveBeenCalled();
  });

  it('testDistributeFiatCorrection_NoCounterbalancingAssets', () => {
    const allDevs = {
      USD: new Decimal('100.0'),
      A: new Decimal('10.0')
    };
    const buyOrders: Record<string, Decimal> = {};
    const sellOrders: Record<string, Decimal> = {};

    portfolioAnalyzer.distributeFiatCorrection(
      new Decimal('100.0'),
      allDevs,
      buyOrders,
      sellOrders,
      []
    );

    expect(Object.keys(buyOrders).length).toBe(0);
    expect(Object.keys(sellOrders).length).toBe(0);
  });

  it('testFiatDeploymentRatioExceedsOne', async () => {
    portfolioStatsRepository.load.mockReturnValue({
      allTimeHigh: new Decimal('2000.0')
    });

    const allocs = [
      { symbol: 'A', targetPercent: 50.0 },
      { symbol: 'USD', targetPercent: 50.0 }
    ];
    configService.getConfig.mockReturnValue({
      kraken: { apiKey: 'k', privateKey: 's' },
      settings: {
        loopDelaySeconds: 0,
        deviationTriggerPercent: 2.0,
        dustThresholdUSD: 1.0,
        dryRun: true,
        fiatMaxDrawdown: 50.0,
        fiatDeploymentExponent: 1.0
      },
      allocations: allocs
    });

    krakenService.balanceSupplier = () => ({ A: 2.5, USD: 250.0 });
    krakenService.pricesSupplier = () => ({ AUSD: 100.0 });

    await portfolioManager.performRebalanceCycle();

    expect(tradeHistoryService.addSnapshot).toHaveBeenCalled();
    const snapshot = tradeHistoryService.addSnapshot.mock.calls[0][0];
    expect(snapshot.fiatDeploymentPercent.eq(100.0)).toBe(true);
  });

  it('testResolvePriceFromTicker_ExplicitPairAndFallback', () => {
    const rawPrices = { ETHEUR: 3000.0, ETHUSD: 3100.0 };

    const priceEth = portfolioAnalyzer.resolvePriceFromTicker('ETH', rawPrices);
    expect(priceEth.eq(3100.0)).toBe(true);

    const priceMissing = portfolioAnalyzer.resolvePriceFromTicker('LTC', rawPrices);
    expect(priceMissing.isZero()).toBe(true);
  });

  it('testExecuteOrders_ZeroPriceContinues', async () => {
    const buyOrders = { ETH: new Decimal(10.0) };
    const sellOrders = { BTC: new Decimal(10.0) };
    const currentValuesUSD = { USD: new Decimal(1000.0) };
    const prices: Record<string, Decimal> = {};
    const settings = {
      loopDelaySeconds: 0,
      deviationTriggerPercent: 2.0,
      dustThresholdUSD: 1.0,
      dryRun: false,
      fiatMaxDrawdown: 0.0,
      fiatDeploymentExponent: 1.0
    };
    const actionLog: string[] = [];

    await orderExecutor.executeOrders(
      buyOrders,
      sellOrders,
      currentValuesUSD,
      prices,
      settings,
      actionLog
    );

    expect(krakenService.executedOrders.length).toBe(0);
  });

  it('testExecuteOrders_UpdateCashException', async () => {
    const buyOrders = { ETH: new Decimal(10.0) };
    const sellOrders = { BTC: new Decimal(100.0) };
    const currentValuesUSD = { USD: new Decimal(1000.0) };
    const prices = {
      BTC: new Decimal(10.0),
      ETH: new Decimal(5.0)
    };
    const settings = {
      loopDelaySeconds: 0,
      deviationTriggerPercent: 2.0,
      dustThresholdUSD: 1.0,
      dryRun: false,
      fiatMaxDrawdown: 0.0,
      fiatDeploymentExponent: 1.0
    };
    const actionLog: string[] = [];

    krakenService.balanceSupplier = () => {
      throw new Error('balances api error');
    };

    await orderExecutor.executeOrders(
      buyOrders,
      sellOrders,
      currentValuesUSD,
      prices,
      settings,
      actionLog
    );

    expect(krakenService.executedOrders.length).toBe(2);
    expect(krakenService.executedOrders[0].pair).toBe('XBTUSD');
    expect(krakenService.executedOrders[0].side).toBe('sell');
    expect(krakenService.executedOrders[0].volume.eq(10.0)).toBe(true);
    expect(krakenService.executedOrders[1].pair).toBe('ETHUSD');
    expect(krakenService.executedOrders[1].side).toBe('buy');
    expect(krakenService.executedOrders[1].volume.eq(2.0)).toBe(true);
  });

  it('testExecuteOrders_UpdateBalancesNullOrEmpty', async () => {
    const buyOrders = { ETH: new Decimal(10.0) };
    const sellOrders = { BTC: new Decimal(100.0) };
    const currentValuesUSD = { USD: new Decimal(1000.0) };
    const prices = {
      BTC: new Decimal(10.0),
      ETH: new Decimal(5.0)
    };
    const settings = {
      loopDelaySeconds: 0,
      deviationTriggerPercent: 2.0,
      dustThresholdUSD: 1.0,
      dryRun: false,
      fiatMaxDrawdown: 0.0,
      fiatDeploymentExponent: 1.0
    };
    const actionLog: string[] = [];

    krakenService.balanceSupplier = () => ({});

    await orderExecutor.executeOrders(
      buyOrders,
      sellOrders,
      currentValuesUSD,
      prices,
      settings,
      actionLog
    );

    expect(krakenService.executedOrders.length).toBe(2);
    expect(krakenService.executedOrders[0].pair).toBe('XBTUSD');
    expect(krakenService.executedOrders[1].pair).toBe('ETHUSD');
  });

  it('testUpdateAthAndCalculateDrawdown_NewAth', () => {
    portfolioStatsRepository.load.mockReturnValue({
      allTimeHigh: new Decimal('1000.0')
    });
    const drawdown = portfolioAnalyzer.updateAthAndCalculateDrawdown(new Decimal('1500.0'));
    expect(drawdown.isZero()).toBe(true);
    expect(portfolioStatsRepository.save).toHaveBeenCalled();
  });

  it('testExecuteOrders_UpdateBalancesEmptyUsdOrNull', async () => {
    const buyOrders = { ETH: new Decimal(10.0) };
    const sellOrders = { BTC: new Decimal(100.0) };
    const currentValuesUSD = { USD: new Decimal(1000.0) };
    const prices = {
      BTC: new Decimal(10.0),
      ETH: new Decimal(5.0)
    };
    const settings = {
      loopDelaySeconds: 0,
      deviationTriggerPercent: 2.0,
      dustThresholdUSD: 1.0,
      dryRun: false,
      fiatMaxDrawdown: 0.0,
      fiatDeploymentExponent: 1.0
    };
    const actionLog: string[] = [];

    krakenService.balanceSupplier = () => ({ BTC: 1.0, ZUSD: 0.0 });

    await orderExecutor.executeOrders(
      buyOrders,
      sellOrders,
      currentValuesUSD,
      prices,
      settings,
      actionLog
    );

    expect(krakenService.executedOrders.length).toBe(2);
    expect(krakenService.executedOrders[0].pair).toBe('XBTUSD');
    expect(krakenService.executedOrders[1].pair).toBe('ETHUSD');
  });

  it('testExecuteOrders_SkipDustBuys', async () => {
    const buyOrders = { ETH: new Decimal(0.5) };
    const sellOrders = {};
    const currentValuesUSD = { USD: new Decimal(1000.0) };
    const prices = { ETH: new Decimal(5.0) };
    const settings = {
      loopDelaySeconds: 0,
      deviationTriggerPercent: 2.0,
      dustThresholdUSD: 1.0,
      dryRun: false,
      fiatMaxDrawdown: 0.0,
      fiatDeploymentExponent: 1.0
    };
    const actionLog: string[] = [];

    await orderExecutor.executeOrders(
      buyOrders,
      sellOrders,
      currentValuesUSD,
      prices,
      settings,
      actionLog
    );

    expect(krakenService.executedOrders.length).toBe(0);
  });

  it('testAnalyzeDeviations_UsdTriggeredButOrdersNotEmpty', () => {
    const currentValuesUSD = {
      USD: new Decimal(1100.0),
      BTC: new Decimal(900.0)
    };
    const totalVal = new Decimal(2000.0);
    const effUsdTarget = new Decimal(50.0);
    const cryptoScale = new Decimal(1.0);

    const allocs = [
      { symbol: 'USD', targetPercent: 50.0 },
      { symbol: 'BTC', targetPercent: 50.0 }
    ];
    configService.getConfig.mockReturnValue({
      kraken: { apiKey: 'k', privateKey: 's' },
      settings: {
        loopDelaySeconds: 0,
        deviationTriggerPercent: 2.0,
        dustThresholdUSD: 1.0,
        dryRun: true,
        fiatMaxDrawdown: 0.0,
        fiatDeploymentExponent: 1.0
      },
      allocations: allocs
    });

    const result = portfolioAnalyzer.analyzeDeviations(
      totalVal,
      currentValuesUSD,
      effUsdTarget,
      cryptoScale
    );

    expect(Object.keys(result.buyOrders).length).toBeGreaterThan(0);
  });

  it('testPerformRebalanceCycle_TradeHistorySaveIOException', async () => {
    krakenService.balanceSupplier = () => ({ USD: 1000.0 });
    const allocs = [{ symbol: 'USD', targetPercent: 100.0 }];
    configService.getConfig.mockReturnValue({
      kraken: { apiKey: 'k', privateKey: 's' },
      settings: {
        loopDelaySeconds: 0,
        deviationTriggerPercent: 2.0,
        dustThresholdUSD: 1.0,
        dryRun: true,
        fiatMaxDrawdown: 0.0,
        fiatDeploymentExponent: 1.0
      },
      allocations: allocs
    });

    tradeHistoryService.addSnapshot.mockImplementation(() => {
      throw new Error('Disk full');
    });

    await portfolioManager.performRebalanceCycle();
    expect(krakenService.getBalancesCallCount).toBe(1);
  });

  it('testResolvePriceFromTicker_FallbackBranches', () => {
    const rawPrices = { BTCEUR: 60000.0, XBTUSD: 61000.0 };
    const price = portfolioAnalyzer.resolvePriceFromTicker('BTC', rawPrices);
    expect(price.eq(61000.0)).toBe(true);

    const rawPricesOnlyEur = { XBTEUR: 55000.0 };
    const priceEurOnly = portfolioAnalyzer.resolvePriceFromTicker('BTC', rawPricesOnlyEur);
    expect(priceEurOnly.isZero()).toBe(true);
  });

  it('testCalculatePortfolioValues_PriceNotFoundAbort', () => {
    const balances = { USD: 1000.0, BTC: 1.0 };
    const prices = { USD: new Decimal(1.0) };

    const allocs = [
      { symbol: 'USD', targetPercent: 50.0 },
      { symbol: 'BTC', targetPercent: 50.0 }
    ];
    configService.getConfig.mockReturnValue({
      kraken: { apiKey: 'k', privateKey: 's' },
      settings: {
        loopDelaySeconds: 0,
        deviationTriggerPercent: 2.0,
        dustThresholdUSD: 1.0,
        dryRun: true,
        fiatMaxDrawdown: 0.0,
        fiatDeploymentExponent: 1.0
      },
      allocations: allocs
    });

    const result = portfolioAnalyzer.calculatePortfolioValues(balances, prices);
    expect(result).toBeNull();
  });

  it('testResolveBalance_FallbackChain', () => {
    expect(portfolioAnalyzer.resolveBalance('BTC', { BTC: 1.1 })).toBe(1.1);
    expect(portfolioAnalyzer.resolveBalance('BTC', { XBTC: 1.2 })).toBe(1.2);
    expect(portfolioAnalyzer.resolveBalance('USD', { ZUSD: 1.3 })).toBe(1.3);
    expect(portfolioAnalyzer.resolveBalance('BTC', { XBT: 1.4 })).toBe(1.4);
    expect(portfolioAnalyzer.resolveBalance('BTC', { XXBT: 1.5 })).toBe(1.5);
    expect(portfolioAnalyzer.resolveBalance('BTC', { ETH: 1.6 })).toBe(0.0);
  });

  it('testUpdateAthAndCalculateDrawdown_NegativeAth', () => {
    portfolioStatsRepository.load.mockReturnValue({
      allTimeHigh: new Decimal('-500.0')
    });
    const drawdown = portfolioAnalyzer.updateAthAndCalculateDrawdown(new Decimal('1000.0'));
    expect(drawdown.isZero()).toBe(true);
    expect(portfolioStatsRepository.save).toHaveBeenCalled();
    const saved = portfolioStatsRepository.save.mock.calls[0][0];
    expect(saved.allTimeHigh.eq('1000.0')).toBe(true);
  });

  it('testUpdateAthAndCalculateDrawdown_NullAth', () => {
    portfolioStatsRepository.load.mockReturnValue({
      allTimeHigh: null
    });
    const drawdown = portfolioAnalyzer.updateAthAndCalculateDrawdown(new Decimal('1200.0'));
    expect(drawdown.isZero()).toBe(true);
    expect(portfolioStatsRepository.save).toHaveBeenCalled();
    const saved = portfolioStatsRepository.save.mock.calls[0][0];
    expect(saved.allTimeHigh.eq('1200.0')).toBe(true);
  });

  it('testUpdateAthAndCalculateDrawdown_StatsSaveIOException', () => {
    portfolioStatsRepository.load.mockReturnValue({
      allTimeHigh: new Decimal('1000.0')
    });
    portfolioStatsRepository.save.mockImplementation(() => {
      throw new Error('Save failed');
    });

    const drawdown = portfolioAnalyzer.updateAthAndCalculateDrawdown(new Decimal('800.0'));
    expect(drawdown.eq(20.0)).toBe(true);
  });

  it('testAnalyzeDeviations_MissingSymbolInCurrentValues', () => {
    const totalVal = new Decimal(1000.0);
    const currentValuesUSD = { USD: new Decimal(1000.0) };
    const effUsdTarget = new Decimal(50.0);
    const cryptoScale = new Decimal(0.5);

    const allocs = [
      { symbol: 'USD', targetPercent: 50.0 },
      { symbol: 'BTC', targetPercent: 50.0 }
    ];
    configService.getConfig.mockReturnValue({
      kraken: { apiKey: 'k', privateKey: 's' },
      settings: {
        loopDelaySeconds: 0,
        deviationTriggerPercent: 2.0,
        dustThresholdUSD: 1.0,
        dryRun: true,
        fiatMaxDrawdown: 0.0,
        fiatDeploymentExponent: 1.0
      },
      allocations: allocs
    });

    const result = portfolioAnalyzer.analyzeDeviations(
      totalVal,
      currentValuesUSD,
      effUsdTarget,
      cryptoScale
    );

    expect(result.buyOrders['BTC'].eq(250.0)).toBe(true);
  });

  it('testAnalyzeDeviations_USDTriggerOnlyEnforcesFiatCorrection', () => {
    const allocs = [
      { symbol: 'USD', targetPercent: 20.0 },
      { symbol: 'BTC', targetPercent: 40.0 },
      { symbol: 'ETH', targetPercent: 40.0 }
    ];
    configService.getConfig.mockReturnValue({
      kraken: { apiKey: 'k', privateKey: 's' },
      settings: {
        loopDelaySeconds: 0,
        deviationTriggerPercent: 15.0,
        dustThresholdUSD: 1.0,
        dryRun: true,
        fiatMaxDrawdown: 0.0,
        fiatDeploymentExponent: 1.0
      },
      allocations: allocs
    });

    const currentValuesUSD = {
      USD: new Decimal('240.0'),
      BTC: new Decimal('380.0'),
      ETH: new Decimal('380.0')
    };

    const result = portfolioAnalyzer.analyzeDeviations(
      new Decimal('1000.0'),
      currentValuesUSD,
      new Decimal('20.0'),
      new Decimal(1.0)
    );

    expect(Object.keys(result.buyOrders).length).toBeGreaterThan(0);
    expect(result.buyOrders['BTC'].eq(20.0)).toBe(true);
    expect(result.buyOrders['ETH'].eq(20.0)).toBe(true);
  });

  it('testAnalyzeDeviations_dustDeviationIsIgnored', () => {
    const totalVal = new Decimal(1000.0);
    const currentValuesUSD = {
      USD: new Decimal('0.0001'),
      BTC: new Decimal('999.9999')
    };
    const effUsdTarget = new Decimal(0);
    const cryptoScale = new Decimal(2.0);
    const allocs = [
      { symbol: 'USD', targetPercent: 50.0 },
      { symbol: 'BTC', targetPercent: 50.0 }
    ];
    configService.getConfig.mockReturnValue({
      kraken: { apiKey: 'k', privateKey: 's' },
      settings: {
        loopDelaySeconds: 0,
        deviationTriggerPercent: 2.0,
        dustThresholdUSD: 5.0,
        dryRun: true,
        fiatMaxDrawdown: 0.0,
        fiatDeploymentExponent: 1.0
      },
      allocations: allocs
    });

    const result = portfolioAnalyzer.analyzeDeviations(
      totalVal,
      currentValuesUSD,
      effUsdTarget,
      cryptoScale
    );

    expect(Object.keys(result.buyOrders).length).toBe(0);
    expect(Object.keys(result.sellOrders).length).toBe(0);
  });

  it('testExecuteOrders_DryRunAndSellsSuccess', async () => {
    const buyOrders = {};
    const sellOrders = { BTC: new Decimal(100.0) };
    const currentValuesUSD = { USD: new Decimal(1000.0) };
    const prices = { BTC: new Decimal(10.0) };
    const settings = {
      loopDelaySeconds: 0,
      deviationTriggerPercent: 2.0,
      dustThresholdUSD: 1.0,
      dryRun: true,
      fiatMaxDrawdown: 0.0,
      fiatDeploymentExponent: 1.0
    };
    const actionLog: string[] = [];

    krakenService.orderResultFactory = (pair, type, side, volume) =>
      createOrderResult(true, pair, side, volume, true);

    await orderExecutor.executeOrders(
      buyOrders,
      sellOrders,
      currentValuesUSD,
      prices,
      settings,
      actionLog
    );

    expect(actionLog.some(l => l.includes('[DRY RUN] SELL BTC'))).toBe(true);
  });

  it('testExecuteOrders_FailedSellDoesNotIncrementCash', async () => {
    const buyOrders = {};
    const sellOrders = { BTC: new Decimal(100.0) };
    const currentValuesUSD = { USD: new Decimal(1000.0) };
    const prices = { BTC: new Decimal(10.0) };
    const settings = {
      loopDelaySeconds: 0,
      deviationTriggerPercent: 2.0,
      dustThresholdUSD: 1.0,
      dryRun: false,
      fiatMaxDrawdown: 0.0,
      fiatDeploymentExponent: 1.0
    };
    const actionLog: string[] = [];

    krakenService.orderResultFactory = (pair, type, side, volume) =>
      createOrderResult(false, pair, side, volume, false, 'Invalid amount');

    await orderExecutor.executeOrders(
      buyOrders,
      sellOrders,
      currentValuesUSD,
      prices,
      settings,
      actionLog
    );

    expect(actionLog.some(l => l.includes('FAILED SELL BTC: Invalid amount'))).toBe(true);
  });

  it('testRefreshUsdBalanceAfterSells_EarlyReturnAndTimeout', async () => {
    const buyOrders = {};
    const sellOrders = { BTC: new Decimal(100.0) };
    const currentValuesUSD = { USD: new Decimal(1000.0) };
    const prices = { BTC: new Decimal(10.0) };
    const settings = {
      loopDelaySeconds: 0,
      deviationTriggerPercent: 2.0,
      dustThresholdUSD: 1.0,
      dryRun: false,
      fiatMaxDrawdown: 0.0,
      fiatDeploymentExponent: 1.0
    };

    krakenService.getBalancesCallCount = 0;
    krakenService.balanceSupplier = () => ({ USD: 1050.0 });

    await orderExecutor.executeOrders(
      buyOrders,
      sellOrders,
      currentValuesUSD,
      prices,
      settings,
      []
    );

    expect(krakenService.getBalancesCallCount).toBe(1);

    krakenService.getBalancesCallCount = 0;
    krakenService.balanceSupplier = () => ({ USD: 900.0 });

    await orderExecutor.executeOrders(
      buyOrders,
      sellOrders,
      currentValuesUSD,
      prices,
      settings,
      []
    );

    expect(krakenService.getBalancesCallCount).toBe(3);
  });

  it('testLogOrderResult', () => {
    const log1: string[] = [];
    orderExecutor['logOrderResult'](
      createOrderResult(true, 'XBTUSD', 'sell', new Decimal(1.0), true),
      log1,
      'BTC',
      new Decimal(1.0),
      new Decimal(10.0),
      'SELL'
    );
    expect(log1[0]).toBe('[DRY RUN] SELL BTC Volume: 1 Value: $10.00');

    const log2: string[] = [];
    orderExecutor['logOrderResult'](
      createOrderResult(true, 'XBTUSD', 'buy', new Decimal(1.0), false),
      log2,
      'BTC',
      new Decimal(1.0),
      new Decimal(10.0),
      'BUY'
    );
    expect(log2[0]).toBe('BUY BTC Volume: 1 Cost: $10.00');
  });
});
