import React, { useState } from 'react';
import { BackArrowIcon, FloppyDiskIcon, PlusIcon, ShieldExclamationIcon } from './Icons';

interface Allocation {
  symbol: string;
  targetPercent: number;
}

interface Settings {
  loopDelaySeconds: number;
  deviationTriggerPercent: number;
  dustThresholdUSD: number;
  dryRun: boolean;
  fiatMaxDrawdown: number;
  fiatDeploymentExponent: number;
}

interface SettingsFormProps {
  initialSettings: Settings;
  initialAllocations: Allocation[];
  onBack: () => void;
  onSave: (settings: Settings, allocations: Allocation[]) => Promise<void>;
  errorMessage: string | null;
}

export const SettingsForm: React.FC<SettingsFormProps> = ({
  initialSettings,
  initialAllocations,
  onBack,
  onSave,
  errorMessage: initialErrorMessage
}) => {
  const [settings, setSettings] = useState<Settings>({ ...initialSettings });
  const [allocations, setAllocations] = useState<Allocation[]>([...initialAllocations]);
  const [newSymbol, setNewSymbol] = useState<string>('');
  const [error, setError] = useState<string | null>(initialErrorMessage);
  const [isSaving, setIsSaving] = useState<boolean>(false);

  const totalPercent = allocations.reduce((sum, a) => sum + a.targetPercent, 0);
  const hasUsd = allocations.some(a => a.symbol.toUpperCase() === 'USD');
  const isValid = Math.abs(totalPercent - 100) <= 0.01 && hasUsd;

  const handleSettingChange = <K extends keyof Settings>(key: K, value: Settings[K]) => {
    setSettings(prev => ({
      ...prev,
      [key]: value
    }));
  };

  const handleAllocationPercentChange = (index: number, valStr: string) => {
    const val = parseFloat(valStr) || 0;
    setAllocations(prev => {
      const updated = [...prev];
      updated[index] = { ...updated[index], targetPercent: val };
      return updated;
    });
  };

  const handleRemoveAllocation = (index: number) => {
    setAllocations(prev => prev.filter((_, i) => i !== index));
  };

  const handleAddAsset = () => {
    const symbol = newSymbol.trim().toUpperCase();
    if (!symbol) return;

    if (allocations.some(a => a.symbol.toUpperCase() === symbol)) {
      alert('Symbol already exists');
      return;
    }

    setAllocations(prev => [...prev, { symbol, targetPercent: 0.0 }]);
    setNewSymbol('');
  };

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!isValid) return;

    setIsSaving(true);
    setError(null);
    try {
      await onSave(settings, allocations);
    } catch (err: unknown) {
      const message = err instanceof Error ? err.message : 'Failed to save configuration';
      setError(message);
    } finally {
      setIsSaving(false);
    }
  };

  return (
    <form onSubmit={handleSubmit}>
      <header className="app-header">
        <div className="header-brand-back">
          <button 
            type="button" 
            className="btn-icon" 
            onClick={onBack}
          >
            <BackArrowIcon size={18} />
          </button>
          <h1 className="header-logo">
            Settings
          </h1>
        </div>
        <button 
          type="submit" 
          className="btn-primary" 
          id="save-button" 
          disabled={!isValid || isSaving}
        >
          <FloppyDiskIcon size={18} />
          <span>{isSaving ? 'Saving...' : 'Save Configuration'}</span>
        </button>
      </header>

      {error && (
        <div className="error-box">
          {error}
        </div>
      )}

      <div className="glass-card">
        {/* Global parameters */}
        <div className="settings-section">
          <div className="card-title">
            <ShieldExclamationIcon size={18} />
            GLOBAL PARAMETERS
          </div>
          <div className="settings-grid">
            <div className="form-group">
              <label className="form-label">Loop Interval (Seconds)</label>
              <input
                type="number"
                className="input-glass"
                min="1"
                value={settings.loopDelaySeconds}
                onChange={e => handleSettingChange('loopDelaySeconds', parseInt(e.target.value) || 60)}
              />
            </div>

            <div className="form-group">
              <label className="form-label">Deviation Trigger Percent (%)</label>
              <input
                type="number"
                step="0.1"
                min="0"
                className="input-glass"
                value={settings.deviationTriggerPercent}
                onChange={e => handleSettingChange('deviationTriggerPercent', parseFloat(e.target.value) || 0)}
              />
            </div>

            <div className="form-group">
              <label className="form-label">Dust Threshold (USD)</label>
              <input
                type="number"
                step="0.5"
                min="0"
                className="input-glass"
                value={settings.dustThresholdUSD}
                onChange={e => handleSettingChange('dustThresholdUSD', parseFloat(e.target.value) || 0)}
              />
            </div>

            <div className="form-group">
              <label className="form-label">Fiat Max Drawdown (%)</label>
              <input
                type="number"
                step="1"
                min="0"
                max="100"
                className="input-glass"
                value={settings.fiatMaxDrawdown}
                onChange={e => handleSettingChange('fiatMaxDrawdown', parseFloat(e.target.value) || 0)}
              />
            </div>

            <div className="form-group">
              <label className="form-label">Fiat Deployment Exponent</label>
              <input
                type="number"
                step="0.1"
                min="0.1"
                className="input-glass"
                value={settings.fiatDeploymentExponent}
                onChange={e => handleSettingChange('fiatDeploymentExponent', parseFloat(e.target.value) || 1)}
              />
            </div>

            <div className="form-group-checkbox">
              <label className="toggle-label">
                <div className="toggle-wrapper">
                  <input
                    type="checkbox"
                    className="sr-only peer"
                    checked={settings.dryRun}
                    onChange={e => handleSettingChange('dryRun', e.target.checked)}
                  />
                  <div className="toggle-bg"></div>
                  <div className="toggle-knob"></div>
                </div>
                <span className="toggle-text">Dry Run Mode</span>
              </label>
            </div>
          </div>
        </div>

        {/* Target allocations */}
        <div>
          <div className="settings-allocations-header">
            <h3 className="settings-allocations-title">TARGET ALLOCATIONS</h3>
            <div className={`allocation-badge ${
              isValid ? 'allocation-badge-valid' : 'allocation-badge-invalid'
            }`} id="total-allocated-display">
              Total: {totalPercent.toFixed(2)}%
            </div>
          </div>

          <div className="allocations-list-grid" id="allocations-container">
            {allocations.map((alloc, idx) => (
              <div key={alloc.symbol} className="allocation-edit-row">
                <div className="allocation-symbol">{alloc.symbol}</div>
                <div className="allocation-input-wrapper">
                  <input
                    type="number"
                    step="0.1"
                    className="allocation-input"
                    value={alloc.targetPercent}
                    onChange={e => handleAllocationPercentChange(idx, e.target.value)}
                  />
                  <span className="allocation-percent-label">%</span>
                </div>
                <button 
                  type="button" 
                  className="btn-remove" 
                  onClick={() => handleRemoveAllocation(idx)}
                >
                  Remove
                </button>
              </div>
            ))}
          </div>

          <div className="dashed-box">
            <input
              type="text"
              className="add-asset-input"
              placeholder="BTC, ETH, etc..."
              value={newSymbol}
              onChange={e => setNewSymbol(e.target.value)}
              onKeyDown={e => {
                if (e.key === 'Enter') {
                  e.preventDefault();
                  handleAddAsset();
                }
              }}
            />
            <button 
              type="button" 
              className="btn-add-asset" 
              onClick={handleAddAsset}
            >
              <PlusIcon size={16} />
              <span>Add Asset</span>
            </button>
          </div>
        </div>
      </div>
    </form>
  );
};
