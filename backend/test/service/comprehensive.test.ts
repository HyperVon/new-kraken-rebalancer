import { describe, it, expect, beforeEach, vi } from 'vitest';
import { Decimal } from 'decimal.js';
import { FakeKrakenService } from './fakeKraken';
import { PortfolioAnalyzer } from '../../src/service/analyzer';
import { OrderExecutor } from '../../src/service/executor';
import { PortfolioManagerImpl } from '../../src/service/manager';
import { AppConfig, Allocation } from '../../src/config/config';

Decimal.set({ rounding: Decimal.ROUND_HALF_UP });

describe('PortfolioManagerComprehensiveTest', () => {
  let krakenService: FakeKrakenService;
  let configService: any;
  let tradeHistoryService: any;
  let portfolioStatsRepository: any;
  let portfolioAnalyzer: PortfolioAnalyzer;
  let orderExecutor: OrderExecutor;
  let portfolioManager: PortfolioManagerImpl;

  const makeConfig = (...allocs: Allocation[]): AppConfig => ({
    kraken: { apiKey: 'k', privateKey: 's' },
    settings: {
      loopDelaySeconds: 60,
      deviationTriggerPercent: 2.0,
      dustThresholdUSD: 1.0,
      dryRun: false,
      fiatMaxDrawdown: 0.0,
      fiatDeploymentExponent: 1.0
    },
    allocations: allocs
  });

  beforeEach(() => {
    krakenService = new FakeKrakenService();
    configService = {
      getConfig: vi.fn(),
      updateConfig: vi.fn(),
      loadConfig: vi.fn()
    };
    tradeHistoryService = {
      addSnapshot: vi.fn(),
      getHistory: vi.fn(() => []),
      getLatestSnapshot: vi.fn(() => null),
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
    portfolioManager = new PortfolioManagerImpl(
      configService,
      tradeHistoryService,
      portfolioAnalyzer,
      orderExecutor
    );
  });

  it('Scenario: Balanced Portfolio - No Trades Expected', async () => {
    configService.getConfig.mockReturnValue(
      makeConfig(
        { symbol: 'A', targetPercent: 50.0 },
        { symbol: 'B', targetPercent: 50.0 }
      )
    );
    krakenService.pricesSupplier = () => ({ AUSD: 100.0, BUSD: 100.0 });
    krakenService.balanceSupplier = () => ({ A: 10.0, B: 10.0 });

    await portfolioManager.performRebalanceCycle();

    expect(krakenService.executedOrders.length).toBe(0);
  });

  it('Scenario: Simple Rebalance - Asset A Overweight, B Underweight', async () => {
    configService.getConfig.mockReturnValue(
      makeConfig(
        { symbol: 'A', targetPercent: 50.0 },
        { symbol: 'B', targetPercent: 50.0 }
      )
    );
    krakenService.pricesSupplier = () => ({ AUSD: 100.0, BUSD: 100.0 });
    krakenService.balanceSupplier = () => ({ A: 11.0, B: 9.0 });

    await portfolioManager.performRebalanceCycle();

    expect(krakenService.executedOrders.length).toBe(2);

    const sell = krakenService.executedOrders.find(o => o.side === 'sell');
    expect(sell).toBeDefined();
    expect(sell!.pair).toBe('AUSD');
    expect(sell!.volume.sub(1).abs().toNumber()).toBeLessThan(0.0001);

    const buy = krakenService.executedOrders.find(o => o.side === 'buy');
    expect(buy).toBeDefined();
    expect(buy!.pair).toBe('BUSD');
    expect(buy!.volume.sub(1).abs().toNumber()).toBeLessThan(0.0001);
  });

  it('Scenario: Fiat Deposit - Distribute Excess Cash', async () => {
    configService.getConfig.mockReturnValue(
      makeConfig(
        { symbol: 'A', targetPercent: 40.0 },
        { symbol: 'B', targetPercent: 40.0 },
        { symbol: 'USD', targetPercent: 20.0 }
      )
    );
    krakenService.pricesSupplier = () => ({ AUSD: 100.0, BUSD: 100.0 });
    krakenService.balanceSupplier = () => ({
      A: 4.0,
      B: 4.0,
      USD: 1200.0
    });

    await portfolioManager.performRebalanceCycle();

    const buyA = krakenService.executedOrders.find(o => o.pair === 'AUSD' && o.side === 'buy');
    expect(buyA).toBeDefined();
    expect(buyA!.volume.sub(4.0).abs().toNumber()).toBeLessThan(0.0001);

    const buyB = krakenService.executedOrders.find(o => o.pair === 'BUSD' && o.side === 'buy');
    expect(buyB).toBeDefined();
    expect(buyB!.volume.sub(4.0).abs().toNumber()).toBeLessThan(0.0001);
  });

  it('Scenario: Fiat Withdrawal - Prevent Buys if No Cash', async () => {
    configService.getConfig.mockReturnValue(
      makeConfig(
        { symbol: 'A', targetPercent: 10.0 },
        { symbol: 'B', targetPercent: 90.0 }
      )
    );
    krakenService.pricesSupplier = () => ({ AUSD: 100.0, BUSD: 100.0 });
    krakenService.balanceSupplier = () => ({ A: 5.0, B: 0.0, USD: 0.0 });

    await portfolioManager.performRebalanceCycle();

    const sell = krakenService.executedOrders.find(o => o.side === 'sell');
    expect(sell).toBeDefined();
    expect(sell!.pair).toBe('AUSD');
    expect(sell!.volume.sub(4.5).abs().toNumber()).toBeLessThan(0.0001);

    const buy = krakenService.executedOrders.find(o => o.side === 'buy');
    expect(buy).toBeDefined();
    expect(buy!.pair).toBe('BUSD');
    expect(buy!.volume.sub(4.5).abs().toNumber()).toBeLessThan(0.05);
  });

  it('Scenario: Dust Thresholds - Skip Tiny Orders', async () => {
    configService.getConfig.mockReturnValue(
      makeConfig(
        { symbol: 'A', targetPercent: 50.0 },
        { symbol: 'B', targetPercent: 50.0 }
      )
    );
    krakenService.pricesSupplier = () => ({ AUSD: 100.0, BUSD: 100.0 });
    krakenService.balanceSupplier = () => ({ A: 10.005, B: 9.995 });

    await portfolioManager.performRebalanceCycle();

    expect(krakenService.executedOrders.length).toBe(0);
  });

  it('Scenario: 0% Allocation - Sell Everything', async () => {
    configService.getConfig.mockReturnValue(
      makeConfig(
        { symbol: 'A', targetPercent: 0.0 },
        { symbol: 'USD', targetPercent: 100.0 }
      )
    );
    krakenService.pricesSupplier = () => ({ AUSD: 100.0 });
    krakenService.balanceSupplier = () => ({ A: 10.0, USD: 0.0 });

    await portfolioManager.performRebalanceCycle();

    const sell = krakenService.executedOrders.find(o => o.side === 'sell');
    expect(sell).toBeDefined();
    expect(sell!.pair).toBe('AUSD');
    expect(sell!.volume.sub(10.0).abs().toNumber()).toBeLessThan(0.0001);
  });

  it('Scenario: New Asset Entry - Buy from Scratch', async () => {
    configService.getConfig.mockReturnValue(
      makeConfig(
        { symbol: 'A', targetPercent: 100.0 },
        { symbol: 'USD', targetPercent: 0.0 }
      )
    );
    krakenService.pricesSupplier = () => ({ AUSD: 100.0 });
    krakenService.balanceSupplier = () => ({ A: 0.0, USD: 1000.0 });

    await portfolioManager.performRebalanceCycle();

    const buy = krakenService.executedOrders.find(o => o.side === 'buy');
    expect(buy).toBeDefined();
    expect(buy!.pair).toBe('AUSD');
    expect(buy!.volume.sub(10.0).abs().toNumber()).toBeLessThan(0.0001);
  });

  it('Scenario: Market Moon - All Assets Overweight (Sell to Rebalance)', async () => {
    configService.getConfig.mockReturnValue(
      makeConfig(
        { symbol: 'A', targetPercent: 50.0 },
        { symbol: 'USD', targetPercent: 50.0 }
      )
    );
    krakenService.pricesSupplier = () => ({ AUSD: 200.0 });
    krakenService.balanceSupplier = () => ({ A: 10.0, USD: 1000.0 });

    await portfolioManager.performRebalanceCycle();

    const sell = krakenService.executedOrders.find(o => o.side === 'sell');
    expect(sell).toBeDefined();
    expect(sell!.pair).toBe('AUSD');
    expect(sell!.volume.sub(2.5).abs().toNumber()).toBeLessThan(0.0001);
  });

  it('Scenario: Price Lookup Failure - Abort Cycle', async () => {
    configService.getConfig.mockReturnValue(
      makeConfig(
        { symbol: 'A', targetPercent: 100.0 },
        { symbol: 'USD', targetPercent: 0.0 }
      )
    );
    krakenService.pricesSupplier = () => ({});
    krakenService.balanceSupplier = () => ({ A: 10.0 });

    await portfolioManager.performRebalanceCycle();

    expect(krakenService.executedOrders.length).toBe(0);
  });

  it('Scenario: Partial Price Lookup Failure - Skip Asset', async () => {
    configService.getConfig.mockReturnValue(
      makeConfig(
        { symbol: 'A', targetPercent: 50.0 },
        { symbol: 'B', targetPercent: 50.0 }
      )
    );
    krakenService.pricesSupplier = () => ({ BUSD: 100.0 });
    krakenService.balanceSupplier = () => ({ A: 10.0, B: 20.0 });

    await portfolioManager.performRebalanceCycle();

    expect(krakenService.executedOrders.length).toBe(0);
  });

  it('Scenario: API Exception - Safe Recovery', async () => {
    configService.getConfig.mockReturnValue(
      makeConfig(
        { symbol: 'A', targetPercent: 100.0 },
        { symbol: 'USD', targetPercent: 0.0 }
      )
    );
    krakenService.pricesSupplier = () => ({ AUSD: 100.0 });
    krakenService.balanceSupplier = () => ({ A: 0.0, USD: 1000.0 });
    krakenService.orderResultFactory = (pair, type, side, volume) => ({
      success: false,
      pair,
      side,
      volume,
      dryRun: false,
      errorMessage: 'Kraken Down'
    });

    await portfolioManager.performRebalanceCycle();

    expect(krakenService.executedOrders.length).toBe(1);
    const order = krakenService.executedOrders[0];
    expect(order.pair).toBe('AUSD');
    expect(order.side).toBe('buy');

    expect(tradeHistoryService.addSnapshot).toHaveBeenCalled();
    const snapshot = tradeHistoryService.addSnapshot.mock.calls[0][0];
    expect(snapshot.actions.some((a: string) => a.startsWith('FAILED BUY A'))).toBe(true);
  });
});
