import React from 'react';

interface StatusCardProps {
    title: string;
    value: string | number;
    subValue?: React.ReactNode;
    type?: 'neutral' | 'success' | 'danger';
}

const StatusCard: React.FC<StatusCardProps> = ({ title, value, subValue, type = 'neutral' }) => {
    let valueClass = 'text-3xl font-bold font-mono tracking-tight bg-clip-text text-transparent ';
    let borderClass = 'border-slate-800 hover:border-slate-700';

    if (type === 'success') {
        valueClass += 'bg-gradient-to-br from-emerald-400 to-teal-500';
        borderClass = 'border-emerald-500/20 hover:border-emerald-500/40 shadow-[0_0_15px_rgba(16,185,129,0.05)]';
    } else if (type === 'danger') {
        valueClass += 'bg-gradient-to-br from-rose-400 to-red-500';
        borderClass = 'border-rose-500/20 hover:border-rose-500/40 shadow-[0_0_15px_rgba(244,63,94,0.05)]';
    } else {
        valueClass += 'bg-gradient-to-br from-white to-slate-400';
    }

    return (
        <div className={`glass-panel flex flex-col justify-between group cursor-default transition-all duration-500 ${borderClass}`}>
            <h2 className="text-sm font-semibold text-slate-400 uppercase tracking-wider mb-2 group-hover:text-slate-300 transition-colors">
                {title}
            </h2>
            <div className={valueClass}>
                {value}
            </div>
            {subValue && (
                <div className="mt-4 pt-4 border-t border-slate-800/50">
                    {subValue}
                </div>
            )}
        </div>
    );
};

export default StatusCard;
