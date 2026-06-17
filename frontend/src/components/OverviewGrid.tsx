import React from 'react';
import { TrendUpIcon, WalletIcon, CirclesIcon } from './Icons';

interface AssetSnapshot {
  symbol: string;
  balance: number;
  price: number;
  valueUSD: number;
  targetPercent: number;
  currentPercent: number;
  deviationPercent: number;
  deviationUSD: number;
}

interface PortfolioSnapshot {
  timestamp: string;
  totalValueUSD: number;
  assets: Record<string, AssetSnapshot>;
  actions: string[];
  drawdownPercent: number;
  fiatDeploymentPercent: number;
  effectiveUsdTargetPercent: number;
}

interface OverviewGridProps {
  latest: PortfolioSnapshot;
}

export const formatCurrency = (val: number | string): string => {
  return Number(val).toLocaleString(undefined, {
    minimumFractionDigits: 2,
    maximumFractionDigits: 2
  });
};

export const formatPercent = (val: number | string): string => {
  return Number(val).toFixed(2);
};

export const getDeviationClass = (dev: number): string => {
  if (dev > 0.001) return 'text-danger';
  if (dev < -0.001) return 'text-success';
  return '';
};

export const getDeviationSign = (dev: number): string => {
  return dev > 0.001 ? '+' : '';
};

export const OverviewGrid: React.FC<OverviewGridProps> = ({ latest }) => {
  const totalValue = latest.totalValueUSD;
  const usdAsset = latest.assets['USD'];
  const usdValue = usdAsset ? usdAsset.valueUSD : 0;
  const cryptoValue = totalValue - usdValue;

  const cryptoAssets = Object.values(latest.assets).filter(
    a => a.symbol.toUpperCase() !== 'USD'
  );
  const cryptoPercent = cryptoAssets.reduce((sum, a) => sum + a.currentPercent, 0);
  const cryptoTargetPercent = cryptoAssets.reduce((sum, a) => sum + a.targetPercent, 0);
  const cryptoCount = cryptoAssets.length;

  return (
    <div className="overview-grid">
      {/* Portfolio Card */}
      <div className="glass-panel status-card">
        <div className="status-card-header">
          <div className="status-card-title">TOTAL PORTFOLIO</div>
          <div className="status-card-icon">
            <TrendUpIcon />
          </div>
        </div>
        <div className="status-card-value">${formatCurrency(totalValue)}</div>
        <div className="status-card-sub">
          <span className={latest.drawdownPercent > 0 ? 'text-danger' : ''}>
            Drawdown: {formatPercent(latest.drawdownPercent)}%
          </span>
        </div>
      </div>

      {/* Cash Card */}
      <div className="glass-panel status-card success">
        <div className="status-card-header">
          <div className="status-card-title">CASH (USD)</div>
          <div className="status-card-icon">
            <WalletIcon />
          </div>
        </div>
        <div className="status-card-value">${formatCurrency(usdValue)}</div>
        <div className="status-card-sub">
          {usdAsset ? (
            <span>
              {formatPercent(usdAsset.currentPercent)}% | Target:{' '}
              {formatPercent(latest.effectiveUsdTargetPercent)}%
              {Math.abs(latest.effectiveUsdTargetPercent - usdAsset.targetPercent) > 0.01 && (
                <> ({formatPercent(usdAsset.targetPercent)}% base)</>
              )}
              {' | '}
              <span className={getDeviationClass(usdAsset.deviationPercent)}>
                Dev: {getDeviationSign(usdAsset.deviationPercent)}
                {formatPercent(usdAsset.deviationPercent)}%
              </span>
            </span>
          ) : (
            <span>No USD data</span>
          )}
        </div>
      </div>

      {/* Crypto assets Card */}
      <div className="glass-panel status-card">
        <div className="status-card-header">
          <div className="status-card-title">CRYPTO ASSETS</div>
          <div className="status-card-icon">
            <CirclesIcon />
          </div>
        </div>
        <div className="status-card-value">${formatCurrency(cryptoValue)}</div>
        <div className="status-card-sub">
          <span>
            {formatPercent(cryptoPercent)}% | Target: {formatPercent(cryptoTargetPercent)}% |{' '}
            {cryptoCount} asset{cryptoCount !== 1 ? 's' : ''}
          </span>
        </div>
      </div>
    </div>
  );
};
