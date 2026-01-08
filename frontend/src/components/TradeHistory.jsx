import React from 'react';

const TradeHistory = ({ history }) => {
    if (!history || history.length === 0) return <div className="card"><h2>Recent Activity</h2><p>No history available.</p></div>;

    return (
        <div className="card">
            <h2>Recent Activity</h2>
            <div style={{ maxHeight: '400px', overflowY: 'auto' }}>
                <table>
                    <thead>
                        <tr>
                            <th style={{ width: '180px' }}>Time</th>
                            <th>Action</th>
                        </tr>
                    </thead>
                    <tbody>
                        {history.flatMap((snapshot, sIndex) => {
                            // Convert timestamp
                            const date = new Date(snapshot.timestamp);
                            // Handle numeric timestamp (seconds vs ms) - simplistic check
                            // If timestamp is number and small (seconds), multiply by 1000
                            // But cleaner is to fix backend. For now, let's assume valid Date input or fix visual.

                            const dateStr = isNaN(date.getTime()) && typeof snapshot.timestamp === 'number'
                                ? new Date(snapshot.timestamp * 1000).toLocaleString()
                                : new Date(snapshot.timestamp).toLocaleString();

                            if (!snapshot.actions || snapshot.actions.length === 0) {
                                return (
                                    <tr key={`${sIndex}-no-action`}>
                                        <td style={{ color: '#e2e8f0' }}>{dateStr}</td>
                                        <td>
                                            <span style={{ color: '#cbd5e1', fontStyle: 'italic' }}>No trades executed (Cycle complete)</span>
                                        </td>
                                    </tr>
                                );
                            }

                            return snapshot.actions.map((action, aIndex) => {
                                const isBuy = action.toUpperCase().startsWith('BUY');
                                const isSell = action.toUpperCase().startsWith('SELL');
                                let badgeClass = 'badge';
                                if (isBuy) badgeClass += ' badge-buy';
                                else if (isSell) badgeClass += ' badge-sell';

                                return (
                                    <tr key={`${sIndex}-${aIndex}`}>
                                        <td style={{ color: '#e2e8f0' }}>{dateStr}</td>
                                        <td>
                                            <span className={badgeClass}>{isBuy ? 'BUY' : (isSell ? 'SELL' : 'INFO')}</span>
                                            <span style={{ marginLeft: '10px' }}>{action}</span>
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
