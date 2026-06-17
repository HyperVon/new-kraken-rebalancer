"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.Asset = void 0;
class Asset {
    value;
    constructor(value) {
        this.value = value;
    }
    get isUsd() {
        return this.value.toUpperCase() === 'USD';
    }
    get krakenTicker() {
        return Asset.toKrakenTicker(this.value);
    }
    get tradingPair() {
        return Asset.tradingPair(this.value);
    }
    toString() {
        return this.value;
    }
    static toKrakenTicker(symbol) {
        const s = symbol.toUpperCase();
        if (s === 'BTC')
            return 'XBT';
        if (s === 'DOGE')
            return 'XDG';
        return s;
    }
    static tradingPair(symbol) {
        return `${Asset.toKrakenTicker(symbol)}USD`;
    }
    static USD = 'USD';
    static BTC = 'BTC';
    static ETH = 'ETH';
    static DOGE = 'DOGE';
    static XBT = 'XBT';
    static XDG = 'XDG';
    static BTC_USD_PAIR = Asset.tradingPair(Asset.BTC);
}
exports.Asset = Asset;
