import React, { useState, useEffect } from 'react';
import { CogIcon } from './components/Icons';
import { OverviewGrid } from './components/OverviewGrid';
import { AllocationChart } from './components/AllocationChart';
import { PerformanceTable } from './components/PerformanceTable';
import { RecentActivity } from './components/RecentActivity';
import { SettingsForm } from './components/SettingsForm';
import './index.css';

interface AssetSnapshot {
  symbol: string;
  balance: number;
  price: number;
  valueUSD: number;
  targetPercent: number;
  currentPercent: number;
  deviationPercent: number;
  deviationUSD: number;
}

interface PortfolioSnapshot {
  timestamp: string;
  totalValueUSD: number;
  assets: Record<string, AssetSnapshot>;
  actions: string[];
  drawdownPercent: number;
  fiatDeploymentPercent: number;
  effectiveUsdTargetPercent: number;
}

interface AppConfig {
  settings: {
    loopDelaySeconds: number;
    deviationTriggerPercent: number;
    dustThresholdUSD: number;
    dryRun: boolean;
    fiatMaxDrawdown: number;
    fiatDeploymentExponent: number;
  };
  allocations: {
    symbol: string;
    targetPercent: number;
  }[];
}

export const App: React.FC = () => {
  const [view, setView] = useState<'dashboard' | 'settings'>(
    window.location.pathname === '/settings' ? 'settings' : 'dashboard'
  );
  const [latestSnapshot, setLatestSnapshot] = useState<PortfolioSnapshot | null>(null);
  const [history, setHistory] = useState<PortfolioSnapshot[]>([]);
  const [config, setConfig] = useState<AppConfig | null>(null);
  const [timeSinceUpdate, setTimeSinceUpdate] = useState<number>(0);

  // Sync client-side router
  const navigateTo = (newView: 'dashboard' | 'settings') => {
    const path = newView === 'settings' ? '/settings' : '/';
    window.history.pushState({}, '', path);
    setView(newView);
  };

  useEffect(() => {
    const handlePopState = () => {
      setView(window.location.pathname === '/settings' ? 'settings' : 'dashboard');
    };
    window.addEventListener('popstate', handlePopState);
    return () => window.removeEventListener('popstate', handlePopState);
  }, []);

  // Fetch initial history and configuration
  useEffect(() => {
    const fetchData = async () => {
      try {
        const historyRes = await fetch('/api/history');
        if (historyRes.ok) {
          const historyData = await historyRes.json();
          setHistory(historyData);
          if (historyData.length > 0) {
            setLatestSnapshot(historyData[0]);
          }
        }
      } catch (err) {
        console.error('Failed to fetch history', err);
      }

      try {
        const configRes = await fetch('/api/config');
        if (configRes.ok) {
          const configData = await configRes.json();
          setConfig(configData);
        }
      } catch (err) {
        console.error('Failed to fetch config', err);
      }
    };

    fetchData();
  }, []);

  // Establish Server-Sent Events stream
  useEffect(() => {
    const eventSource = new EventSource('/api/status/stream');

    eventSource.onmessage = (event) => {
      try {
        const snapshot = JSON.parse(event.data);
        setLatestSnapshot(snapshot);
        setHistory(prev => {
          const exists = prev.some(s => s.timestamp === snapshot.timestamp);
          if (exists) return prev;
          const updated = [snapshot, ...prev];
          if (updated.length > 50) updated.pop();
          return updated;
        });
      } catch (err) {
        console.error('Failed to parse SSE snapshot', err);
      }
    };

    eventSource.onerror = (err) => {
      console.error('SSE connection error:', err);
    };

    return () => {
      eventSource.close();
    };
  }, []);

  // Live timer for tracking snapshot data age
  useEffect(() => {
    if (!latestSnapshot) return;

    const updateAge = () => {
      const epoch = new Date(latestSnapshot.timestamp).getTime();
      const diff = Math.floor(Math.max(0, Date.now() - epoch) / 1000);
      setTimeSinceUpdate(diff);
    };

    updateAge();
    const interval = setInterval(updateAge, 1000);
    return () => clearInterval(interval);
  }, [latestSnapshot]);

  const handleSaveConfig = async (
    settings: AppConfig['settings'],
    allocations: AppConfig['allocations']
  ) => {
    const response = await fetch('/api/config', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json'
      },
      body: JSON.stringify({ settings, allocations })
    });

    if (response.ok) {
      // Re-fetch config to sync state
      const configRes = await fetch('/api/config');
      if (configRes.ok) {
        const configData = await configRes.json();
        setConfig(configData);
      }
      navigateTo('dashboard');
    } else {
      const errorData = await response.json();
      throw new Error(errorData.error || 'Failed to save settings');
    }
  };

  if (view === 'settings' && config) {
    return (
      <div className="settings-container">
        <SettingsForm
          initialSettings={config.settings}
          initialAllocations={config.allocations}
          onBack={() => navigateTo('dashboard')}
          onSave={handleSaveConfig}
          errorMessage={null}
        />
      </div>
    );
  }

  if (!latestSnapshot) {
    return (
      <div className="main-container">
        <div className="spinner-container">
          <div className="spinner"></div>
          <p className="loading-text">Connecting to portfolio stream...</p>
        </div>
      </div>
    );
  }

  const isStale = timeSinceUpdate > 90;
  const localTimeStr = new Date(latestSnapshot.timestamp).toLocaleTimeString();

  return (
    <div className="main-container">
      {/* Decorative Blur Glow Blobs */}
      <div className="background-blobs">
        <div className="blob-blue"></div>
        <div className="blob-emerald"></div>
      </div>

      <header className="app-header">
        <div className="header-brand">
          <h1 className="header-logo">
            Kraken Portfolio Rebalancer
          </h1>
          <div className={isStale ? 'badge-delayed' : 'badge-live'}>
            {isStale ? 'DELAYED' : 'LIVE'}
          </div>
        </div>

        <div className="header-actions">
          <div className="header-meta">
            <div className="header-meta-label">DATA AGE</div>
            <div className={isStale ? 'header-meta-value-stale' : 'header-meta-value'}>
              {timeSinceUpdate}s ago
            </div>
            <div className="header-meta-sub">{localTimeStr}</div>
          </div>
          <button 
            className="btn-secondary" 
            onClick={() => navigateTo('settings')}
          >
            <CogIcon size={18} />
            <span>Settings</span>
          </button>
        </div>
      </header>

      <OverviewGrid latest={latestSnapshot} />

      <div className="dashboard-grid-2">
        <AllocationChart latest={latestSnapshot} />
        <PerformanceTable latest={latestSnapshot} />
      </div>

      <RecentActivity history={history} />
    </div>
  );
};
export default App;
