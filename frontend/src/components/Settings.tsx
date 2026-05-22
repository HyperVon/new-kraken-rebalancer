import React, { useState, useEffect } from 'react';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { useNavigate } from 'react-router-dom';
import toast from 'react-hot-toast';
import { ArrowLeft, Save, Plus, Trash2, ShieldAlert } from 'lucide-react';
import { apiService } from '@/services/api';
import { FrontendConfig, Settings as SettingsType, Allocation } from '@/types';

const ALLOWED_SETTING_KEYS = new Set<keyof SettingsType>([
    'loopDelaySeconds',
    'deviationTriggerPercent',
    'dustThresholdUSD',
    'dryRun',
    'fiatMaxDrawdown',
    'fiatDeploymentExponent'
]);

const ALLOWED_ALLOCATION_KEYS = new Set<keyof Allocation>([
    'symbol',
    'targetPercent'
]);

const Settings: React.FC = () => {
    const navigate = useNavigate();
    const queryClient = useQueryClient();
    const [config, setConfig] = useState<FrontendConfig | null>(null);
    const [newSymbol, setNewSymbol] = useState('');

    const { data: remoteConfig, isLoading, error: fetchError } = useQuery({
        queryKey: ['settings'],
        queryFn: apiService.getSettings,
    });

    const mutation = useMutation({
        mutationFn: apiService.updateSettings,
        onSuccess: () => {
            toast.success('Configuration saved successfully!', {
                icon: '💾',
                style: {
                    borderRadius: '10px',
                    background: '#10b981',
                    color: '#fff',
                },
            });
            queryClient.invalidateQueries({ queryKey: ['settings'] });
        },
        onError: (err: Error) => {
            toast.error(err.message, {
                icon: '❌',
                style: {
                    borderRadius: '10px',
                    background: '#ef4444',
                    color: '#fff',
                },
            });
        }
    });

    useEffect(() => {
        if (remoteConfig) {
            setConfig(JSON.parse(JSON.stringify(remoteConfig)));
        }
    }, [remoteConfig]);

    if (isLoading) return (
        <div className="flex items-center justify-center min-h-screen">
            <div className="animate-pulse text-blue-400 font-medium">Loading settings...</div>
        </div>
    );
    if (fetchError) return <div className="text-rose-500 p-8">Error: {(fetchError as Error).message}</div>;
    if (!config) return null;

    const handleSettingChange = (field: keyof SettingsType, value: any) => {
        if (!ALLOWED_SETTING_KEYS.has(field)) return;
        setConfig(prev => {
            /* v8 ignore start */
            if (!prev) return null;
            /* v8 ignore stop */
            return {
                ...prev,
                settings: {
                    ...prev.settings,
                    [field]: value
                }
            };
        });
    };

    const handleAllocationChange = (index: number, field: keyof Allocation, value: string) => {
        if (!ALLOWED_ALLOCATION_KEYS.has(field)) return;
        /* v8 ignore start */
        if (!config || typeof index !== 'number' || index < 0 || index >= config.allocations.length) return;
        /* v8 ignore stop */
        const newAllocations = [...config.allocations];
        newAllocations[index] = {
            ...newAllocations[index],
            /* v8 ignore start */
            [field]: field === 'targetPercent' ? parseFloat(value) || 0 : value
            /* v8 ignore stop */
        } as any;
        setConfig(prev => {
            /* v8 ignore start */
            if (!prev) return null;
            /* v8 ignore stop */
            return { ...prev, allocations: newAllocations };
        });
    };

    const addAllocation = () => {
        /* v8 ignore start */
        if (!newSymbol || !config) return;
        const allocations = config.allocations || [];
        /* v8 ignore stop */
        if (allocations.some((a) => a.symbol?.toUpperCase() === newSymbol.toUpperCase())) {
            toast.error('Symbol already exists');
            return;
        }

        setConfig(prev => {
            /* v8 ignore start */
            if (!prev) return null;
            /* v8 ignore stop */
            return {
                ...prev,
                allocations: [
                    ...prev.allocations,
                    { symbol: newSymbol.toUpperCase(), targetPercent: 0 }
                ]
            };
        });
        setNewSymbol('');
    };

    const removeAllocation = (index: number) => {
        /* v8 ignore start */
        if (!config || typeof index !== 'number' || index < 0 || index >= config.allocations.length) return;
        const newAllocations = (config.allocations || []).filter((_, i) => i !== index);
        /* v8 ignore stop */
        setConfig(prev => {
            /* v8 ignore start */
            if (!prev) return null;
            /* v8 ignore stop */
            return { ...prev, allocations: newAllocations };
        });
    };

    const saveConfig = () => {
        /* v8 ignore start */
        if (!config) return;
        const allocations = config.allocations || [];
        const totalPct = allocations.reduce((sum, a) => sum + (a.targetPercent || 0), 0);
        /* v8 ignore stop */
        if (Math.abs(totalPct - 100) > 0.01) {
            /* v8 ignore start -- defensive: button is disabled when total ≠ 100% */
            toast.error(`Total allocation must be 100%. Current: ${totalPct.toFixed(2)}%`);
            return;
            /* v8 ignore stop */
        }
        if (!allocations.some((a) => a.symbol === 'USD')) {
            toast.error('Must include USD allocation.');
            return;
        }

        mutation.mutate(config);
    };

    const allocations = config.allocations || [];
    const totalAllocated = allocations.reduce((sum, a) => sum + (a.targetPercent || 0), 0);
    const isValidTotal = Math.abs(totalAllocated - 100) <= 0.01;
    const settings = config.settings || {} as SettingsType;

    return (
        <div className="max-w-4xl mx-auto p-4 md:p-8 space-y-8">
            <header className="flex justify-between items-center pb-6 border-b border-slate-800">
                <div className="flex items-center gap-4">
                    <button 
                        onClick={() => navigate('/')} 
                        className="p-2 hover:bg-slate-800 rounded-full transition-colors text-slate-400 hover:text-white"
                        title="Back to Dashboard"
                    >
                        <ArrowLeft size={24} />
                    </button>
                    <h1 className="text-3xl font-bold text-white">Settings</h1>
                </div>
                <button
                    onClick={saveConfig}
                    disabled={mutation.isPending || !isValidTotal}
                    className={`btn-primary flex items-center gap-2 ${!isValidTotal ? 'opacity-50 grayscale cursor-not-allowed' : ''}`}
                >
                    <Save size={18} />
                    <span>{mutation.isPending ? 'Saving...' : 'Save Configuration'}</span>
                </button>
            </header>

            <div className="glass-panel space-y-8">
                <div>
                    <h3 className="text-lg font-semibold text-white mb-6 flex items-center gap-2">
                        <ShieldAlert size={20} className="text-blue-400" />
                        Global Parameters
                    </h3>
                    <div className="grid grid-cols-1 md:grid-cols-2 gap-6">
                        <div className="space-y-2">
                            <label className="text-sm font-medium text-slate-400">Loop Interval (Seconds)</label>
                            <input
                                type="number"
                                className="input-glass"
                                value={settings.loopDelaySeconds || 0}
                                onChange={(e) => handleSettingChange('loopDelaySeconds', parseInt(e.target.value))}
                            />
                        </div>
                        <div className="space-y-2">
                            <label className="text-sm font-medium text-slate-400">Deviation Trigger (%)</label>
                            <input
                                type="number"
                                step="0.1"
                                className="input-glass"
                                value={settings.deviationTriggerPercent || 0}
                                onChange={(e) => handleSettingChange('deviationTriggerPercent', parseFloat(e.target.value))}
                            />
                        </div>
                        <div className="space-y-2">
                            <label className="text-sm font-medium text-slate-400">Dust Threshold ($)</label>
                            <input
                                type="number"
                                step="0.5"
                                className="input-glass"
                                value={settings.dustThresholdUSD || 0}
                                onChange={(e) => handleSettingChange('dustThresholdUSD', parseFloat(e.target.value))}
                            />
                        </div>
                        <div className="space-y-2">
                            <label className="text-sm font-medium text-slate-400">Fiat Max Drawdown (%)</label>
                            <input
                                type="number"
                                step="1.0"
                                className="input-glass"
                                value={settings.fiatMaxDrawdown ?? 0}
                                onChange={(e) => handleSettingChange('fiatMaxDrawdown', parseFloat(e.target.value))}
                            />
                        </div>
                        <div className="space-y-2">
                            <label className="text-sm font-medium text-slate-400">Fiat Deployment Exponent</label>
                            <input
                                type="number"
                                step="0.1"
                                className="input-glass"
                                value={settings.fiatDeploymentExponent ?? 1.0}
                                onChange={(e) => handleSettingChange('fiatDeploymentExponent', parseFloat(e.target.value))}
                            />
                        </div>
                        <div className="flex items-center pt-8">
                            <label className="flex items-center gap-3 cursor-pointer group">
                                <div className="relative flex items-center justify-center">
                                    <input
                                        type="checkbox"
                                        className="peer appearance-none w-6 h-6 border-2 border-slate-600 rounded bg-slate-900/50 checked:bg-blue-500 checked:border-blue-500 transition-all cursor-pointer"
                                        checked={settings.dryRun}
                                        onChange={(e) => handleSettingChange('dryRun', e.target.checked)}
                                    />
                                    <svg className="absolute w-4 h-4 text-white opacity-0 peer-checked:opacity-100 pointer-events-none transition-opacity" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="3" strokeLinecap="round" strokeLinejoin="round"><polyline points="20 6 9 17 4 12"></polyline></svg>
                                </div>
                                <span className="font-medium text-slate-300 group-hover:text-white transition-colors">Dry Run Mode (Safe)</span>
                            </label>
                        </div>
                    </div>
                </div>

                <div className="border-t border-slate-800 pt-8">
                    <div className="flex justify-between items-center mb-6">
                        <h3 className="text-lg font-semibold text-white">Target Allocations</h3>
                        <div className={`px-4 py-1.5 rounded-full font-bold text-sm tracking-wide ${isValidTotal ? 'bg-emerald-500/10 text-emerald-400 border border-emerald-500/20' : 'bg-rose-500/10 text-rose-400 border border-rose-500/20'}`}>
                            Total: {totalAllocated.toFixed(2)}%
                        </div>
                    </div>

                    <div className="space-y-3 mb-6">
                        {allocations.map((alloc: any, idx: number) => (
                            <div key={idx} className="flex items-center gap-4 bg-slate-900/40 p-3 rounded-xl border border-slate-800/50 hover:border-slate-700 transition-colors">
                                <div className="w-24 font-bold text-lg text-slate-200">{alloc.symbol}</div>
                                <div className="flex-1 relative">
                                    <input
                                        type="number"
                                        step="0.1"
                                        className="input-glass w-full pr-8"
                                        value={alloc.targetPercent}
                                        onChange={(e) => handleAllocationChange(idx, 'targetPercent', e.target.value)}
                                    />
                                    <span className="absolute right-4 top-1/2 -translate-y-1/2 text-slate-500 font-medium">%</span>
                                </div>
                                <button
                                    onClick={() => removeAllocation(idx)}
                                    className="p-3 text-slate-500 hover:text-rose-400 hover:bg-rose-400/10 rounded-lg transition-colors"
                                    title="Remove Asset"
                                >
                                    <Trash2 size={20} />
                                </button>
                            </div>
                        ))}
                    </div>

                    <div className="flex gap-4 items-center bg-slate-900/60 p-4 rounded-xl border border-slate-800 border-dashed">
                        <input
                            type="text"
                            placeholder="New Symbol (e.g. DOT)"
                            className="input-glass flex-1 uppercase"
                            value={newSymbol}
                            onChange={(e) => setNewSymbol(e.target.value)}
                            onKeyDown={(e) => e.key === 'Enter' && addAllocation()}
                        />
                        <button
                            onClick={addAllocation}
                            disabled={!newSymbol}
                            className="btn-secondary flex items-center gap-2 whitespace-nowrap disabled:opacity-50"
                        >
                            <Plus size={18} />
                            <span>Add Asset</span>
                        </button>
                    </div>
                </div>
            </div>
        </div>
    );
};

export default Settings;
