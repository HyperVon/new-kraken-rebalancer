

import type {ReactNode} from 'react';

interface StatusCardProps {
    title: string;
    value: string | number;
    subValue?: ReactNode;
    type?: 'neutral' | 'success' | 'danger';
    icon?: ReactNode;
}

const StatusCard = ({ title, value, subValue, type = 'neutral', icon }: StatusCardProps) => {
    let valueClass = 'text-2xl font-bold font-mono tracking-tight bg-clip-text text-transparent ';
    let borderClass = 'border-slate-800 hover:border-slate-700';

    if (type === 'success') {
        valueClass += 'bg-linear-to-br from-emerald-400 to-teal-500';
        borderClass = 'border-emerald-500/20 hover:border-emerald-500/40 shadow-[0_0_15px_rgba(16,185,129,0.05)]';
    } else if (type === 'danger') {
        valueClass += 'bg-linear-to-br from-rose-400 to-red-500';
        borderClass = 'border-rose-500/20 hover:border-rose-500/40 shadow-[0_0_15px_rgba(244,63,94,0.05)]';
    } else {
        valueClass += 'bg-linear-to-br from-white to-slate-400';
    }

    return (
        <div className={`glass-panel-compact flex flex-col justify-between group cursor-default transition-all duration-300 ${borderClass}`}>
            <div className="flex justify-between items-center mb-2">
                <h2 className="text-xs font-bold text-slate-500 uppercase tracking-wider group-hover:text-slate-300 transition-colors">
                    {title}
                </h2>
                {icon && (
                    <div className="text-slate-500 group-hover:text-blue-400 transition-colors duration-300">
                        {icon}
                    </div>
                )}
            </div>
            
            <div className="flex flex-wrap items-baseline justify-between gap-x-4 gap-y-1">
                <div className={valueClass}>
                    {value}
                </div>
                {subValue && (
                    <div className="subvalue-container text-slate-400 text-xs">
                        {subValue}
                    </div>
                )}
            </div>
        </div>
    );
};

export default StatusCard;
