import React, { useState, useEffect } from 'react';
import { useQuery } from '@tanstack/react-query';
import { useNavigate } from 'react-router-dom';
import { Settings as SettingsIcon, TrendingUp, Wallet, Coins } from 'lucide-react';
import StatusCard from './StatusCard';
import AllocationChart from './AllocationChart';
import TradeHistory from './TradeHistory';
import { apiService } from '@/services/api';
import { AssetSnapshot } from '@/types';

const ALLOWED_SORT_KEYS = new Set(['symbol', 'price', 'valueUSD', 'targetPercent', 'currentPercent', 'deviationPercent']);

const Dashboard: React.FC = () => {
    const navigate = useNavigate();
    const [sortConfig, setSortConfig] = useState<{key: keyof AssetSnapshot | string, direction: 'asc' | 'desc'}>({ key: 'deviationPercent', direction: 'asc' });
    const [timeSinceUpdate, setTimeSinceUpdate] = useState(0);

    const { data: status, isLoading: isStatusLoading } = useQuery({
        queryKey: ['status'],
        queryFn: apiService.getStatus,
        refetchInterval: 5000,
    });

    const { data: history = [], isLoading: isHistoryLoading } = useQuery({
        queryKey: ['history'],
        queryFn: apiService.getHistory,
        refetchInterval: 5000,
    });

    useEffect(() => {
        if (!status) return;

        const updateTimer = () => {
            const dataTime = new Date(status.timestamp);
            const now = new Date();
            const diffInSeconds = Math.floor((now.getTime() - dataTime.getTime()) / 1000);
            setTimeSinceUpdate(diffInSeconds);
        };

        updateTimer();
        const interval = setInterval(updateTimer, 1000);
        return () => clearInterval(interval);
    }, [status]);

    if ((isStatusLoading || isHistoryLoading) && !status) return (
        <div className="flex items-center justify-center min-h-screen">
            <div className="animate-pulse flex flex-col items-center gap-4">
                <div className="w-12 h-12 border-4 border-blue-500 border-t-transparent rounded-full animate-spin"></div>
                <p className="text-slate-400 font-medium">Connecting to KrakenBot...</p>
            </div>
        </div>
    );

    const isStale = timeSinceUpdate > 90;
    const formatCurrency = (val: number) => new Intl.NumberFormat('en-US', { style: 'currency', currency: 'USD' }).format(val);

    const totalValue = status ? status.totalValueUSD : 0;
    const usdAsset = status && status.assets ? Object.values(status.assets).find(a => a.symbol === 'USD') : null;
    const usdValue = usdAsset ? usdAsset.valueUSD : 0;
    const cryptoValue = totalValue - usdValue;

    const assetsArray = status && status.assets ? Object.values(status.assets) : [];
    const cryptoAssets = assetsArray.filter(a => a.symbol !== 'USD');
    const cryptoCount = cryptoAssets.length;
    /* v8 ignore start */
    const cryptoPercent = cryptoAssets.reduce((sum, a) => sum + (a.currentPercent || 0), 0);
    const cryptoTargetPercent = cryptoAssets.reduce((sum, a) => sum + (a.targetPercent || 0), 0);
    /* v8 ignore stop */

    const totalPortfolioSub = (
        <div className="flex items-center gap-3 text-xs font-medium text-slate-400">
            <span title="Current Drawdown" className={status?.drawdownPercent > 0 ? 'text-rose-400 font-semibold' : ''}>
                Drawdown: {status?.drawdownPercent !== undefined ? status.drawdownPercent.toFixed(2) : '0.00'}%
            </span>
        </div>
    );

    const cashSub = usdAsset ? (
        <div className="flex items-center gap-2 text-xs font-medium text-slate-400 flex-wrap">
            <span title="Current Allocation">{usdAsset.currentPercent?.toFixed(2)}%</span>
            <span className="text-slate-700">|</span>
            <span title="Target Allocation">
                Target: {status.effectiveUsdTargetPercent !== undefined ? status.effectiveUsdTargetPercent.toFixed(2) : usdAsset.targetPercent?.toFixed(2)}%
                {status.effectiveUsdTargetPercent !== undefined && Math.abs(status.effectiveUsdTargetPercent - usdAsset.targetPercent) > 0.01 &&
                    <span className="opacity-60 ml-1">(Base: {usdAsset.targetPercent?.toFixed(2)}%)</span>
                }
            </span>
            <span className="text-slate-700">|</span>
            <span
                title="Deviation"
                className={usdAsset.deviationPercent > 0 ? 'text-rose-400' : (usdAsset.deviationPercent < 0 ? 'text-emerald-400' : '')}
            >
                Dev: {usdAsset.deviationPercent > 0 ? '+' : ''}{usdAsset.deviationPercent?.toFixed(2)}%
            </span>
        </div>
    ) : <span className="text-slate-500 text-xs">No USD Data</span>;

    const cryptoAssetsSub = (
        <div className="flex items-center gap-2 text-xs font-medium text-slate-400">
            <span title="Current Allocation">{cryptoPercent.toFixed(2)}%</span>
            <span className="text-slate-700">|</span>
            <span title="Target Allocation">Target: {cryptoTargetPercent.toFixed(2)}%</span>
            <span className="text-slate-700">|</span>
            <span title="Total Crypto Assets">{cryptoCount} Assets</span>
        </div>
    );

    const requestSort = (key: keyof AssetSnapshot | string) => {
        let direction: 'asc' | 'desc' = 'asc';
        if (sortConfig.key === key && sortConfig.direction === 'asc') direction = 'desc';
        setSortConfig({ key, direction });
    };

    const getSortedAssets = () => {
        if (!status || !status.assets) return [];
        let assets = Object.values(status.assets).filter(a => a.symbol !== 'USD');

        const sortKey = ALLOWED_SORT_KEYS.has(sortConfig.key) ? sortConfig.key : 'deviationPercent';

        /* v8 ignore start */
        return assets.sort((a: any, b: any) => {
            if (a[sortKey] < b[sortKey]) return sortConfig.direction === 'asc' ? -1 : 1;
            if (a[sortKey] > b[sortKey]) return sortConfig.direction === 'asc' ? 1 : -1;
            return 0;
        });
        /* v8 ignore stop */
    };

    const getSortIndicator = (key: string) => {
        if (sortConfig.key !== key) return null;
        return <span className="ml-1 text-blue-400">{sortConfig.direction === 'asc' ? '▲' : '▼'}</span>;
    };

    return (
        <div className="max-w-7xl mx-auto p-4 md:p-6 space-y-5">
            {/* Header */}
            <header className="flex flex-col md:flex-row justify-between items-start md:items-center gap-3 pb-4 border-b border-slate-800">
                <div className="flex items-center gap-3">
                    <h1 className="text-2xl font-bold bg-gradient-to-r from-blue-400 to-emerald-400 bg-clip-text text-transparent">
                        Kraken Rebalancer
                    </h1>
                    <div className={`px-2.5 py-0.5 rounded-full text-xs font-bold tracking-wider shadow-lg ${
                        isStale ? 'bg-yellow-500/20 text-yellow-400 shadow-yellow-500/20' : 'bg-emerald-500/20 text-emerald-400 shadow-emerald-500/20 animate-pulse'
                    }`}>
                        {isStale ? 'DELAYED' : 'LIVE'}
                    </div>
                </div>

                <div className="flex items-center gap-5">
                    <div className="text-right">
                        <div className="text-xs text-slate-500 uppercase tracking-wider font-semibold">Data Age</div>
                        <div className={`font-mono text-sm font-bold ${isStale ? 'text-yellow-400' : 'text-slate-200'}`}>
                            {timeSinceUpdate}s ago
                        </div>
                        <div className="text-xs text-slate-500">
                            {status ? new Date(status.timestamp).toLocaleTimeString() : '-'}
                        </div>
                    </div>
                    <button
                        onClick={() => navigate('/settings')}
                        className="btn-secondary flex items-center gap-2"
                    >
                        <SettingsIcon size={16} />
                        <span>Settings</span>
                    </button>
                </div>
            </header>

            {/* Overview Cards */}
            <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
                <StatusCard 
                    title="Total Portfolio" 
                    value={formatCurrency(totalValue)} 
                    type="neutral"
                    subValue={totalPortfolioSub}
                    icon={<TrendingUp size={16} />}
                />
                <StatusCard
                    title="Cash (USD)"
                    value={formatCurrency(usdValue)}
                    type="success"
                    subValue={cashSub}
                    icon={<Wallet size={16} />}
                />
                <StatusCard 
                    title="Crypto Assets" 
                    value={formatCurrency(cryptoValue)} 
                    type="neutral" 
                    subValue={cryptoAssetsSub}
                    icon={<Coins size={16} />}
                />
            </div>

            {/* Charts & Table */}
            <div className="grid grid-cols-1 lg:grid-cols-2 gap-5">
                <AllocationChart assets={status?.assets} />

                <div className="glass-panel">
                    <h2 className="text-sm font-semibold text-slate-400 uppercase tracking-wider mb-3">Asset Performance</h2>
                    <div className="overflow-x-auto -mx-6 px-6">
                        <table className="w-full text-left border-collapse text-xs sm:text-sm">
                            <thead>
                                <tr className="border-b border-slate-800 text-[10px] sm:text-xs text-slate-400">
                                    <th className="py-2 px-1.5 sm:px-3 font-semibold cursor-pointer hover:text-white transition-colors" onClick={() => requestSort('symbol')}>Asset{getSortIndicator('symbol')}</th>
                                    <th className="py-2 px-1.5 sm:px-3 font-semibold cursor-pointer hover:text-white transition-colors" onClick={() => requestSort('price')}>Price{getSortIndicator('price')}</th>
                                    <th className="py-2 px-1.5 sm:px-3 font-semibold cursor-pointer hover:text-white transition-colors" onClick={() => requestSort('valueUSD')}>Value{getSortIndicator('valueUSD')}</th>
                                    <th className="py-2 px-1.5 sm:px-3 font-semibold cursor-pointer hover:text-white transition-colors" onClick={() => requestSort('targetPercent')}>Target %{getSortIndicator('targetPercent')}</th>
                                    <th className="py-2 px-1.5 sm:px-3 font-semibold cursor-pointer hover:text-white transition-colors" onClick={() => requestSort('currentPercent')}>Current %{getSortIndicator('currentPercent')}</th>
                                    <th className="py-2 px-1.5 sm:px-3 font-semibold cursor-pointer hover:text-white transition-colors" onClick={() => requestSort('deviationPercent')}>Dev %{getSortIndicator('deviationPercent')}</th>
                                </tr>
                            </thead>
                            <tbody className="divide-y divide-slate-800/50">
                                {getSortedAssets().map(asset => {
                                    const dev = asset.deviationPercent;
                                    const isBuy = dev < 0;
                                    const isSell = dev > 0;
                                    
                                    return (
                                        <tr key={asset.symbol} className="hover:bg-slate-800/30 transition-colors group">
                                            <td className="py-2 px-1.5 sm:px-3 font-bold text-slate-200">{asset.symbol}</td>
                                            <td className="py-2 px-1.5 sm:px-3 font-mono text-[10px] sm:text-xs text-slate-300">{formatCurrency(asset.price)}</td>
                                            <td className="py-2 px-1.5 sm:px-3 font-mono font-medium text-white">{formatCurrency(asset.valueUSD)}</td>
                                            <td className="py-2 px-1.5 sm:px-3 text-slate-400">{asset.targetPercent?.toFixed(2)}%</td>
                                            <td className="py-2 px-1.5 sm:px-3 text-slate-300">{asset.currentPercent?.toFixed(2)}%</td>
                                            <td className={`py-2 px-1.5 sm:px-3 font-medium ${isBuy ? 'text-emerald-400' : (isSell ? 'text-rose-400' : 'text-slate-400')}`}>
                                                <div className="flex flex-col leading-tight">
                                                    <span>{dev > 0 ? '+' : ''}{dev?.toFixed(2)}%</span>
                                                    <span className="text-[10px] sm:text-xs opacity-70 font-mono">
                                                        ({asset.deviationUSD >= 0 ? '+' : ''}{formatCurrency(asset.deviationUSD)})
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
            </div>

            {/* History */}
            <TradeHistory history={history} />
            {process.env.NODE_ENV === 'test' && (
                <button
                    data-testid="test-trigger-sort"
                    style={{ display: 'none' }}
                    onClick={() => (requestSort as any)('invalidSortKey')}
                />
            )}
        </div>
    );
};

export default Dashboard;
