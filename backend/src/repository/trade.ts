import * as fs from 'fs';
import { Decimal } from 'decimal.js';
import { PortfolioSnapshot, AssetSnapshot } from '../model/snapshot';
import { AtomicJsonFile } from './atomicFile';

interface RawAssetSnapshot {
  symbol: string;
  balance: number | string;
  price: number | string;
  valueUSD: number | string;
  targetPercent: number | string;
  currentPercent: number | string;
  deviationPercent: number | string;
  deviationUSD: number | string;
}

interface RawPortfolioSnapshot {
  timestamp: string;
  totalValueUSD: number | string;
  assets?: Record<string, RawAssetSnapshot>;
  actions?: string[];
  drawdownPercent?: number | string;
  fiatDeploymentPercent?: number | string;
  effectiveUsdTargetPercent?: number | string;
}

export class TradeRepository {
  private readonly filePath: string;

  constructor(filePath: string = 'trade-history.json') {
    this.filePath = filePath;
  }

  load(): PortfolioSnapshot[] {
    if (!fs.existsSync(this.filePath)) {
      return [];
    }

    try {
      const data = fs.readFileSync(this.filePath, 'utf8');
      const list = JSON.parse(data) as RawPortfolioSnapshot[];
      return list.map(item => this.parseSnapshot(item));
    } catch (e) {
      return [];
    }
  }

  save(history: PortfolioSnapshot[]): void {
    AtomicJsonFile.writeSync(this.filePath, history);
  }

  private parseSnapshot(obj: RawPortfolioSnapshot): PortfolioSnapshot {
    const assets: Record<string, AssetSnapshot> = {};
    if (obj.assets) {
      for (const key of Object.keys(obj.assets)) {
        const a = obj.assets[key];
        assets[key] = {
          symbol: a.symbol,
          balance: new Decimal(a.balance),
          price: new Decimal(a.price),
          valueUSD: new Decimal(a.valueUSD),
          targetPercent: new Decimal(a.targetPercent),
          currentPercent: new Decimal(a.currentPercent),
          deviationPercent: new Decimal(a.deviationPercent),
          deviationUSD: new Decimal(a.deviationUSD)
        };
      }
    }

    return {
      timestamp: obj.timestamp,
      totalValueUSD: new Decimal(obj.totalValueUSD),
      assets,
      actions: obj.actions || [],
      drawdownPercent: new Decimal(obj.drawdownPercent ?? 0),
      fiatDeploymentPercent: new Decimal(obj.fiatDeploymentPercent ?? 0),
      effectiveUsdTargetPercent: new Decimal(obj.effectiveUsdTargetPercent ?? 0)
    };
  }
}
