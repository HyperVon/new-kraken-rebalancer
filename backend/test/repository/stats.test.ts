import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import * as fs from 'fs';
import { PortfolioStatsRepositoryImpl } from '../../src/repository/stats';
import { Decimal } from 'decimal.js';
import { AtomicJsonFile } from '../../src/repository/atomicFile';

describe('PortfolioStatsRepositoryImplTest', () => {
  const testFileName = 'test-portfolio-stats.json';
  let repository: PortfolioStatsRepositoryImpl;

  beforeEach(() => {
    if (fs.existsSync(testFileName)) {
      try {
        fs.unlinkSync(testFileName);
      } catch (_) {}
    }
    repository = new PortfolioStatsRepositoryImpl(testFileName);
  });

  afterEach(() => {
    if (fs.existsSync(testFileName)) {
      try {
        fs.unlinkSync(testFileName);
      } catch (_) {}
    }
  });

  it('load_NonExistentFile_ReturnsZeroStats', () => {
    const stats = repository.load();
    expect(stats).toBeDefined();
    expect(stats.allTimeHigh.isZero()).toBe(true);
  });

  it('load_Success', () => {
    const stats = { allTimeHigh: new Decimal('1000.50') };
    repository.save(stats);

    const loaded = repository.load();
    expect(loaded).toBeDefined();
    expect(loaded.allTimeHigh.eq('1000.50')).toBe(true);
  });

  it('load_HandlesIOException', () => {
    fs.writeFileSync(testFileName, '{invalid json}', 'utf8');
    const stats = repository.load();
    expect(stats).toBeDefined();
    expect(stats.allTimeHigh.isZero()).toBe(true);
  });

  it('save_HandlesIOException', () => {
    const spy = vi.spyOn(AtomicJsonFile, 'writeSync').mockImplementation(() => {
      throw new Error('simulated error');
    });

    const errRepository = new PortfolioStatsRepositoryImpl(testFileName);
    const stats = { allTimeHigh: new Decimal(10.0) };

    expect(() => errRepository.save(stats)).toThrow(/simulated error/);

    spy.mockRestore();
  });
});
