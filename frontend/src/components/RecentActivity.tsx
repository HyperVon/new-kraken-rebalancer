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
    <div className="glass-card">
      <div className="card-title">
        <PulseIcon size={18} />
        RECENT ACTIVITY
      </div>
      {history.length === 0 ? (
        <div className="empty-box">
          <EmptyPieIcon size={48} />
          <h3 className="empty-box-title">RECENT ACTIVITY</h3>
          <p className="empty-box-text">No trading history</p>
        </div>
      ) : (
        <div className="table-wrapper">
          <table className="custom-table">
            <thead>
              <tr className="table-header-row">
                <th className="table-th">TIME</th>
                <th className="table-th">ACTION</th>
              </tr>
            </thead>
            <tbody>
              {history.map((snapshot, snapshotIndex) => {
                const timeStr = formatTimestamp(snapshot.timestamp);
                if (snapshot.actions.length === 0) {
                  return (
                    <tr key={`empty-${snapshotIndex}`} className="table-tr">
                      <td className="activity-cell-time-empty">{timeStr}</td>
                      <td className="activity-cell">
                        <span className="activity-no-trades">
                          <span className="activity-dot-empty"></span>
                          No trades executed
                        </span>
                      </td>
                    </tr>
                  );
                } else {
                  return snapshot.actions.map((action, actionIndex) => {
                    const badge = getBadgeDetails(action);
                    return (
                      <tr key={`action-${snapshotIndex}-${actionIndex}`} className="table-tr">
                        <td className="activity-cell-time">{timeStr}</td>
                        <td className="activity-cell">
                          <div className="activity-content">
                            <span className={badge.className}>{badge.label}</span>
                            <span className="activity-action-text">{action}</span>
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
