import React from 'react';
import {PortfolioSnapshot} from '@/types';
import {Activity, CircleDashed} from 'lucide-react';

interface TradeHistoryProps {
    history: PortfolioSnapshot[];
}

const TradeHistory: React.FC<TradeHistoryProps> = ({ history }) => {
    if (!history || history.length === 0) {
        return (
            <div className="glass-panel flex flex-col items-center justify-center py-16 text-slate-500">
                <CircleDashed size={48} className="mb-4 opacity-20" />
                <h2 className="text-lg font-medium text-slate-300">Recent Activity</h2>
                <p>No trading history available.</p>
            </div>
        );
    }

    return (
        <div className="glass-panel overflow-hidden flex flex-col">
            <h2 className="text-sm font-semibold text-slate-400 uppercase tracking-wider mb-4 flex items-center gap-2">
                <Activity size={18} className="text-blue-400" />
                Recent Activity
            </h2>
            <div className="overflow-y-auto max-h-[400px] -mx-6 px-6 pr-4 custom-scrollbar">
                <table className="w-full text-left border-collapse">
                    <thead className="sticky top-0 bg-slate-900/90 backdrop-blur-md z-10">
                        <tr className="text-xs uppercase text-slate-500 tracking-wider">
                            <th className="py-3 px-4 font-semibold w-48 rounded-tl-lg">Time</th>
                            <th className="py-3 px-4 font-semibold rounded-tr-lg">Action</th>
                        </tr>
                    </thead>
                    <tbody className="divide-y divide-slate-800/50">
                        {history.flatMap((snapshot, sIndex) => {
                            const dateStr = new Date(snapshot.timestamp).toLocaleString();

                            if (!snapshot.actions || snapshot.actions.length === 0) {
                                return (
                                    <tr key={`${sIndex}-no-action`} className="hover:bg-slate-800/30 transition-colors">
                                        <td className="py-3 px-4 text-sm text-slate-400 font-mono">{dateStr}</td>
                                        <td className="py-3 px-4">
                                            <span className="text-slate-500 italic text-sm flex items-center gap-2">
                                                <span className="w-1.5 h-1.5 rounded-full bg-slate-600"></span>
                                                No trades executed (Cycle complete)
                                            </span>
                                        </td>
                                    </tr>
                                );
                            }

                            return snapshot.actions.map((action, aIndex) => {
                                const isBuy = action.toUpperCase().startsWith('BUY');
                                const isSell = action.toUpperCase().startsWith('SELL');
                                
                                return (
                                    <tr key={`${sIndex}-${aIndex}`} className="hover:bg-slate-800/30 transition-colors">
                                        <td className="py-3 px-4 text-sm text-slate-400 font-mono">{dateStr}</td>
                                        <td className="py-3 px-4 flex items-center gap-3">
                                            <span className={`badge ${isBuy ? 'badge-buy' : (isSell ? 'badge-sell' : '')}`}>
                                                {isBuy ? 'BUY' : (isSell ? 'SELL' : 'INFO')}
                                            </span>
                                            <span className="text-slate-200 font-medium">{action}</span>
                                        </td>
                                    </tr>
                                );
                            });
                        })}
                    </tbody>
                </table>
            </div>
        </div>
    );
};

export default TradeHistory;
