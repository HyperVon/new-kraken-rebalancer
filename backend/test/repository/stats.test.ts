import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import * as fs from 'fs';
import { PortfolioStatsRepository } from '../../src/repository/stats';
import { Decimal } from 'decimal.js';
import { AtomicJsonFile } from '../../src/repository/atomicFile';

describe('PortfolioStatsRepository', () => {
  const testFileName = 'test-portfolio-stats.json';
  let repository: PortfolioStatsRepository;

  beforeEach(() => {
    if (fs.existsSync(testFileName)) {
      try {
        fs.unlinkSync(testFileName);
      } catch (_) {}
    }
    repository = new PortfolioStatsRepository(testFileName);
  });

  afterEach(() => {
    if (fs.existsSync(testFileName)) {
      try {
        fs.unlinkSync(testFileName);
      } catch (_) {}
    }
  });

  it('should return zero stats if the file does not exist', () => {
    const stats = repository.load();
    expect(stats).toBeDefined();
    expect(stats.allTimeHigh).not.toBeNull();
    expect(stats.allTimeHigh!.isZero()).toBe(true);
  });

  it('should successfully load saved stats', () => {
    const stats = { allTimeHigh: new Decimal('1000.50') };
    repository.save(stats);

    const loaded = repository.load();
    expect(loaded).toBeDefined();
    expect(loaded.allTimeHigh).not.toBeNull();
    expect(loaded.allTimeHigh!.eq('1000.50')).toBe(true);
  });

  it('should return zero stats if the file contains invalid JSON', () => {
    fs.writeFileSync(testFileName, '{invalid json}', 'utf8');
    const stats = repository.load();
    expect(stats).toBeDefined();
    expect(stats.allTimeHigh).not.toBeNull();
    expect(stats.allTimeHigh!.isZero()).toBe(true);
  });

  it('should throw an error if saving fails', () => {
    const spy = vi.spyOn(AtomicJsonFile, 'writeSync').mockImplementation(() => {
      throw new Error('simulated error');
    });

    const errRepository = new PortfolioStatsRepository(testFileName);
    const stats = { allTimeHigh: new Decimal(10.0) };

    expect(() => errRepository.save(stats)).toThrow(/simulated error/);

    spy.mockRestore();
  });
});
