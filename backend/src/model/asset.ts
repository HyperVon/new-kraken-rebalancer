export class Asset {
  public readonly value: string;

  constructor(value: string) {
    this.value = value;
  }

  get isUsd(): boolean {
    return this.value.toUpperCase() === 'USD';
  }

  get krakenTicker(): string {
    return Asset.toKrakenTicker(this.value);
  }

  get tradingPair(): string {
    return Asset.tradingPair(this.value);
  }

  toString(): string {
    return this.value;
  }

  static toKrakenTicker(symbol: string): string {
    const s = symbol.toUpperCase();
    if (s === 'BTC') return 'XBT';
    if (s === 'DOGE') return 'XDG';
    return s;
  }

  static tradingPair(symbol: string): string {
    return `${Asset.toKrakenTicker(symbol)}USD`;
  }

  static readonly USD = 'USD';
  static readonly BTC = 'BTC';
  static readonly ETH = 'ETH';
  static readonly DOGE = 'DOGE';
  static readonly XBT = 'XBT';
  static readonly XDG = 'XDG';
  static readonly BTC_USD_PAIR = Asset.tradingPair(Asset.BTC);
}
