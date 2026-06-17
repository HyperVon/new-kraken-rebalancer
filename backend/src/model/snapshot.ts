import { Decimal } from 'decimal.js';

export interface AssetSnapshot {
  symbol: string;
  balance: Decimal;
  price: Decimal;
  valueUSD: Decimal;
  targetPercent: Decimal;
  currentPercent: Decimal;
  deviationPercent: Decimal;
  deviationUSD: Decimal;
}

export interface PortfolioSnapshot {
  timestamp: string; // ISO string
  totalValueUSD: Decimal;
  assets: Record<string, AssetSnapshot>;
  actions: string[];
  drawdownPercent: Decimal;
  fiatDeploymentPercent: Decimal;
  effectiveUsdTargetPercent: Decimal;
}
