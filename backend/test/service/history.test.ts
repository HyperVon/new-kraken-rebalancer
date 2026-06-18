import { describe, it, expect, vi } from 'vitest';
import { Decimal } from 'decimal.js';
import { TradeHistoryService } from '../../src/service/history';
import { PortfolioSnapshot } from '../../src/model/snapshot';

describe('TradeHistoryService', () => {
  const mockSnapshot = (): PortfolioSnapshot => ({
    timestamp: new Date().toISOString(),
    totalValueUSD: new Decimal(0),
    assets: {},
    actions: [],
    drawdownPercent: new Decimal(0),
    fiatDeploymentPercent: new Decimal(0),
    effectiveUsdTargetPercent: new Decimal(0)
  });

  it('should load history from repository on initialization', () => {
    const s1 = mockSnapshot();
    const repository = {
      load: vi.fn(() => [s1]),
      save: vi.fn()
    };
    const tradeHistoryService = new TradeHistoryService(repository);
    expect(tradeHistoryService.getHistory().length).toBe(1);
    expect(tradeHistoryService.getLatestSnapshot()).toEqual(s1);
  });

  it('should prepend new snapshots and save history', () => {
    const repository = {
      load: vi.fn(() => []),
      save: vi.fn()
    };
    const tradeHistoryService = new TradeHistoryService(repository);
    const s1 = mockSnapshot();
    const s2 = mockSnapshot();

    tradeHistoryService.addSnapshot(s1);
    tradeHistoryService.addSnapshot(s2);

    expect(tradeHistoryService.getHistory().length).toBe(2);
    expect(tradeHistoryService.getLatestSnapshot()).toEqual(s2);
    expect(repository.save).toHaveBeenCalledTimes(2);
  });

  it('should limit history size to 50 items', () => {
    const repository = {
      load: vi.fn(() => []),
      save: vi.fn()
    };
    const tradeHistoryService = new TradeHistoryService(repository);
    for (let i = 0; i < 60; i++) {
      tradeHistoryService.addSnapshot(mockSnapshot());
    }

    expect(tradeHistoryService.getHistory().length).toBe(50);
    expect(repository.save).toHaveBeenCalled();
  });

  it('should initialize successfully with empty repository', () => {
    const repository = {
      load: vi.fn(() => []),
      save: vi.fn()
    };
    const tradeHistoryService = new TradeHistoryService(repository);
    expect(tradeHistoryService.getHistory().length).toBe(0);
  });

  it('should emit snapshots to subscribers when added', async () => {
    const repository = {
      load: vi.fn(() => []),
      save: vi.fn()
    };
    const tradeHistoryService = new TradeHistoryService(repository);
    const snapshots: PortfolioSnapshot[] = [];

    const unsubscribe = tradeHistoryService.subscribe((s) => {
      snapshots.push(s);
    });

    const s1 = mockSnapshot();
    tradeHistoryService.addSnapshot(s1);

    expect(snapshots.length).toBe(1);
    expect(snapshots[0]).toEqual(s1);

    unsubscribe();
  });

  it('should return null when getLatestSnapshot is called on empty history', () => {
    const repository = {
      load: vi.fn(() => []),
      save: vi.fn()
    };
    const tradeHistoryService = new TradeHistoryService(repository);
    expect(tradeHistoryService.getLatestSnapshot()).toBeNull();
  });
});
