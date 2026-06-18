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

describe('PortfolioManager drawdown handling', () => {
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

    const settings = {
      loopDelaySeconds: 60,
      deviationTriggerPercent: 2.0,
      dustThresholdUSD: 1.0,
      dryRun: false,
      fiatMaxDrawdown: 50.0,
      fiatDeploymentExponent: 1.0
    };
    const appConfig: AppConfig = {
      kraken: { apiKey: 'k', privateKey: 's' },
      settings,
      allocations: []
    };
    configService.getConfig.mockReturnValue(appConfig);
  });

  it('should successfully calculate drawdown and adjust fiat deployment', async () => {
    portfolioStatsRepository.load.mockReturnValue({
      allTimeHigh: new Decimal('2000.0')
    });

    const allocs = [
      { symbol: 'A', targetPercent: 50.0 },
      { symbol: 'USD', targetPercent: 50.0 }
    ];

    const appConfig: AppConfig = {
      kraken: { apiKey: 'k', privateKey: 's' },
      settings: {
        loopDelaySeconds: 60,
        deviationTriggerPercent: 2.0,
        dustThresholdUSD: 1.0,
        dryRun: false,
        fiatMaxDrawdown: 50.0,
        fiatDeploymentExponent: 1.0
      },
      allocations: allocs
    };
    configService.getConfig.mockReturnValue(appConfig);

    krakenService.pricesSupplier = () => ({ AUSD: 100.0 });
    krakenService.balanceSupplier = () => ({
      A: 7.5,
      USD: 750.0
    });

    await portfolioManager.performRebalanceCycle();

    expect(krakenService.executedOrders.length).toBe(1);
    const order = krakenService.executedOrders[0];
    expect(order.pair).toBe('AUSD');
    expect(order.type).toBe('market');
    expect(order.side).toBe('buy');
    expect(order.volume.sub(3.75).abs().toNumber()).toBeLessThan(0.01);

    expect(tradeHistoryService.addSnapshot).toHaveBeenCalled();
    const snapshot = tradeHistoryService.addSnapshot.mock.calls[0][0];

    expect(new Decimal(snapshot.drawdownPercent).eq('25.0')).toBe(true);
    expect(new Decimal(snapshot.fiatDeploymentPercent).eq('50.0')).toBe(true);
    expect(new Decimal(snapshot.effectiveUsdTargetPercent).eq('25.0')).toBe(true);
  });

  it('should update the portfolio All-Time High when a new ATH is reached', async () => {
    const stats = { allTimeHigh: new Decimal('1000.0') };
    portfolioStatsRepository.load.mockReturnValue(stats);

    const allocs = [{ symbol: 'USD', targetPercent: 100.0 }];
    const appConfig: AppConfig = {
      kraken: { apiKey: 'k', privateKey: 's' },
      settings: {
        loopDelaySeconds: 60,
        deviationTriggerPercent: 2.0,
        dustThresholdUSD: 1.0,
        dryRun: false,
        fiatMaxDrawdown: 50.0,
        fiatDeploymentExponent: 1.0
      },
      allocations: allocs
    };
    configService.getConfig.mockReturnValue(appConfig);
    krakenService.pricesSupplier = () => ({});
    krakenService.balanceSupplier = () => ({ USD: 1500.0 });

    await portfolioManager.performRebalanceCycle();

    expect(portfolioStatsRepository.save).toHaveBeenCalled();
    const saved = portfolioStatsRepository.save.mock.calls[0][0];
    expect(saved.allTimeHigh.eq('1500.0')).toBe(true);
  });
});
