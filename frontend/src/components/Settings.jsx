import React, { useState, useEffect } from 'react';

const Settings = ({ onBack }) => {
    const [config, setConfig] = useState(null);
    const [originalConfig, setOriginalConfig] = useState(null);
    const [loading, setLoading] = useState(true);
    const [saving, setSaving] = useState(false);
    const [error, setError] = useState(null);
    const [newSymbol, setNewSymbol] = useState('');
    const [newMessage, setNewMessage] = useState(null);

    useEffect(() => {
        fetchConfig();
    }, []);

    const fetchConfig = async () => {
        try {
            const res = await fetch('/api/config');
            if (!res.ok) throw new Error('Failed to fetch config');
            const data = await res.json();
            setConfig(data);
            setOriginalConfig(JSON.parse(JSON.stringify(data))); // Deep copy for rollback/comparison
        } catch (err) {
            setError(err.message);
        } finally {
            setLoading(false);
        }
    };

    const handleSettingChange = (field, value) => {
        setConfig(prev => ({
            ...prev,
            settings: {
                ...prev.settings,
                [field]: value
            }
        }));
    };

    const handleAllocationChange = (index, field, value) => {
        const newAllocations = [...config.allocations];
        newAllocations[index] = {
            ...newAllocations[index],
            [field]: field === 'targetPercent' ? parseFloat(value) : value
        };
        setConfig(prev => ({ ...prev, allocations: newAllocations }));
    };

    const addAllocation = () => {
        if (!newSymbol) return;
        if (config.allocations.some(a => a.symbol.toUpperCase() === newSymbol.toUpperCase())) {
            setNewMessage({ type: 'error', text: 'Symbol already exists' });
            return;
        }

        setConfig(prev => ({
            ...prev,
            allocations: [
                ...prev.allocations,
                { symbol: newSymbol.toUpperCase(), targetPercent: 0 }
            ]
        }));
        setNewSymbol('');
        setNewMessage(null);
    };

    const removeAllocation = (index) => {
        const newAllocations = config.allocations.filter((_, i) => i !== index);
        setConfig(prev => ({ ...prev, allocations: newAllocations }));
    };

    const saveConfig = async () => {
        setSaving(true);
        setNewMessage(null);
        try {
            const totalPct = config.allocations.reduce((sum, a) => sum + (a.targetPercent || 0), 0);
            if (Math.abs(totalPct - 100) > 0.01) {
                throw new Error(`Total allocation must be 100%. Current: ${totalPct.toFixed(2)}%`);
            }
            if (!config.allocations.some(a => a.symbol === 'USD')) {
                throw new Error('Must include USD allocation.');
            }

            const res = await fetch('/api/config', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(config)
            });

            if (!res.ok) throw new Error('Failed to save config');

            setNewMessage({ type: 'success', text: 'Configuration saved successfully!' });
            // Refresh original config
            const updated = await res.json();
            setConfig(updated);
            setOriginalConfig(JSON.parse(JSON.stringify(updated)));

        } catch (err) {
            setNewMessage({ type: 'error', text: err.message });
        } finally {
            setSaving(false);
        }
    };

    if (loading) return <div>Loading settings...</div>;
    if (error) return <div className="text-danger">Error: {error}</div>;

    const totalAllocated = config?.allocations.reduce((sum, a) => sum + (a.targetPercent || 0), 0) || 0;
    const isValidTotal = Math.abs(totalAllocated - 100) <= 0.01;

    return (
        <div className="card" style={{ maxWidth: '800px', margin: '0 auto' }}>
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '1rem' }}>
                <h2>Settings</h2>
                <button onClick={onBack} style={{ background: 'transparent', border: '1px solid #334155', color: 'white', padding: '0.5rem 1rem', borderRadius: '4px', cursor: 'pointer' }}>
                    &larr; Back to Dashboard
                </button>
            </div>

            {newMessage && (
                <div style={{
                    padding: '10px',
                    marginBottom: '1rem',
                    borderRadius: '4px',
                    background: newMessage.type === 'error' ? '#ef444420' : '#22c55e20',
                    color: newMessage.type === 'error' ? '#ef4444' : '#22c55e',
                    border: `1px solid ${newMessage.type === 'error' ? '#ef4444' : '#22c55e'}`
                }}>
                    {newMessage.text}
                </div>
            )}

            <div style={{ marginBottom: '2rem' }}>
                <h3>Global Parameters</h3>
                <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '1rem' }}>
                    <div>
                        <label style={{ display: 'block', marginBottom: '0.5rem', color: '#e2e8f0' }}>Loop Interval (Seconds)</label>
                        <input
                            type="number"
                            style={{ width: '100%', padding: '0.5rem', background: '#0f172a', border: '1px solid #334155', color: 'white' }}
                            value={config.settings.loopDelaySeconds}
                            onChange={(e) => handleSettingChange('loopDelaySeconds', parseInt(e.target.value))}
                        />
                    </div>
                    <div>
                        <label style={{ display: 'block', marginBottom: '0.5rem', color: '#e2e8f0' }}>Deviation Trigger (%)</label>
                        <input
                            type="number"
                            step="0.1"
                            style={{ width: '100%', padding: '0.5rem', background: '#0f172a', border: '1px solid #334155', color: 'white' }}
                            value={config.settings.deviationTriggerPercent}
                            onChange={(e) => handleSettingChange('deviationTriggerPercent', parseFloat(e.target.value))}
                        />
                    </div>
                    <div>
                        <label style={{ display: 'block', marginBottom: '0.5rem', color: '#e2e8f0' }}>Dust Threshold ($)</label>
                        <input
                            type="number"
                            step="0.5"
                            style={{ width: '100%', padding: '0.5rem', background: '#0f172a', border: '1px solid #334155', color: 'white' }}
                            value={config.settings.dustThresholdUSD}
                            onChange={(e) => handleSettingChange('dustThresholdUSD', parseFloat(e.target.value))}
                        />
                    </div>
                    <div>
                        <label style={{ display: 'block', marginBottom: '0.5rem', color: '#e2e8f0' }}>Fiat Max Drawdown (%)</label>
                        <input
                            type="number"
                            step="1.0"
                            style={{ width: '100%', padding: '0.5rem', background: '#0f172a', border: '1px solid #334155', color: 'white' }}
                            value={config.settings.fiatMaxDrawdown ?? 0}
                            onChange={(e) => handleSettingChange('fiatMaxDrawdown', parseFloat(e.target.value))}
                        />
                    </div>
                    <div>
                        <label style={{ display: 'block', marginBottom: '0.5rem', color: '#e2e8f0' }}>Fiat Deployment Exponent</label>
                        <input
                            type="number"
                            step="0.1"
                            style={{ width: '100%', padding: '0.5rem', background: '#0f172a', border: '1px solid #334155', color: 'white' }}
                            value={config.settings.fiatDeploymentExponent ?? 1.0}
                            onChange={(e) => handleSettingChange('fiatDeploymentExponent', parseFloat(e.target.value))}
                        />
                    </div>
                    <div style={{ display: 'flex', alignItems: 'center' }}>
                        <label style={{ display: 'flex', alignItems: 'center', gap: '10px', cursor: 'pointer', color: '#e2e8f0' }}>
                            <input
                                type="checkbox"
                                style={{ width: '20px', height: '20px' }}
                                checked={config.settings.dryRun}
                                onChange={(e) => handleSettingChange('dryRun', e.target.checked)}
                            />
                            <span>Dry Run Mode (Safe)</span>
                        </label>
                    </div>
                </div>
            </div>

            <div style={{ marginBottom: '2rem' }}>
                <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '1rem' }}>
                    <h3>Allocations</h3>
                    <div style={{
                        color: isValidTotal ? '#22c55e' : '#ef4444',
                        fontWeight: 'bold'
                    }}>
                        Total: {totalAllocated.toFixed(2)}%
                    </div>
                </div>

                <div style={{ display: 'flex', flexDirection: 'column', gap: '0.5rem' }}>
                    {config.allocations.map((alloc, idx) => (
                        <div key={idx} style={{ display: 'flex', gap: '1rem', alignItems: 'center' }}>
                            <div style={{ width: '120px', fontWeight: 'bold' }}>{alloc.symbol}</div>
                            <input
                                type="number"
                                step="0.1"
                                style={{ flex: 1, padding: '0.5rem', background: '#0f172a', border: '1px solid #334155', color: 'white' }}
                                value={alloc.targetPercent}
                                onChange={(e) => handleAllocationChange(idx, 'targetPercent', e.target.value)}
                            />
                            <div style={{ width: '30px', textAlign: 'center' }}>%</div>
                            <button
                                onClick={() => removeAllocation(idx)}
                                style={{
                                    background: '#ef4444',
                                    color: 'white',
                                    border: 'none',
                                    padding: '0.5rem',
                                    borderRadius: '4px',
                                    cursor: 'pointer',
                                    width: '30px',
                                    height: '30px',
                                    display: 'flex',
                                    alignItems: 'center',
                                    justifyContent: 'center'
                                }}
                                title="Remove Asset"
                            >
                                ✕
                            </button>
                        </div>
                    ))}
                </div>

                <div style={{ marginTop: '1rem', display: 'flex', gap: '1rem' }}>
                    <input
                        type="text"
                        placeholder="New Symbol (e.g. DOT)"
                        style={{ padding: '0.5rem', background: '#0f172a', border: '1px solid #334155', color: 'white' }}
                        value={newSymbol}
                        onChange={(e) => setNewSymbol(e.target.value)}
                    />
                    <button
                        onClick={addAllocation}
                        disabled={!newSymbol}
                        style={{ background: '#3b82f6', color: 'white', border: 'none', padding: '0.5rem 1rem' }}
                    >
                        Add Asset
                    </button>
                </div>
            </div>

            <div style={{ borderTop: '1px solid #334155', paddingTop: '1rem', display: 'flex', gap: '1rem', justifyContent: 'flex-end' }}>
                <button
                    onClick={saveConfig}
                    disabled={saving || !isValidTotal}
                    style={{
                        background: isValidTotal ? '#22c55e' : '#94a3b8',
                        color: 'white',
                        border: 'none',
                        padding: '0.75rem 2rem',
                        fontSize: '1rem',
                        fontWeight: 'bold',
                        cursor: isValidTotal ? 'pointer' : 'not-allowed'
                    }}
                >
                    {saving ? 'Saving...' : 'Save Configuration'}
                </button>
            </div>
        </div>
    );
};

export default Settings;
