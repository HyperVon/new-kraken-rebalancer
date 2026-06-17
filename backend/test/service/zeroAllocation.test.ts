import { describe, it, expect, beforeEach, vi } from 'vitest';
import { Decimal } from 'decimal.js';
import { FakeKrakenService } from './fakeKraken';
import { PortfolioAnalyzer } from '../../src/service/analyzer';
import { OrderExecutor } from '../../src/service/executor';
import { PortfolioManagerImpl } from '../../src/service/manager';
import { AppConfig } from '../../src/config/config';

Decimal.set({ rounding: Decimal.ROUND_HALF_UP });

describe('PortfolioManagerZeroAllocationTest', () => {
  let krakenService: FakeKrakenService;
  let configService: any;
  let tradeHistoryService: any;
  let portfolioStatsRepository: any;
  let portfolioAnalyzer: PortfolioAnalyzer;
  let orderExecutor: OrderExecutor;
  let portfolioManager: PortfolioManagerImpl;

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
    portfolioManager = new PortfolioManagerImpl(
      configService,
      tradeHistoryService,
      portfolioAnalyzer,
      orderExecutor
    );
  });

  it('testZeroAllocationToOtherAssetRebalance', async () => {
    const allocs = [
      { symbol: 'A', targetPercent: 0.0 },
      { symbol: 'B', targetPercent: 100.0 }
    ];

    const mockConfig: AppConfig = {
      kraken: { apiKey: 'k', privateKey: 's' },
      settings: {
        loopDelaySeconds: 0,
        deviationTriggerPercent: 2.0,
        dustThresholdUSD: 1.0,
        dryRun: false,
        fiatMaxDrawdown: 0.0,
        fiatDeploymentExponent: 1.0
      },
      allocations: allocs
    };
    configService.getConfig.mockReturnValue(mockConfig);

    krakenService.balanceSupplier = () => ({
      A: 10.0,
      B: 0.0,
      USD: 100.0
    });

    krakenService.pricesSupplier = () => ({
      AUSD: 100.0,
      BUSD: 50.0
    });

    await portfolioManager.performRebalanceCycle();

    const sellA = krakenService.executedOrders.find(
      o => o.pair === 'AUSD' && o.type === 'market' && o.side === 'sell'
    );
    expect(sellA).toBeDefined();
  });
});
