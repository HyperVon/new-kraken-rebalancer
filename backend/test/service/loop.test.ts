import { describe, it, expect, beforeEach, vi, afterEach, Mock } from 'vitest';
import { Decimal } from 'decimal.js';
import { FakeKrakenService } from './fakeKraken';
import { PortfolioAnalyzer } from '../../src/service/analyzer';
import { OrderExecutor } from '../../src/service/executor';
import { PortfolioManager } from '../../src/service/manager';
import { ConfigService } from '../../src/config/config';
import { TradeHistoryService } from '../../src/service/history';
import { PortfolioStatsRepository } from '../../src/repository/stats';

describe('PortfolioManager loop handling', () => {
  let krakenService: FakeKrakenService;
  let configService: {
    getConfig: Mock;
  };
  let tradeHistoryService: {
    addSnapshot: Mock;
    getLatestSnapshot: Mock;
    getHistory: Mock;
    subscribe: Mock;
  };
  let portfolioManager: PortfolioManager;

  beforeEach(() => {
    vi.useFakeTimers();
    krakenService = new FakeKrakenService();
    configService = {
      getConfig: vi.fn(() => ({
        kraken: { apiKey: 'k', privateKey: 's' },
        settings: {
          loopDelaySeconds: 60,
          deviationTriggerPercent: 2.0,
          dustThresholdUSD: 1.0,
          dryRun: true,
          fiatMaxDrawdown: 0.0,
          fiatDeploymentExponent: 1.0
        },
        allocations: []
      }))
    };
    tradeHistoryService = {
      addSnapshot: vi.fn(),
      getLatestSnapshot: vi.fn(() => null),
      getHistory: vi.fn(() => []),
      subscribe: vi.fn(() => () => {})
    };
    const repo: {
      load: Mock;
      save: Mock;
    } = {
      load: vi.fn(() => ({ allTimeHigh: new Decimal(0) })),
      save: vi.fn()
    };
    const portfolioAnalyzer = new PortfolioAnalyzer(
      krakenService,
      configService as unknown as ConfigService,
      repo as unknown as PortfolioStatsRepository
    );
    const orderExecutor = new OrderExecutor(krakenService, portfolioAnalyzer);
    portfolioManager = new PortfolioManager(
      configService as unknown as ConfigService,
      tradeHistoryService as unknown as TradeHistoryService,
      portfolioAnalyzer,
      orderExecutor
    );
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it('should run the cycle when startRebalancingLoop is enabled', async () => {
    krakenService.balanceSupplier = () => ({});
    portfolioManager.startRebalancingLoop();
    expect(krakenService.getBalancesCallCount).toBe(1);

    portfolioManager.stopRebalancingLoop();
    await vi.runOnlyPendingTimersAsync();
  });

  it('should stop execution when stopRebalancingLoop is called', async () => {
    portfolioManager.startRebalancingLoop();
    expect(krakenService.getBalancesCallCount).toBe(1);

    portfolioManager.stopRebalancingLoop();
    await vi.runOnlyPendingTimersAsync();
    expect(krakenService.getBalancesCallCount).toBe(1);
  });

  it('should handle exceptions gracefully during check and run cycle', async () => {
    krakenService.balanceSupplier = () => {
      throw new Error('API Error!');
    };
    portfolioManager.startRebalancingLoop();
    expect(krakenService.getBalancesCallCount).toBe(1);

    portfolioManager.stopRebalancingLoop();
    await vi.runOnlyPendingTimersAsync();
  });
});
