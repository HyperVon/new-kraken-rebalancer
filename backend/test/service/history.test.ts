import { describe, it, expect, vi } from 'vitest';
import { Decimal } from 'decimal.js';
import { TradeHistoryServiceImpl } from '../../src/service/history';
import { PortfolioSnapshot } from '../../src/model/snapshot';

describe('TradeHistoryServiceTest', () => {
  const mockSnapshot = (): PortfolioSnapshot => ({
    timestamp: new Date().toISOString(),
    totalValueUSD: new Decimal(0),
    assets: {},
    actions: [],
    drawdownPercent: new Decimal(0),
    fiatDeploymentPercent: new Decimal(0),
    effectiveUsdTargetPercent: new Decimal(0)
  });

  it('init_LoadsHistoryFromRepository', () => {
    const s1 = mockSnapshot();
    const repository = {
      load: vi.fn(() => [s1]),
      save: vi.fn()
    };
    const tradeHistoryService = new TradeHistoryServiceImpl(repository);
    expect(tradeHistoryService.getHistory().length).toBe(1);
    expect(tradeHistoryService.getLatestSnapshot()).toEqual(s1);
  });

  it('addSnapshot_AddsToFrontAndSaves', () => {
    const repository = {
      load: vi.fn(() => []),
      save: vi.fn()
    };
    const tradeHistoryService = new TradeHistoryServiceImpl(repository);
    const s1 = mockSnapshot();
    const s2 = mockSnapshot();

    tradeHistoryService.addSnapshot(s1);
    tradeHistoryService.addSnapshot(s2);

    expect(tradeHistoryService.getHistory().length).toBe(2);
    expect(tradeHistoryService.getLatestSnapshot()).toEqual(s2);
    expect(repository.save).toHaveBeenCalledTimes(2);
  });

  it('addSnapshot_LimitsHistorySize', () => {
    const repository = {
      load: vi.fn(() => []),
      save: vi.fn()
    };
    const tradeHistoryService = new TradeHistoryServiceImpl(repository);
    for (let i = 0; i < 60; i++) {
      tradeHistoryService.addSnapshot(mockSnapshot());
    }

    expect(tradeHistoryService.getHistory().length).toBe(50);
    expect(repository.save).toHaveBeenCalled();
  });

  it('init_HandlesNullLoaded', () => {
    const repository = {
      load: vi.fn(() => []),
      save: vi.fn()
    };
    const tradeHistoryService = new TradeHistoryServiceImpl(repository);
    expect(tradeHistoryService.getHistory().length).toBe(0);
  });

  it('getHistoryFlow_EmitsSnapshotsOnAdd', async () => {
    const repository = {
      load: vi.fn(() => []),
      save: vi.fn()
    };
    const tradeHistoryService = new TradeHistoryServiceImpl(repository);
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

  it('getLatestSnapshot_ReturnsNullWhenEmpty', () => {
    const repository = {
      load: vi.fn(() => []),
      save: vi.fn()
    };
    const tradeHistoryService = new TradeHistoryServiceImpl(repository);
    expect(tradeHistoryService.getLatestSnapshot()).toBeNull();
  });
});
