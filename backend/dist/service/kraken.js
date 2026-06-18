"use strict";
var __createBinding = (this && this.__createBinding) || (Object.create ? (function(o, m, k, k2) {
    if (k2 === undefined) k2 = k;
    var desc = Object.getOwnPropertyDescriptor(m, k);
    if (!desc || ("get" in desc ? !m.__esModule : desc.writable || desc.configurable)) {
      desc = { enumerable: true, get: function() { return m[k]; } };
    }
    Object.defineProperty(o, k2, desc);
}) : (function(o, m, k, k2) {
    if (k2 === undefined) k2 = k;
    o[k2] = m[k];
}));
var __setModuleDefault = (this && this.__setModuleDefault) || (Object.create ? (function(o, v) {
    Object.defineProperty(o, "default", { enumerable: true, value: v });
}) : function(o, v) {
    o["default"] = v;
});
var __importStar = (this && this.__importStar) || (function () {
    var ownKeys = function(o) {
        ownKeys = Object.getOwnPropertyNames || function (o) {
            var ar = [];
            for (var k in o) if (Object.prototype.hasOwnProperty.call(o, k)) ar[ar.length] = k;
            return ar;
        };
        return ownKeys(o);
    };
    return function (mod) {
        if (mod && mod.__esModule) return mod;
        var result = {};
        if (mod != null) for (var k = ownKeys(mod), i = 0; i < k.length; i++) if (k[i] !== "default") __createBinding(result, mod, k[i]);
        __setModuleDefault(result, mod);
        return result;
    };
})();
Object.defineProperty(exports, "__esModule", { value: true });
exports.KrakenService = void 0;
const crypto = __importStar(require("crypto"));
const order_1 = require("../model/order");
class KrakenService {
    configService;
    apiUrl = 'https://api.kraken.com';
    apiVersion = '0';
    nonceGenerator = BigInt(Date.now() * 1000);
    constructor(configService) {
        this.configService = configService;
    }
    async getBalances() {
        const path = `/${this.apiVersion}/private/Balance`;
        const result = await this.queryPrivate(path, {});
        const balances = {};
        if (result) {
            for (const key of Object.keys(result)) {
                balances[key] = parseFloat(result[key]);
            }
        }
        return balances;
    }
    async getTickerPrices(pairs) {
        const path = `/${this.apiVersion}/public/Ticker?pair=${pairs}`;
        const result = await this.queryPublic(path);
        const ticker = result.result;
        const prices = {};
        if (ticker) {
            for (const key of Object.keys(ticker)) {
                const c = ticker[key]?.c;
                if (Array.isArray(c) && c.length > 0) {
                    prices[key] = parseFloat(c[0]);
                }
            }
        }
        return prices;
    }
    async executeOrder(pair, type, side, volume) {
        const config = this.configService.getConfig();
        const normalizedVolume = volume.toDecimalPlaces(8);
        let volStr = normalizedVolume.toFixed(8);
        if (volStr.includes('.')) {
            while (volStr.endsWith('0')) {
                volStr = volStr.slice(0, -1);
            }
            if (volStr.endsWith('.')) {
                volStr = volStr.slice(0, -1);
            }
        }
        if (config.settings.dryRun) {
            console.log(`[DRY RUN] Would execute order: ${type} ${side} ${pair} volume=${volStr}`);
            return (0, order_1.createOrderResult)(true, pair, side, normalizedVolume, true);
        }
        const path = `/${this.apiVersion}/private/AddOrder`;
        const params = {
            pair,
            type: side,
            ordertype: type,
            volume: volStr
        };
        try {
            const resp = await this.queryPrivate(path, params);
            console.log(`Order Executed: ${JSON.stringify(resp)}`);
            return (0, order_1.createOrderResult)(true, pair, side, normalizedVolume, false);
        }
        catch (e) {
            const message = e instanceof Error ? e.message : String(e);
            console.error(`Failed to execute order: ${type} ${side} ${pair} volume=${volStr}`, e);
            return (0, order_1.createOrderResult)(false, pair, side, normalizedVolume, false, message);
        }
    }
    async queryPublic(path) {
        const response = await fetch(this.apiUrl + path);
        if (!response.ok) {
            throw new Error(`HTTP error! status: ${response.status}`);
        }
        const body = await response.json();
        if (body.error && Array.isArray(body.error) && body.error.length > 0) {
            console.error(`Kraken Public API Error for path ${path}: ${body.error}`);
            throw new Error(`Kraken Public API Error: ${JSON.stringify(body.error)}`);
        }
        return body;
    }
    async queryPrivate(path, data) {
        const config = this.configService.getConfig();
        const apiKey = config.kraken.apiKey;
        const privateKey = config.kraken.privateKey;
        if (!apiKey || apiKey.trim() === '') {
            throw new Error('API Key is null');
        }
        const maxRetries = 5;
        let retryCount = 0;
        while (true) {
            const nonce = (this.nonceGenerator++).toString();
            const payload = { ...data, nonce };
            const postData = new URLSearchParams(payload).toString();
            const signature = this.signRequest(path, nonce, postData, privateKey);
            try {
                const response = await fetch(this.apiUrl + path, {
                    method: 'POST',
                    headers: {
                        'API-Key': apiKey,
                        'API-Sign': signature,
                        'Content-Type': 'application/x-www-form-urlencoded'
                    },
                    body: postData
                });
                if (!response.ok) {
                    throw new Error(`HTTP error! status: ${response.status}`);
                }
                const body = await response.json();
                if (body.error && Array.isArray(body.error) && body.error.length > 0) {
                    const errorMsg = JSON.stringify(body.error);
                    if (errorMsg.includes('Invalid nonce') && retryCount < maxRetries) {
                        const bumpAmount = BigInt(100_000_000) * (BigInt(1) << BigInt(retryCount));
                        console.warn(`Invalid nonce detected. Adjusting nonce generator by ${bumpAmount} and retrying (Attempt ${retryCount + 1}/${maxRetries})`);
                        this.nonceGenerator += bumpAmount;
                        retryCount++;
                        continue;
                    }
                    throw new Error(`Kraken API Error: ${errorMsg}`);
                }
                return body.result;
            }
            catch (e) {
                const errorMsg = e instanceof Error ? e.message : String(e);
                if (errorMsg.includes('Invalid nonce') && retryCount < maxRetries) {
                    const bumpAmount = BigInt(100_000_000) * (BigInt(1) << BigInt(retryCount));
                    this.nonceGenerator += bumpAmount;
                    retryCount++;
                    continue;
                }
                throw e;
            }
        }
    }
    signRequest(path, nonce, postData, privateKey) {
        const sha256 = crypto.createHash('sha256');
        sha256.update(nonce + postData);
        const sha2 = sha256.digest();
        const hmacMessage = Buffer.concat([Buffer.from(path), sha2]);
        const secretDecoded = Buffer.from(privateKey, 'base64');
        const hmac = crypto.createHmac('sha512', secretDecoded);
        hmac.update(hmacMessage);
        return hmac.digest('base64');
    }
}
exports.KrakenService = KrakenService;
