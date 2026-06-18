import { describe, it, expect, beforeEach, vi } from 'vitest';
import { Decimal } from 'decimal.js';
import { FakeKrakenService } from './fakeKraken';
import { PortfolioAnalyzer } from '../../src/service/analyzer';
import { OrderExecutor } from '../../src/service/executor';
import { PortfolioManager } from '../../src/service/manager';
import { AppConfig } from '../../src/config/config';

Decimal.set({ rounding: Decimal.ROUND_HALF_UP });

describe('PortfolioManager order execution', () => {
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

  it('should execute sell orders before buy orders', async () => {
    const allocs = [
      { symbol: 'A', targetPercent: 10.0 },
      { symbol: 'B', targetPercent: 90.0 },
      { symbol: 'USD', targetPercent: 0.0 }
    ];

    const mockConfig: AppConfig = {
      kraken: { apiKey: 'k', privateKey: 's' },
      settings: {
        loopDelaySeconds: 0,
        deviationTriggerPercent: 1.0,
        dustThresholdUSD: 1.0,
        dryRun: false,
        fiatMaxDrawdown: 0.0,
        fiatDeploymentExponent: 1.0
      },
      allocations: allocs
    };
    configService.getConfig.mockReturnValue(mockConfig);

    krakenService.balanceSupplier = () => ({ A: 5.0, B: 50.0, USD: 0.0 });
    krakenService.pricesSupplier = () => ({ AUSD: 100.0, BUSD: 10.0 });

    await portfolioManager.performRebalanceCycle();

    expect(krakenService.executedOrders.length).toBe(2);
    expect(krakenService.executedOrders[0].pair).toBe('AUSD');
    expect(krakenService.executedOrders[0].side).toBe('sell');
    expect(krakenService.executedOrders[1].pair).toBe('BUSD');
    expect(krakenService.executedOrders[1].side).toBe('buy');
  });

  it('should skip sell orders below the dust threshold', async () => {
    const allocs = [
      { symbol: 'A', targetPercent: 10.0 },
      { symbol: 'USD', targetPercent: 90.0 }
    ];

    const mockConfig: AppConfig = {
      kraken: { apiKey: 'k', privateKey: 's' },
      settings: {
        loopDelaySeconds: 0,
        deviationTriggerPercent: 0.1,
        dustThresholdUSD: 10.0,
        dryRun: false,
        fiatMaxDrawdown: 0.0,
        fiatDeploymentExponent: 1.0
      },
      allocations: allocs
    };
    configService.getConfig.mockReturnValue(mockConfig);

    krakenService.balanceSupplier = () => ({ A: 1.05, USD: 895.0 });
    krakenService.pricesSupplier = () => ({ AUSD: 100.0 });

    await portfolioManager.performRebalanceCycle();

    const sellA = krakenService.executedOrders.find(o => o.pair === 'AUSD' && o.side === 'sell');
    expect(sellA).toBeUndefined();
  });

  it('should fall back to projected cash when balance verification fails', async () => {
    const allocs = [
      { symbol: 'A', targetPercent: 10.0 },
      { symbol: 'B', targetPercent: 90.0 }
    ];

    const mockConfig: AppConfig = {
      kraken: { apiKey: 'k', privateKey: 's' },
      settings: {
        loopDelaySeconds: 0,
        deviationTriggerPercent: 1.0,
        dustThresholdUSD: 1.0,
        dryRun: false,
        fiatMaxDrawdown: 0.0,
        fiatDeploymentExponent: 1.0
      },
      allocations: allocs
    };
    configService.getConfig.mockReturnValue(mockConfig);

    const initialBalances = { A: 5.0, B: 50.0, USD: 0.0 };
    let callCount = 0;
    krakenService.balanceSupplier = () => {
      callCount++;
      if (callCount === 1) {
        return initialBalances;
      } else {
        throw new Error('API Error during verification!');
      }
    };

    krakenService.pricesSupplier = () => ({ AUSD: 100.0, BUSD: 10.0 });

    await portfolioManager.performRebalanceCycle();

    expect(krakenService.executedOrders.length).toBe(2);
    expect(krakenService.executedOrders[0].pair).toBe('AUSD');
    expect(krakenService.executedOrders[0].side).toBe('sell');
    expect(krakenService.executedOrders[1].pair).toBe('BUSD');
    expect(krakenService.executedOrders[1].side).toBe('buy');
  });

  it('should adjust buy volume based on updated cash balances after partial sell fills', async () => {
    const allocs = [
      { symbol: 'A', targetPercent: 10.0 },
      { symbol: 'B', targetPercent: 90.0 }
    ];

    const mockConfig: AppConfig = {
      kraken: { apiKey: 'k', privateKey: 's' },
      settings: {
        loopDelaySeconds: 0,
        deviationTriggerPercent: 1.0,
        dustThresholdUSD: 1.0,
        dryRun: false,
        fiatMaxDrawdown: 0.0,
        fiatDeploymentExponent: 1.0
      },
      allocations: allocs
    };
    configService.getConfig.mockReturnValue(mockConfig);

    const initialBalances = { A: 5.0, B: 50.0, USD: 0.0 };
    const updatedBalances = { A: 2.0, B: 50.0, USD: 200.0 };

    let callCount = 0;
    krakenService.balanceSupplier = () => {
      callCount++;
      return callCount === 1 ? initialBalances : updatedBalances;
    };

    krakenService.pricesSupplier = () => ({ AUSD: 100.0, BUSD: 10.0 });

    await portfolioManager.performRebalanceCycle();

    expect(krakenService.executedOrders.length).toBe(2);
    expect(krakenService.executedOrders[0].pair).toBe('AUSD');
    expect(krakenService.executedOrders[0].side).toBe('sell');
    expect(krakenService.executedOrders[1].pair).toBe('BUSD');
    expect(krakenService.executedOrders[1].side).toBe('buy');
    expect(krakenService.executedOrders[1].volume.sub(19.8).abs().toNumber()).toBeLessThan(0.1);
  });
});
