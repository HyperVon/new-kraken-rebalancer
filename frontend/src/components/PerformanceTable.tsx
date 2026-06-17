import React, { useState } from 'react';
import { formatCurrency, formatPercent, getDeviationClass, getDeviationSign } from './OverviewGrid';

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

interface PerformanceTableProps {
  latest: {
    assets: Record<string, AssetSnapshot>;
  };
}

export const PerformanceTable: React.FC<PerformanceTableProps> = ({ latest }) => {
  const [sortCol, setSortCol] = useState<number>(5); // Default to Dev % column (index 5)
  const [sortDir, setSortDir] = useState<'asc' | 'desc'>('asc');

  const cryptoAssets = Object.values(latest.assets).filter(
    a => a.symbol.toUpperCase() !== 'USD'
  );

  const handleSort = (index: number) => {
    if (sortCol === index) {
      setSortDir(sortDir === 'asc' ? 'desc' : 'asc');
    } else {
      setSortCol(index);
      setSortDir(index === 5 ? 'asc' : 'desc'); // Dev % usually ascends (underweight first), others descend by default
    }
  };

  const sortedAssets = [...cryptoAssets].sort((a, b) => {
    let aVal: any;
    let bVal: any;

    switch (sortCol) {
      case 0:
        aVal = a.symbol;
        bVal = b.symbol;
        break;
      case 1:
        aVal = a.price;
        bVal = b.price;
        break;
      case 2:
        aVal = a.valueUSD;
        bVal = b.valueUSD;
        break;
      case 3:
        aVal = a.targetPercent;
        bVal = b.targetPercent;
        break;
      case 4:
        aVal = a.currentPercent;
        bVal = b.currentPercent;
        break;
      case 5:
        aVal = a.deviationPercent;
        bVal = b.deviationPercent;
        break;
      default:
        aVal = a.symbol;
        bVal = b.symbol;
    }

    if (typeof aVal === 'string') {
      return sortDir === 'asc' ? aVal.localeCompare(bVal) : bVal.localeCompare(aVal);
    } else {
      return sortDir === 'asc' ? aVal - bVal : bVal - aVal;
    }
  });

  const headers = ['Asset', 'Price', 'Value', 'Target %', 'Current %', 'Dev %'];

  const getHeaderClass = (index: number) => {
    if (sortCol !== index) return 'sortable';
    return `sortable ${sortDir}`;
  };

  return (
    <div className="glass-panel">
      <div className="glass-panel-title">ASSET PERFORMANCE</div>
      <div className="table-wrapper">
        <table>
          <thead>
            <tr>
              {headers.map((h, i) => (
                <th key={h} className={getHeaderClass(i)} onClick={() => handleSort(i)}>
                  {h}
                </th>
              ))}
            </tr>
          </thead>
          <tbody>
            {sortedAssets.map(asset => {
              const dev = asset.deviationPercent;
              const devClass = getDeviationClass(dev);
              const sign = getDeviationSign(dev);
              const usdSign = asset.deviationUSD > 0.001 ? '+' : '';

              return (
                <tr key={asset.symbol} className="hoverable">
                  <td className="symbol-col">{asset.symbol}</td>
                  <td className="mono-col">${formatCurrency(asset.price)}</td>
                  <td className="mono-col">${formatCurrency(asset.valueUSD)}</td>
                  <td>{formatPercent(asset.targetPercent)}%</td>
                  <td>{formatPercent(asset.currentPercent)}%</td>
                  <td className={devClass}>
                    <div className="performance-dev-container">
                      <span>
                        {sign}
                        {formatPercent(dev)}%
                      </span>
                      <span className="performance-dev-usd-label">
                        ({usdSign}${formatCurrency(asset.deviationUSD)})
                      </span>
                    </div>
                  </td>
                </tr>
              );
            })}
          </tbody>
        </table>
      </div>
    </div>
  );
};
