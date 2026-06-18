import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import * as fs from 'fs';
import { TradeRepository } from '../../src/repository/trade';
import { Decimal } from 'decimal.js';
import { AtomicJsonFile } from '../../src/repository/atomicFile';

describe('TradeRepository', () => {
  const testFileName = 'test-trade-history.json';
  let repository: TradeRepository;

  beforeEach(() => {
    if (fs.existsSync(testFileName)) {
      try {
        fs.unlinkSync(testFileName);
      } catch (_) {}
    }
    repository = new TradeRepository(testFileName);
  });

  afterEach(() => {
    if (fs.existsSync(testFileName)) {
      try {
        fs.unlinkSync(testFileName);
      } catch (_) {}
    }
  });

  it('should successfully save and load snapshot history', () => {
    const snapshot = {
      timestamp: '2023-01-01T10:00:00.000Z',
      totalValueUSD: new Decimal('15000.50'),
      assets: {},
      actions: ['BUY BTC'],
      drawdownPercent: new Decimal(0),
      fiatDeploymentPercent: new Decimal(0),
      effectiveUsdTargetPercent: new Decimal(0)
    };

    repository.save([snapshot]);
    const loaded = repository.load();

    expect(loaded.length).toBe(1);
    expect(loaded[0].totalValueUSD.eq('15000.50')).toBe(true);
    expect(loaded[0].actions).toEqual(snapshot.actions);
  });

  it('should return empty list when the history file does not exist', () => {
    const loaded = repository.load();
    expect(loaded).toBeDefined();
    expect(loaded.length).toBe(0);
  });

  it('should return empty list when loading a corrupted history file', () => {
    fs.writeFileSync(testFileName, '{ incomplete json ', 'utf8');
    const loaded = repository.load();
    expect(loaded).toBeDefined();
    expect(loaded.length).toBe(0);
  });

  it('should propagate errors when saving fails', () => {
    const spy = vi.spyOn(AtomicJsonFile, 'writeSync').mockImplementation(() => {
      throw new Error('Write failed');
    });

    const repo = new TradeRepository(testFileName);
    expect(() => repo.save([])).toThrow(/Write failed/);

    spy.mockRestore();
  });
});
