import { describe, it, expect } from 'vitest';
import { Decimal } from 'decimal.js';
import { Asset } from '../../src/model/asset';

describe('ModelTest', () => {
  it('testAssetMappings', () => {
    const btc = new Asset(Asset.BTC);
    expect(btc.krakenTicker).toBe(Asset.XBT);
    expect(btc.tradingPair).toBe('XBTUSD');
    expect(btc.isUsd).toBe(false);

    const doge = new Asset(Asset.DOGE);
    expect(doge.krakenTicker).toBe(Asset.XDG);
    expect(doge.tradingPair).toBe('XDGUSD');

    const usd = new Asset(Asset.USD);
    expect(usd.isUsd).toBe(true);
    expect(usd.tradingPair).toBe('USDUSD');

    const eth = new Asset(Asset.ETH);
    expect(eth.krakenTicker).toBe(Asset.ETH);
    expect(eth.tradingPair).toBe('ETHUSD');

    expect(Asset.toKrakenTicker('btc')).toBe(Asset.XBT);
    expect(Asset.toKrakenTicker('doge')).toBe(Asset.XDG);
    expect(Asset.toKrakenTicker('eth')).toBe('ETH');

    expect(Asset.tradingPair('btc')).toBe('XBTUSD');
    expect(Asset.tradingPair('eth')).toBe('ETHUSD');

    expect(Asset.BTC_USD_PAIR).toBe('XBTUSD');
  });

  it('testPortfolioSnapshot', () => {
    const asset = {
      symbol: Asset.BTC,
      balance: new Decimal(1.0),
      price: new Decimal(10.0),
      valueUSD: new Decimal(10.0),
      targetPercent: new Decimal(1.0),
      currentPercent: new Decimal(1.0),
      deviationPercent: new Decimal(0.0),
      deviationUSD: new Decimal(0.0)
    };

    expect(asset.symbol).toBe(Asset.BTC);

    const snapshot = {
      timestamp: new Date(0).toISOString(),
      totalValueUSD: new Decimal(10.0),
      assets: { [Asset.BTC]: asset },
      actions: ['BUY'],
      drawdownPercent: new Decimal(0.0),
      fiatDeploymentPercent: new Decimal(0.0),
      effectiveUsdTargetPercent: new Decimal(0.0)
    };

    expect(snapshot.totalValueUSD.toNumber()).toBe(10.0);
  });
});
