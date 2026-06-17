import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import * as fs from 'fs';
import { FileTradeRepositoryImpl } from '../../src/repository/trade';
import { Decimal } from 'decimal.js';
import { AtomicJsonFile } from '../../src/repository/atomicFile';

describe('FileTradeRepositoryTest', () => {
  const testFileName = 'test-trade-history.json';
  let repository: FileTradeRepositoryImpl;

  beforeEach(() => {
    if (fs.existsSync(testFileName)) {
      try {
        fs.unlinkSync(testFileName);
      } catch (_) {}
    }
    repository = new FileTradeRepositoryImpl(testFileName);
  });

  afterEach(() => {
    if (fs.existsSync(testFileName)) {
      try {
        fs.unlinkSync(testFileName);
      } catch (_) {}
    }
  });

  it('testSaveAndLoad', () => {
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

  it('testLoadNonExistentFile', () => {
    const loaded = repository.load();
    expect(loaded).toBeDefined();
    expect(loaded.length).toBe(0);
  });

  it('testLoadCorruptedFile', () => {
    fs.writeFileSync(testFileName, '{ incomplete json ', 'utf8');
    const loaded = repository.load();
    expect(loaded).toBeDefined();
    expect(loaded.length).toBe(0);
  });

  it('testSaveError', () => {
    const spy = vi.spyOn(AtomicJsonFile, 'writeSync').mockImplementation(() => {
      throw new Error('Write failed');
    });

    const repo = new FileTradeRepositoryImpl(testFileName);
    expect(() => repo.save([])).toThrow(/Write failed/);

    spy.mockRestore();
  });
});
