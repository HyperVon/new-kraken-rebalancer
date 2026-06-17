import React from 'react';
import { PulseIcon, EmptyPieIcon } from './Icons';

interface PortfolioSnapshot {
  timestamp: string;
  actions: string[];
}

interface RecentActivityProps {
  history: PortfolioSnapshot[];
}

const formatTimestamp = (isoString: string): string => {
  const date = new Date(isoString);
  const yyyy = date.getFullYear();
  const mm = String(date.getMonth() + 1).padStart(2, '0');
  const dd = String(date.getDate()).padStart(2, '0');

  let hours = date.getHours();
  const ampm = hours >= 12 ? 'PM' : 'AM';
  hours = hours % 12;
  hours = hours ? hours : 12;
  const hh = String(hours).padStart(2, '0');
  const min = String(date.getMinutes()).padStart(2, '0');
  const ss = String(date.getSeconds()).padStart(2, '0');

  return `${yyyy}-${mm}-${dd} ${hh}:${min}:${ss} ${ampm}`;
};

const getBadgeDetails = (action: string) => {
  const upper = action.toUpperCase();
  if (upper.startsWith('BUY') || upper.includes(' BUY ')) {
    return { className: 'badge badge-buy', label: 'BUY' };
  }
  if (upper.startsWith('SELL') || upper.includes(' SELL ')) {
    return { className: 'badge badge-sell', label: 'SELL' };
  }
  return { className: 'badge badge-info', label: 'INFO' };
};

export const RecentActivity: React.FC<RecentActivityProps> = ({ history }) => {
  return (
    <div className="glass-panel">
      <div className="glass-panel-title">
        <PulseIcon />
        RECENT ACTIVITY
      </div>
      {history.length === 0 ? (
        <div className="empty-history-box">
          <EmptyPieIcon />
          <h3>RECENT ACTIVITY</h3>
          <p>No trading history</p>
        </div>
      ) : (
        <div className="table-wrapper custom-scrollbar max-h-100">
          <table>
            <thead>
              <tr>
                <th>TIME</th>
                <th>ACTION</th>
              </tr>
            </thead>
            <tbody>
              {history.map((snapshot, snapshotIndex) => {
                const timeStr = formatTimestamp(snapshot.timestamp);
                if (snapshot.actions.length === 0) {
                  return (
                    <tr key={`empty-${snapshotIndex}`} className="hoverable">
                      <td className="mono-col">{timeStr}</td>
                      <td>
                        <span className="recent-activity-empty-text">
                          <span className="recent-activity-dot-marker"></span>
                          No trades executed
                        </span>
                      </td>
                    </tr>
                  );
                } else {
                  return snapshot.actions.map((action, actionIndex) => {
                    const badge = getBadgeDetails(action);
                    return (
                      <tr key={`action-${snapshotIndex}-${actionIndex}`} className="hoverable">
                        <td className="mono-col">{timeStr}</td>
                        <td>
                          <div className="recent-activity-row-container">
                            <span className={badge.className}>{badge.label}</span>
                            <span>{action}</span>
                          </div>
                        </td>
                      </tr>
                    );
                  });
                }
              })}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
};
