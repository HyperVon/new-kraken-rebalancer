import React, { useEffect, useState } from 'react';
import StatusCard from './StatusCard';
import AllocationChart from './AllocationChart';
import TradeHistory from './TradeHistory';
import Settings from './Settings';

const Dashboard = () => {
    const [status, setStatus] = useState(null);
    const [history, setHistory] = useState([]);
    const [loading, setLoading] = useState(true);
    const [sortConfig, setSortConfig] = useState({ key: 'valueUSD', direction: 'desc' });
    const [lastUpdatedFormatted, setLastUpdatedFormatted] = useState('-');
    const [timeSinceUpdate, setTimeSinceUpdate] = useState(0);
    const [showSettings, setShowSettings] = useState(false);

    const fetchData = async () => {
        try {
            const statusRes = await fetch('/api/status');
            if (statusRes.ok) {
                const statusData = await statusRes.json();
                setStatus(prev => {
                    // Check if value changed significantly to perhaps trigger an animation
                    return statusData;
                });
            }

            const historyRes = await fetch('/api/history');
            if (historyRes.ok) {
                const historyData = await historyRes.json();
                setHistory(historyData);
            }
        } catch (error) {
            console.error("Error fetching data:", error);
        } finally {
            setLoading(false);
        }
    };

    useEffect(() => {
        if (!status) return;

        const updateTimer = () => {
            const dataTime = new Date(status.timestamp);
            const now = new Date();
            const diffInSeconds = Math.floor((now - dataTime) / 1000);
            setTimeSinceUpdate(diffInSeconds);
        };

        updateTimer(); // Initial update
        const interval = setInterval(updateTimer, 1000);

        return () => clearInterval(interval);
    }, [status]); // Re-run when status changes

    // Main polling loop
    useEffect(() => {
        // Don't poll aggressively if in settings
        if (showSettings) return;

        fetchData();
        const dataInterval = setInterval(fetchData, 5000);
        return () => clearInterval(dataInterval);
    }, [showSettings]);

    if (loading && !status && !showSettings) return <div className="dashboard-container">Loading...</div>;

    if (showSettings) {
        return <Settings onBack={() => setShowSettings(false)} />;
    }

    const isStale = timeSinceUpdate > 90; // Warn if data is older than 90s (cycle is ~60s)
    const liveBadgeColor = isStale ? '#eab308' : '#22c55e'; // Yellow if stale, Green if live
    const liveBadgeText = isStale ? 'DELAYED' : 'LIVE';

    // Helpers
    const formatCurrency = (val) => new Intl.NumberFormat('en-US', { style: 'currency', currency: 'USD' }).format(val);

    const totalValue = status ? status.totalValueUSD : 0;
    const usdAsset = status && status.assets ? Object.values(status.assets).find(a => a.symbol === 'USD') : null;
    const usdValue = usdAsset ? usdAsset.valueUSD : 0;
    const cryptoValue = totalValue - usdValue;

    // Sorting Logic
    const requestSort = (key) => {
        let direction = 'asc';
        if (sortConfig.key === key && sortConfig.direction === 'asc') {
            direction = 'desc';
        }
        setSortConfig({ key, direction });
    };

    const getSortedAssets = () => {
        if (!status || !status.assets) return [];
        let assets = Object.values(status.assets).filter(a => a.symbol !== 'USD');

        return assets.sort((a, b) => {
            if (a[sortConfig.key] < b[sortConfig.key]) {
                return sortConfig.direction === 'asc' ? -1 : 1;
            }
            if (a[sortConfig.key] > b[sortConfig.key]) {
                return sortConfig.direction === 'asc' ? 1 : -1;
            }
            return 0;
        });
    };

    const getSortIndicator = (key) => {
        if (sortConfig.key !== key) return null;
        return sortConfig.direction === 'asc' ? ' ▲' : ' ▼';
    };

    return (
        <div className="dashboard-container">
            <div className="header">
                <div style={{ display: 'flex', alignItems: 'center', gap: '10px' }}>
                    <h1>Kraken Rebalancer</h1>
                    <span style={{
                        background: liveBadgeColor,
                        color: '#000',
                        padding: '2px 8px',
                        borderRadius: '12px',
                        fontSize: '0.75rem',
                        fontWeight: 'bold',
                        boxShadow: `0 0 10px ${liveBadgeColor}80`, // Hex with opacity
                        animation: isStale ? 'none' : 'pulse 2s infinite'
                    }}>{liveBadgeText}</span>
                </div>
                <div style={{ display: 'flex', gap: '1rem', alignItems: 'center' }}>
                    <div style={{ textAlign: 'right' }}>
                        <div style={{ fontSize: '0.875rem', color: '#e2e8f0' }}>Data Age</div>
                        <div style={{ fontWeight: 'bold', color: isStale ? '#eab308' : 'inherit' }}>{timeSinceUpdate}s ago</div>
                        <div style={{ fontSize: '0.75rem', color: '#cbd5e1' }}>
                            {status ? new Date(status.timestamp).toLocaleTimeString() : '-'}
                        </div>
                    </div>
                    <button
                        onClick={() => setShowSettings(true)}
                        style={{
                            background: '#334155',
                            color: 'white',
                            border: 'none',
                            padding: '0.5rem 1rem',
                            borderRadius: '4px',
                            cursor: 'pointer',
                            display: 'flex',
                            alignItems: 'center',
                            gap: '0.5rem'
                        }}
                    >
                        <span>⚙️ Settings</span>
                    </button>
                </div>
            </div>

            <div className="grid-cols-2" style={{ gridTemplateColumns: 'repeat(auto-fit, minmax(200px, 1fr))', marginBottom: '2rem' }}>
                <StatusCard title="Total Portfolio" value={formatCurrency(totalValue)} type="neutral"
                    subValue={status?.drawdownPercent > 0 ? <span className="text-danger" style={{ fontSize: '0.8rem' }}>Drawdown: {status.drawdownPercent.toFixed(2)}%</span> : null}
                />
                <StatusCard
                    title="Cash (USD)"
                    value={formatCurrency(usdValue)}
                    subValue={
                        usdAsset ? (
                            <div style={{ display: 'flex', alignItems: 'center', gap: '8px', fontSize: '0.875rem' }}>
                                <span title="Current Allocation">{usdAsset.currentPercent?.toFixed(2)}%</span>
                                <span style={{ color: 'var(--text-secondary)' }}>|</span>
                                <span title="Target Allocation" style={{ color: 'var(--text-secondary)' }}>
                                    Target: {status.effectiveUsdTargetPercent !== undefined ? status.effectiveUsdTargetPercent.toFixed(2) : usdAsset.targetPercent?.toFixed(2)}%
                                    {status.effectiveUsdTargetPercent !== undefined && Math.abs(status.effectiveUsdTargetPercent - usdAsset.targetPercent) > 0.01 &&
                                        <span style={{ fontSize: '0.75em', opacity: 0.8 }}> (Base: {usdAsset.targetPercent?.toFixed(2)}%)</span>
                                    }
                                </span>
                                <span style={{ color: 'var(--text-secondary)' }}>|</span>
                                <span
                                    title="Deviation"
                                    style={{ color: usdAsset.deviationPercent > 0 ? 'var(--danger-color)' : (usdAsset.deviationPercent < 0 ? 'var(--success-color)' : 'inherit') }}
                                >
                                    Dev: {usdAsset.deviationPercent > 0 ? '+' : ''}{usdAsset.deviationPercent?.toFixed(2)}%
                                </span>
                            </div>
                        ) : '-'
                    }
                    type="success"
                />
                <StatusCard title="Crypto Assets" value={formatCurrency(cryptoValue)} type="neutral" />
            </div>

            <div className="grid-cols-2">
                <AllocationChart assets={status?.assets} />

                <div className="card">
                    <h2>Asset Performance</h2>
                    <table>
                        <thead>
                            <tr style={{ cursor: 'pointer' }}>
                                <th onClick={() => requestSort('symbol')}>Asset{getSortIndicator('symbol')}</th>
                                <th onClick={() => requestSort('price')}>Price{getSortIndicator('price')}</th>
                                <th onClick={() => requestSort('valueUSD')}>Value{getSortIndicator('valueUSD')}</th>
                                <th onClick={() => requestSort('targetPercent')}>Target %{getSortIndicator('targetPercent')}</th>
                                <th onClick={() => requestSort('currentPercent')}>Current %{getSortIndicator('currentPercent')}</th>
                                <th onClick={() => requestSort('deviationPercent')}>Dev %{getSortIndicator('deviationPercent')}</th>
                            </tr>
                        </thead>
                        <tbody>
                            {getSortedAssets().map(asset => {
                                const dev = asset.deviationPercent;
                                let devClass = '';
                                if (dev > 0) devClass = 'text-danger'; // Overweight -> Sell
                                if (dev < 0) devClass = 'text-success'; // Underweight -> Buy

                                return (
                                    <tr key={asset.symbol}>
                                        <td style={{ fontWeight: 'bold' }}>{asset.symbol}</td>
                                        <td>{formatCurrency(asset.price)}</td>
                                        <td>{formatCurrency(asset.valueUSD)}</td>
                                        <td>{asset.targetPercent?.toFixed(2)}%</td>
                                        <td>{asset.currentPercent?.toFixed(2)}%</td>
                                        <td className={devClass}>{dev?.toFixed(2)}%</td>
                                    </tr>
                                );
                            })}
                        </tbody>
                    </table>
                </div>
            </div>

            <TradeHistory history={history} />
        </div>
    );
};

export default Dashboard;
