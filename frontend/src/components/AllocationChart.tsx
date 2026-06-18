import React from 'react';
import { DollarCircleIcon } from './Icons';
import { formatCurrency, formatPercent } from './OverviewGrid';

interface AssetSnapshot {
  symbol: string;
  valueUSD: number;
  currentPercent: number;
  targetPercent: number;
}

interface PortfolioSnapshot {
  totalValueUSD: number;
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
    <div className="glass-card">
      <div className="card-title">
        <DollarCircleIcon size={18} />
        PORTFOLIO ALLOCATION
      </div>
      <div className="chart-container">
        {sortedAssets.map(asset => {
          const fillPct = maxVal > 0 ? Math.round((asset.valueUSD / maxVal) * 100) : 0;
          const targetValUSD = (latest.totalValueUSD * asset.targetPercent) / 100;
          const targetFillPct = Math.min(100, maxVal > 0 ? Math.round((targetValUSD / maxVal) * 100) : 0);

          return (
            <div key={asset.symbol} className="chart-row">
              <div className="chart-symbol">
                {asset.symbol}
              </div>
              <div className="chart-track-wrapper">
                <div 
                  className="chart-track-fill" 
                  style={{ width: `${fillPct}%` }}
                ></div>
                <div
                  className="chart-target-marker"
                  style={{ left: `${targetFillPct}%` }}
                  title={`Target: ${formatPercent(asset.targetPercent)}%`}
                />
              </div>
              <div className="chart-metrics">
                <span className="chart-metric-val">${formatCurrency(asset.valueUSD)}</span>
                <span className="chart-metric-sub">
                  {formatPercent(asset.currentPercent)}% <span className="chart-metric-divider">/</span> {formatPercent(asset.targetPercent)}% target
                </span>
                <span className="visually-hidden">
                  ${formatCurrency(asset.valueUSD)} ({formatPercent(asset.currentPercent)}% / {formatPercent(asset.targetPercent)}% target)
                </span>
              </div>
            </div>
          );
        })}
      </div>
    </div>
  );
};
