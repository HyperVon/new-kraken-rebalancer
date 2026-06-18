import { describe, it, expect, vi } from 'vitest';
import { Decimal } from 'decimal.js';
import { PortfolioAnalyzer } from '../../src/service/analyzer';
import { FakeKrakenService } from './fakeKraken';
import { Allocation } from '../../src/config/config';

Decimal.set({ rounding: Decimal.ROUND_HALF_UP });

function makePortfolioAnalyzer(...allocs: Allocation[]): PortfolioAnalyzer {
  const configService = {
    getConfig: vi.fn(() => ({
      kraken: { apiKey: 'k', privateKey: 's' },
      settings: {
        loopDelaySeconds: 60,
        deviationTriggerPercent: 2.0,
        dustThresholdUSD: 1.0,
        dryRun: false,
        fiatMaxDrawdown: 0.0,
        fiatDeploymentExponent: 1.0
      },
      allocations: allocs
    })),
    updateConfig: vi.fn(),
    loadConfig: vi.fn()
  };
  const repo = {
    load: vi.fn(() => ({ allTimeHigh: new Decimal(0) })),
    save: vi.fn()
  };

  return new PortfolioAnalyzer(new FakeKrakenService(), configService as any, repo as any);
}

describe('PortfolioManager fiat correction', () => {
  it('should distribute deposit correction only to underweight assets', () => {
    const portfolioAnalyzer = makePortfolioAnalyzer(
      { symbol: 'A', targetPercent: 50.0 },
      { symbol: 'B', targetPercent: 50.0 }
    );

    const usdDev = new Decimal(100.0);
    const allDevs = {
      A: new Decimal(10.0),
      B: new Decimal(-10.0)
    };
    const buyOrders: Record<string, Decimal> = {};
    const sellOrders: Record<string, Decimal> = {};

    portfolioAnalyzer.distributeFiatCorrection(usdDev, allDevs, buyOrders, sellOrders, []);

    expect(buyOrders['B']).toBeDefined();
    expect(buyOrders['A'] || new Decimal(0)).toEqual(new Decimal(0));
    expect(Object.keys(sellOrders).length).toBe(0);
  });

  it('should distribute withdrawal correction only to overweight assets', () => {
    const portfolioAnalyzer = makePortfolioAnalyzer(
      { symbol: 'A', targetPercent: 50.0 },
      { symbol: 'B', targetPercent: 50.0 }
    );

    const usdDev = new Decimal(-100.0);
    const allDevs = {
      A: new Decimal(10.0),
      B: new Decimal(-10.0)
    };
    const buyOrders: Record<string, Decimal> = {};
    const sellOrders: Record<string, Decimal> = {};

    portfolioAnalyzer.distributeFiatCorrection(usdDev, allDevs, buyOrders, sellOrders, []);

    expect(sellOrders['A']).toBeDefined();
    expect(sellOrders['B'] || new Decimal(0)).toEqual(new Decimal(0));
    expect(Object.keys(buyOrders).length).toBe(0);
  });

  it('should distribute correction proportionally based on deviations', () => {
    const portfolioAnalyzer = makePortfolioAnalyzer(
      { symbol: 'A', targetPercent: 30.0 },
      { symbol: 'B', targetPercent: 30.0 },
      { symbol: 'C', targetPercent: 40.0 }
    );

    const usdDev = new Decimal(100.0);
    const allDevs = {
      A: new Decimal(-200.0),
      B: new Decimal(-50.0),
      C: new Decimal(50.0)
    };
    const buyOrders: Record<string, Decimal> = {};
    const sellOrders: Record<string, Decimal> = {};

    portfolioAnalyzer.distributeFiatCorrection(usdDev, allDevs, buyOrders, sellOrders, []);

    expect(buyOrders['A'].eq(80.0)).toBe(true);
    expect(buyOrders['B'].eq(20.0)).toBe(true);
    expect(buyOrders['C'] || new Decimal(0)).toEqual(new Decimal(0));
  });
});
