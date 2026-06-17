import React from 'react';
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { OverviewGrid } from '../src/components/OverviewGrid';
import { AllocationChart } from '../src/components/AllocationChart';
import { PerformanceTable } from '../src/components/PerformanceTable';
import { RecentActivity } from '../src/components/RecentActivity';
import { SettingsForm } from '../src/components/SettingsForm';
import { App } from '../src/App';

// Helper mock data
const mockSnapshot = {
  timestamp: new Date().toISOString(),
  totalValueUSD: 10000,
  assets: {
    USD: {
      symbol: 'USD',
      balance: 4000,
      price: 1,
      valueUSD: 4000,
      targetPercent: 40,
      currentPercent: 40,
      deviationPercent: 0,
      deviationUSD: 0
    },
    BTC: {
      symbol: 'BTC',
      balance: 0.1,
      price: 50000,
      valueUSD: 5000,
      targetPercent: 50,
      currentPercent: 50,
      deviationPercent: 0,
      deviationUSD: 0
    },
    ETH: {
      symbol: 'ETH',
      balance: 1,
      price: 1000,
      valueUSD: 1000,
      targetPercent: 10,
      currentPercent: 10,
      deviationPercent: 0,
      deviationUSD: 0
    }
  },
  actions: ['BUY BTC ($100)'],
  drawdownPercent: 5.5,
  fiatDeploymentPercent: 12.5,
  effectiveUsdTargetPercent: 37.5
};

const mockConfig = {
  settings: {
    loopDelaySeconds: 60,
    deviationTriggerPercent: 2.0,
    dustThresholdUSD: 1.0,
    dryRun: true,
    fiatMaxDrawdown: 10.0,
    fiatDeploymentExponent: 1.5
  },
  allocations: [
    { symbol: 'USD', targetPercent: 40 },
    { symbol: 'BTC', targetPercent: 50 },
    { symbol: 'ETH', targetPercent: 10 }
  ]
};

// EventSource mock
class MockEventSource {
  onmessage: ((event: MessageEvent) => void) | null = null;
  onerror: ((err: any) => void) | null = null;
  close = vi.fn();
  constructor(public url: string) {
    MockEventSource.instances.push(this);
  }
  static instances: MockEventSource[] = [];
  static clear() {
    MockEventSource.instances = [];
  }
}

describe('Frontend Component Tests', () => {
  beforeEach(() => {
    vi.stubGlobal('EventSource', MockEventSource);
    MockEventSource.clear();
    global.fetch = vi.fn().mockImplementation(() =>
      Promise.resolve({
        ok: true,
        json: () => Promise.resolve([])
      } as any)
    );
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  describe('OverviewGrid', () => {
    it('renders total value, cash card and crypto details correctly', () => {
      render(<OverviewGrid latest={mockSnapshot} />);

      expect(screen.getByText('TOTAL PORTFOLIO')).toBeDefined();
      expect(screen.getByText('$10,000.00')).toBeDefined();
      expect(screen.getByText('Drawdown: 5.50%')).toBeDefined();

      expect(screen.getByText('CASH (USD)')).toBeDefined();
      expect(screen.getByText('$4,000.00')).toBeDefined();

      expect(screen.getByText('CRYPTO ASSETS')).toBeDefined();
      expect(screen.getByText('$6,000.00')).toBeDefined();
      expect(screen.getByText(/2 assets/)).toBeDefined();
    });

    it('renders placeholder when USD asset is missing', () => {
      const missingUSD = {
        ...mockSnapshot,
        assets: {
          BTC: mockSnapshot.assets.BTC
        }
      };
      render(<OverviewGrid latest={missingUSD} />);
      expect(screen.getByText('No USD data')).toBeDefined();
    });
  });

  describe('AllocationChart', () => {
    it('renders visual chart segments sorted by value', () => {
      render(<AllocationChart latest={mockSnapshot} />);

      expect(screen.getByText('PORTFOLIO ALLOCATION')).toBeDefined();
      expect(screen.getByText('BTC')).toBeDefined();
      expect(screen.getByText('USD')).toBeDefined();
      expect(screen.getByText('ETH')).toBeDefined();

      expect(screen.getByText('$5,000.00 (50.00%)')).toBeDefined();
      expect(screen.getByText('$4,000.00 (40.00%)')).toBeDefined();
      expect(screen.getByText('$1,000.00 (10.00%)')).toBeDefined();
    });
  });

  describe('PerformanceTable', () => {
    it('renders columns and responds to sorting', () => {
      const { container } = render(<PerformanceTable latest={mockSnapshot} />);

      expect(screen.getByText('ASSET PERFORMANCE')).toBeDefined();
      expect(screen.getByText('BTC')).toBeDefined();
      expect(screen.getByText('ETH')).toBeDefined();

      // Click on symbol header to sort alphabetically
      const assetHeader = screen.getByText('Asset');
      fireEvent.click(assetHeader);

      // Wait for re-render and query using DOM selector
      let cells = container.querySelectorAll('.symbol-col');
      expect(cells[0].textContent).toBe('ETH');
      expect(cells[1].textContent).toBe('BTC');

      // Click again to reverse
      fireEvent.click(assetHeader);
      cells = container.querySelectorAll('.symbol-col');
      expect(cells[0].textContent).toBe('BTC');
      expect(cells[1].textContent).toBe('ETH');
    });
  });

  describe('RecentActivity', () => {
    it('renders logs of completed trades', () => {
      render(<RecentActivity history={[mockSnapshot]} />);
      expect(screen.getByText('RECENT ACTIVITY')).toBeDefined();
      expect(screen.getByText('BUY BTC ($100)')).toBeDefined();
      expect(screen.getByText('BUY')).toBeDefined();
    });

    it('renders empty message if history is empty', () => {
      render(<RecentActivity history={[]} />);
      expect(screen.getByText('No trading history')).toBeDefined();
    });

    it('renders standard message if no trades executed in snapshot', () => {
      const noTrades = { ...mockSnapshot, actions: [] };
      render(<RecentActivity history={[noTrades]} />);
      expect(screen.getByText('No trades executed')).toBeDefined();
    });
  });

  describe('SettingsForm', () => {
    const defaultProps = {
      initialSettings: mockConfig.settings,
      initialAllocations: mockConfig.allocations,
      onBack: vi.fn(),
      onSave: vi.fn().mockResolvedValue(undefined),
      errorMessage: null
    };

    it('renders form inputs correctly', () => {
      render(<SettingsForm {...defaultProps} />);
      expect(screen.getByText('GLOBAL PARAMETERS')).toBeDefined();
      expect(screen.getByText('TARGET ALLOCATIONS')).toBeDefined();
      expect(screen.getByDisplayValue(60)).toBeDefined();
      expect(screen.getByDisplayValue(2)).toBeDefined();
    });

    it('adds new assets and validates allocations correctly', async () => {
      const { container } = render(<SettingsForm {...defaultProps} />);
      
      const addInput = screen.getByPlaceholderText('BTC, ETH, etc...');
      fireEvent.change(addInput, { target: { value: 'LTC' } });
      const addButton = screen.getByText('Add Asset');
      fireEvent.click(addButton);

      // Verify LTC is added to the allocation rows
      expect(screen.getByText('LTC')).toBeDefined();

      // Find the input wrapper for ETH (under allocations-container) and change its percentage to 20
      const ethRow = screen.getByText('ETH').closest('.allocation-edit-row');
      const ethInput = ethRow?.querySelector('input');
      expect(ethInput).toBeDefined();
      
      fireEvent.change(ethInput!, { target: { value: '20' } });

      const totalBadge = container.querySelector('#total-allocated-display');
      expect(totalBadge?.textContent).toContain('110.00%');

      // The save button should be disabled when total != 100%
      const saveBtn = screen.getByRole('button', { name: /Save Configuration/ });
      expect((saveBtn as HTMLButtonElement).disabled).toBe(true);
    });

    it('submits form with updated parameters on save', async () => {
      const onSaveMock = vi.fn().mockResolvedValue(undefined);
      render(<SettingsForm {...defaultProps} onSave={onSaveMock} />);

      const saveBtn = screen.getByRole('button', { name: /Save/ });
      expect((saveBtn as HTMLButtonElement).disabled).toBe(false);

      fireEvent.click(saveBtn);
      await waitFor(() => {
        expect(onSaveMock).toHaveBeenCalled();
      });
    });
  });

  describe('App', () => {
    it('shows connection spinner while loading data', () => {
      render(<App />);
      expect(screen.getByText('Connecting to portfolio stream...')).toBeDefined();
    });

    it('fetches config/history and establishes SSE updates', async () => {
      const fetchSpy = vi.spyOn(global, 'fetch').mockImplementation((url) => {
        if (url === '/api/history') {
          return Promise.resolve({
            ok: true,
            json: () => Promise.resolve([mockSnapshot])
          } as any);
        }
        if (url === '/api/config') {
          return Promise.resolve({
            ok: true,
            json: () => Promise.resolve(mockConfig)
          } as any);
        }
        return Promise.reject(new Error('Unknown url'));
      });

      render(<App />);

      // Wait for App to load and show dashboard elements
      await waitFor(() => {
        expect(screen.getByText('TOTAL PORTFOLIO')).toBeDefined();
      });

      expect(fetchSpy).toHaveBeenCalledWith('/api/history');
      expect(fetchSpy).toHaveBeenCalledWith('/api/config');
      expect(MockEventSource.instances.length).toBe(1);

      // Verify that SSE events trigger update
      const eventInstance = MockEventSource.instances[0];
      const updatedSnapshot = {
        ...mockSnapshot,
        totalValueUSD: 12000,
        timestamp: new Date().toISOString()
      };

      if (eventInstance.onmessage) {
        eventInstance.onmessage({ data: JSON.stringify(updatedSnapshot) } as MessageEvent);
      }

      await waitFor(() => {
        expect(screen.getByText('$12,000.00')).toBeDefined();
      });
    });
  });
});
