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
    <div className="glass-panel">
      <div className="glass-panel-title">
        <DollarCircleIcon />
        PORTFOLIO ALLOCATION
      </div>
      <div className="allocation-chart-container">
        {sortedAssets.map(asset => {
          const fillPct = maxVal > 0 ? Math.round((asset.valueUSD / maxVal) * 100) : 0;
          const targetValUSD = (latest.totalValueUSD * asset.targetPercent) / 100;
          const targetFillPct = Math.min(100, maxVal > 0 ? Math.round((targetValUSD / maxVal) * 100) : 0);

          return (
            <div key={asset.symbol} className="allocation-bar-row">
              <div className="allocation-bar-label">{asset.symbol}</div>
              <div className="allocation-bar-track" style={{ position: 'relative', overflow: 'visible' }}>
                <div className="allocation-bar-fill" style={{ width: `${fillPct}%` }}></div>
                <div
                  className="allocation-bar-target-marker"
                  style={{
                    position: 'absolute',
                    left: `${targetFillPct}%`,
                    top: '-2px',
                    width: '3px',
                    height: 'calc(100% + 4px)',
                    backgroundColor: '#38bdf8',
                    boxShadow: '0 0 8px #38bdf8, 0 0 2px #38bdf8',
                    borderRadius: '2px',
                    transform: 'translateX(-50%)',
                    zIndex: 10
                  }}
                  title={`Target: ${formatPercent(asset.targetPercent)}%`}
                />
              </div>
              <div className="allocation-bar-value">
                ${formatCurrency(asset.valueUSD)} ({formatPercent(asset.currentPercent)}% / {formatPercent(asset.targetPercent)}% target)
              </div>
            </div>
          );
        })}
      </div>
    </div>
  );
};
