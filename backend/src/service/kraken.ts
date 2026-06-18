import * as crypto from 'crypto';
import { Decimal } from 'decimal.js';
import { ConfigService } from '../config/config';
import { OrderResult } from '../model/order';
import { Asset } from '../model/asset';
import { Injectable } from '@nestjs/common';

export const KRAKEN_SERVICE_TOKEN = 'IKrakenService';

export interface IKrakenService {
  getBalances(): Promise<Record<string, number>>;
  getTickerPrices(pairs: string): Promise<Record<string, number>>;
  executeOrder(
    pair: string,
    type: string,
    side: 'buy' | 'sell',
    volume: Decimal
  ): Promise<OrderResult>;
}

@Injectable()
export class KrakenService implements IKrakenService {
  private readonly configService: ConfigService;
  private readonly apiUrl = 'https://api.kraken.com';
  private readonly apiVersion = '0';
  private nonceGenerator = BigInt(Date.now() * 1000);

  constructor(configService: ConfigService) {
    this.configService = configService;
  }

  async getBalances(): Promise<Record<string, number>> {
    const path = `/${this.apiVersion}/private/Balance`;
    const result = await this.queryPrivate(path, {}) as Record<string, string>;
    const balances: Record<string, number> = {};
    if (result) {
      for (const key of Object.keys(result)) {
        balances[key] = parseFloat(result[key]);
      }
    }
    return balances;
  }

  async getTickerPrices(pairs: string): Promise<Record<string, number>> {
    const path = `/${this.apiVersion}/public/Ticker?pair=${pairs}`;
    const result = await this.queryPublic(path) as { result?: Record<string, { c?: string[] }> };
    const ticker = result.result;
    const prices: Record<string, number> = {};
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

  async executeOrder(
    pair: string,
    type: string,
    side: 'buy' | 'sell',
    volume: Decimal
  ): Promise<OrderResult> {
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
      console.log(
        `[DRY RUN] Would execute order: ${type} ${side} ${pair} volume=${volStr}`
      );
      return {
        success: true,
        pair,
        side,
        volume: normalizedVolume,
        dryRun: true
      };
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
      return {
        success: true,
        pair,
        side,
        volume: normalizedVolume,
        dryRun: false
      };
    } catch (e: unknown) {
      const message = e instanceof Error ? e.message : String(e);
      console.error(
        `Failed to execute order: ${type} ${side} ${pair} volume=${volStr}`,
        e
      );
      return {
        success: false,
        pair,
        side,
        volume: normalizedVolume,
        dryRun: false,
        errorMessage: message
      };
    }
  }

  private async queryPublic(path: string): Promise<unknown> {
    const response = await fetch(this.apiUrl + path);
    if (!response.ok) {
      throw new Error(`HTTP error! status: ${response.status}`);
    }
    const body = await response.json() as { error?: string[] };
    if (body.error && Array.isArray(body.error) && body.error.length > 0) {
      console.error(`Kraken Public API Error for path ${path}: ${body.error}`);
      throw new Error(`Kraken Public API Error: ${JSON.stringify(body.error)}`);
    }
    return body;
  }

  private async queryPrivate(path: string, data: Record<string, string>): Promise<unknown> {
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

        const body = await response.json() as { error?: string[]; result?: unknown };
        if (body.error && Array.isArray(body.error) && body.error.length > 0) {
          const errorMsg = JSON.stringify(body.error);
          if (errorMsg.includes('Invalid nonce') && retryCount < maxRetries) {
            const bumpAmount = BigInt(100_000_000) * (BigInt(1) << BigInt(retryCount));
            console.warn(
              `Invalid nonce detected. Adjusting nonce generator by ${bumpAmount} and retrying (Attempt ${retryCount + 1}/${maxRetries})`
            );
            this.nonceGenerator += bumpAmount;
            retryCount++;
            continue;
          }
          throw new Error(`Kraken API Error: ${errorMsg}`);
        }
        return body.result;
      } catch (e: unknown) {
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

  private signRequest(path: string, nonce: string, postData: string, privateKey: string): string {
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
