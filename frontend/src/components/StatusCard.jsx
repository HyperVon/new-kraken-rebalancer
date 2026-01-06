import React from 'react';

const StatusCard = ({ title, value, subValue, type = 'neutral' }) => {
    let valueClass = 'stat-value';
    if (type === 'success') valueClass += ' text-success';
    if (type === 'danger') valueClass += ' text-danger';

    return (
        <div className="card">
            <h2>{title}</h2>
            <div className={valueClass}>{value}</div>
            {subValue && <div className="stat-label">{subValue}</div>}
        </div>
    );
};

export default StatusCard;
