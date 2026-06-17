import React from 'react';
import { DollarCircleIcon } from './Icons';
import { formatCurrency, formatPercent } from './OverviewGrid';

interface AssetSnapshot {
  symbol: string;
  valueUSD: number;
  currentPercent: number;
}

interface PortfolioSnapshot {
  assets: Record<string, AssetSnapshot>;
}

interface AllocationChartProps {
  latest: PortfolioSnapshot;
}

export const AllocationChart: React.FC<AllocationChartProps> = ({ latest }) => {
  const sortedAssets = Object.values(latest.assets)
    .sort((a, b) => b.valueUSD - a.valueUSD)
    .slice(0, 15);

  const maxVal = sortedAssets.length > 0 ? sortedAssets[0].valueUSD : 1.0;

  return (
    <div className="glass-panel">
      <div className="glass-panel-title">
        <DollarCircleIcon />
        PORTFOLIO ALLOCATION
      </div>
      <div className="allocation-chart-container">
        {sortedAssets.map(asset => {
          const fillPct = maxVal > 0 ? Math.round((asset.valueUSD / maxVal) * 100) : 0;
          return (
            <div key={asset.symbol} className="allocation-bar-row">
              <div className="allocation-bar-label">{asset.symbol}</div>
              <div className="allocation-bar-track">
                <div className="allocation-bar-fill" style={{ width: `${fillPct}%` }}></div>
              </div>
              <div className="allocation-bar-value">
                ${formatCurrency(asset.valueUSD)} ({formatPercent(asset.currentPercent)}%)
              </div>
            </div>
          );
        })}
      </div>
    </div>
  );
};
