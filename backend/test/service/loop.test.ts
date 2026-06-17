import { describe, it, expect, beforeEach, vi, afterEach } from 'vitest';
import { Decimal } from 'decimal.js';
import { FakeKrakenService } from './fakeKraken';
import { PortfolioAnalyzer } from '../../src/service/analyzer';
import { OrderExecutor } from '../../src/service/executor';
import { PortfolioManagerImpl } from '../../src/service/manager';

describe('PortfolioManagerLoopTest', () => {
  let krakenService: FakeKrakenService;
  let configService: any;
  let tradeHistoryService: any;
  let portfolioManager: PortfolioManagerImpl;

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
    const repo = {
      load: vi.fn(() => ({ allTimeHigh: new Decimal(0) })),
      save: vi.fn()
    };
    const portfolioAnalyzer = new PortfolioAnalyzer(krakenService, configService, repo);
    const orderExecutor = new OrderExecutor(krakenService, portfolioAnalyzer);
    portfolioManager = new PortfolioManagerImpl(
      configService,
      tradeHistoryService,
      portfolioAnalyzer,
      orderExecutor
    );
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it('startRebalancingLoop_RunsWhenEnabled', async () => {
    krakenService.balanceSupplier = () => ({});
    portfolioManager.startRebalancingLoop();
    expect(krakenService.getBalancesCallCount).toBe(1);

    portfolioManager.stopRebalancingLoop();
    await vi.runOnlyPendingTimersAsync();
  });

  it('stopRebalancingLoop_StopsExecution', async () => {
    portfolioManager.startRebalancingLoop();
    expect(krakenService.getBalancesCallCount).toBe(1);

    portfolioManager.stopRebalancingLoop();
    await vi.runOnlyPendingTimersAsync();
    expect(krakenService.getBalancesCallCount).toBe(1);
  });

  it('checkAndRunCycle_HandlesExceptionGracefully', async () => {
    krakenService.balanceSupplier = () => {
      throw new Error('API Error!');
    };
    portfolioManager.startRebalancingLoop();
    expect(krakenService.getBalancesCallCount).toBe(1);

    portfolioManager.stopRebalancingLoop();
    await vi.runOnlyPendingTimersAsync();
  });
});
