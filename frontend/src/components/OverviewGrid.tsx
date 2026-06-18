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
      <div className="card-portfolio">
        <div className="card-header">
          <div className="card-header-title">TOTAL PORTFOLIO</div>
          <div className="card-icon-blue">
            <TrendUpIcon size={18} />
          </div>
        </div>
        <div className="card-value-primary">
          ${formatCurrency(totalValue)}
        </div>
        <div className="card-footer">
          <span className="card-footer-label">Peak Tracker</span>
          <span className={latest.drawdownPercent > 0 ? 'drawdown-active' : 'drawdown-normal'}>
            Drawdown: {formatPercent(latest.drawdownPercent)}%
          </span>
        </div>
      </div>

      {/* Cash Card */}
      <div className="card-cash">
        <div className="card-header">
          <div className="card-header-title">CASH (USD)</div>
          <div className="card-icon-emerald">
            <WalletIcon size={18} />
          </div>
        </div>
        <div className="card-value-success">
          ${formatCurrency(usdValue)}
        </div>
        <div className="card-footer">
          {usdAsset ? (
            <div className="cash-footer-info">
              <span className="crypto-footer-info">{formatPercent(usdAsset.currentPercent)}%</span>
              <span className="footer-divider">|</span>
              <span className="footer-target-text">Target: {formatPercent(latest.effectiveUsdTargetPercent)}%</span>
              {Math.abs(latest.effectiveUsdTargetPercent - usdAsset.targetPercent) > 0.01 && (
                <span className="footer-subtext">({formatPercent(usdAsset.targetPercent)}% base)</span>
              )}
              <span className="footer-divider-end">|</span>
              <span className={`deviation-pill ${getDeviationClass(usdAsset.deviationPercent)}`}>
                Dev: {getDeviationSign(usdAsset.deviationPercent)}{formatPercent(usdAsset.deviationPercent)}%
              </span>
            </div>
          ) : (
            <span className="footer-divider italic">No USD data</span>
          )}
        </div>
      </div>

      {/* Crypto assets Card */}
      <div className="card-crypto">
        <div className="card-header">
          <div className="card-header-title">CRYPTO ASSETS</div>
          <div className="card-icon-violet">
            <CirclesIcon size={18} />
          </div>
        </div>
        <div className="card-value-violet">
          ${formatCurrency(cryptoValue)}
        </div>
        <div className="card-footer">
          <span className="crypto-footer-info">
            {formatPercent(cryptoPercent)}% <span className="footer-divider">/</span> Target: {formatPercent(cryptoTargetPercent)}%
          </span>
          <span className="asset-count-badge">
            {cryptoCount} asset{cryptoCount !== 1 ? 's' : ''}
          </span>
        </div>
      </div>
    </div>
  );
};
