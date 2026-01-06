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
                            // If no actions, maybe skip or show "No actions"
                            if (!snapshot.actions || snapshot.actions.length === 0) return [];

                            return snapshot.actions.map((action, aIndex) => {
                                const isBuy = action.toUpperCase().startsWith('BUY');
                                const isSell = action.toUpperCase().startsWith('SELL');
                                let badgeClass = 'badge';
                                if (isBuy) badgeClass += ' badge-buy';
                                else if (isSell) badgeClass += ' badge-sell';

                                return (
                                    <tr key={`${sIndex}-${aIndex}`}>
                                        <td style={{ color: '#94a3b8' }}>{new Date(snapshot.timestamp).toLocaleString()}</td>
                                        <td>
                                            <span className={badgeClass}>{isBuy ? 'BUY' : (isSell ? 'SELL' : 'INFO')}</span>
                                            <span style={{ marginLeft: '10px' }}>{action}</span>
                                        </td>
                                    </tr>
                                );
                            });
                        })}
                        {history.every(h => !h.actions || h.actions.length === 0) && (
                            <tr><td colSpan="2" style={{ textAlign: 'center', color: '#94a3b8' }}>No recent trades</td></tr>
                        )}
                    </tbody>
                </table>
            </div>
        </div>
    );
};

export default TradeHistory;
