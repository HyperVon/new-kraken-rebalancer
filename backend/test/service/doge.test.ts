import { describe, it, expect, beforeEach, vi, Mock } from 'vitest';
import { Decimal } from 'decimal.js';
import { FakeKrakenService } from './fakeKraken';
import { PortfolioAnalyzer } from '../../src/service/analyzer';
import { OrderExecutor } from '../../src/service/executor';
import { PortfolioManager } from '../../src/service/manager';
import { AppConfig, ConfigService } from '../../src/config/config';
import { TradeHistoryService } from '../../src/service/history';
import { PortfolioStatsRepository } from '../../src/repository/stats';

Decimal.set({ rounding: Decimal.ROUND_HALF_UP });

describe('PortfolioManager DOGE and BTC mappings', () => {
  let krakenService: FakeKrakenService;
  let configService: {
    getConfig: Mock;
    updateConfig: Mock;
    loadConfig: Mock;
  };
  let tradeHistoryService: {
    addSnapshot: Mock;
    getLatestSnapshot: Mock;
    getHistory: Mock;
    subscribe: Mock;
  };
  let portfolioStatsRepository: {
    load: Mock;
    save: Mock;
  };
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
       configService as unknown as ConfigService,
       portfolioStatsRepository as unknown as PortfolioStatsRepository
     );
     orderExecutor = new OrderExecutor(krakenService, portfolioAnalyzer);
     portfolioManager = new PortfolioManager(
       configService as unknown as ConfigService,
       tradeHistoryService as unknown as TradeHistoryService,
       portfolioAnalyzer,
       orderExecutor
     );
  });

  it('should successfully map and parse DOGE (XDG) balances and prices', async () => {
    const mockConfig: AppConfig = {
      kraken: { apiKey: 'k', privateKey: 's' },
      settings: {
        loopDelaySeconds: 60,
        deviationTriggerPercent: 2.0,
        dustThresholdUSD: 1.0,
        dryRun: true,
        fiatMaxDrawdown: 0.0,
        fiatDeploymentExponent: 1.0
      },
      allocations: [
        { symbol: 'DOGE', targetPercent: 50.0 },
        { symbol: 'USD', targetPercent: 50.0 }
      ]
    };
    configService.getConfig.mockReturnValue(mockConfig);

    krakenService.balanceSupplier = () => ({ XDG: 1000.0, ZUSD: 500.0 });
    krakenService.pricesSupplier = (pairs: string): Record<string, number> => {
      if (pairs.includes('XDGUSD')) {
        return { XDGUSD: 0.10 };
      }
      return {};
    };

    await portfolioManager.performRebalanceCycle();

    expect(krakenService.getBalancesCallCount).toBe(1);
  });

  it('should successfully map and parse BTC (XBT) balances and prices', async () => {
    const mockConfig: AppConfig = {
      kraken: { apiKey: 'k', privateKey: 's' },
      settings: {
        loopDelaySeconds: 60,
        deviationTriggerPercent: 2.0,
        dustThresholdUSD: 1.0,
        dryRun: true,
        fiatMaxDrawdown: 0.0,
        fiatDeploymentExponent: 1.0
      },
      allocations: [
        { symbol: 'BTC', targetPercent: 50.0 },
        { symbol: 'USD', targetPercent: 50.0 }
      ]
    };
    configService.getConfig.mockReturnValue(mockConfig);

    krakenService.balanceSupplier = () => ({ XXBT: 1.0, ZUSD: 50000.0 });
    krakenService.pricesSupplier = (pairs: string): Record<string, number> => {
      if (pairs.includes('XXBTZUSD') || pairs.includes('XBTUSD')) {
        return { XXBTZUSD: 50000.0 };
      }
      return {};
    };

    await portfolioManager.performRebalanceCycle();

    expect(krakenService.getBalancesCallCount).toBe(1);
  });
});
