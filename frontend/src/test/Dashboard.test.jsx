import { render, screen, waitFor, act } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import Dashboard from '../components/Dashboard';

// Mock child components to isolate Dashboard logic
vi.mock('../components/AllocationChart', () => ({
    default: ({ assets }) => <div data-testid="allocation-chart">{assets ? 'Chart Loaded' : 'No Chart'}</div>
}));

vi.mock('../components/TradeHistory', () => ({
    default: ({ history }) => <div data-testid="trade-history">{history.length} trades</div>
}));

vi.mock('../components/Settings', () => ({
    default: ({ onBack }) => (
        <div data-testid="settings-view">
            <button onClick={onBack}>Back</button>
        </div>
    )
}));

vi.mock('../components/StatusCard', () => ({
    default: ({ title, value, subValue, type }) => (
        <div data-testid={`status-card-${title.replace(/\s+/g, '-').toLowerCase()}`}>
            <span data-testid="card-title">{title}</span>
            <span data-testid="card-value">{value}</span>
            {subValue && <span data-testid="card-subvalue">{subValue}</span>}
        </div>
    )
}));

describe('Dashboard', () => {
    const mockStatus = {
        timestamp: new Date().toISOString(),
        totalValueUSD: 50000,
        drawdownPercent: 2.5,
        assets: {
            USD: { symbol: 'USD', valueUSD: 10000, currentPercent: 20, targetPercent: 20, deviationPercent: 0, deviationUSD: 0, price: 1 },
            BTC: { symbol: 'BTC', valueUSD: 25000, currentPercent: 50, targetPercent: 45, deviationPercent: 5, deviationUSD: 2500, price: 50000 },
            ETH: { symbol: 'ETH', valueUSD: 15000, currentPercent: 30, targetPercent: 35, deviationPercent: -5, deviationUSD: -2500, price: 3000 },
        }
    };

    const mockHistory = [
        { timestamp: new Date().toISOString(), actions: ['BUY 0.1 BTC @ $50,000'] }
    ];

    beforeEach(() => {
        vi.useFakeTimers({ shouldAdvanceTime: true });
        global.fetch = vi.fn();
    });

    afterEach(() => {
        vi.useRealTimers();
        vi.restoreAllMocks();
    });

    const setupFetchMocks = () => {
        global.fetch.mockImplementation((url) => {
            if (url === '/api/status') {
                return Promise.resolve({ ok: true, json: () => Promise.resolve(mockStatus) });
            }
            if (url === '/api/history') {
                return Promise.resolve({ ok: true, json: () => Promise.resolve(mockHistory) });
            }
            return Promise.reject(new Error('Unknown URL'));
        });
    };

    it('displays loading state initially', async () => {
        global.fetch.mockImplementation(() => new Promise(() => {}));
        await act(async () => { render(<Dashboard />); });
        expect(screen.getByText('Loading...')).toBeInTheDocument();
    });

    it('renders the dashboard title', async () => {
        setupFetchMocks();
        await act(async () => { render(<Dashboard />); });
        await waitFor(() => expect(screen.getByText('Kraken Rebalancer')).toBeInTheDocument());
    });

    it('renders status cards with correct values', async () => {
        setupFetchMocks();
        await act(async () => { render(<Dashboard />); });
        await waitFor(() => {
            expect(screen.getByTestId('status-card-total-portfolio')).toBeInTheDocument();
            expect(screen.getByTestId('status-card-cash-(usd)')).toBeInTheDocument();
            expect(screen.getByTestId('status-card-crypto-assets')).toBeInTheDocument();
        });
    });

    it('formats currency correctly for total portfolio', async () => {
        setupFetchMocks();
        await act(async () => { render(<Dashboard />); });
        await waitFor(() => {
            const card = screen.getByTestId('status-card-total-portfolio');
            expect(card).toHaveTextContent('$50,000.00');
        });
    });

    it('calculates crypto value as total minus USD', async () => {
        setupFetchMocks();
        await act(async () => { render(<Dashboard />); });
        await waitFor(() => {
            const card = screen.getByTestId('status-card-crypto-assets');
            expect(card).toHaveTextContent('$40,000.00');
        });
    });

    it('shows LIVE badge when data is fresh', async () => {
        setupFetchMocks();
        await act(async () => { render(<Dashboard />); });
        await waitFor(() => expect(screen.getByText('LIVE')).toBeInTheDocument());
    });

    it('renders the AllocationChart', async () => {
        setupFetchMocks();
        await act(async () => { render(<Dashboard />); });
        await waitFor(() => expect(screen.getByTestId('allocation-chart')).toHaveTextContent('Chart Loaded'));
    });

    it('renders the TradeHistory', async () => {
        setupFetchMocks();
        await act(async () => { render(<Dashboard />); });
        await waitFor(() => expect(screen.getByTestId('trade-history')).toHaveTextContent('1 trades'));
    });

    it('renders the Asset Performance table', async () => {
        setupFetchMocks();
        await act(async () => { render(<Dashboard />); });
        await waitFor(() => {
            expect(screen.getByText('Asset Performance')).toBeInTheDocument();
            expect(screen.getByText('Asset')).toBeInTheDocument();
            expect(screen.getByText('Price')).toBeInTheDocument();
        });
    });

    it('renders asset table excluding USD', async () => {
        setupFetchMocks();
        await act(async () => { render(<Dashboard />); });
        await waitFor(() => {
            // BTC and ETH should appear in table, USD should not
            expect(screen.getByText('BTC')).toBeInTheDocument();
            expect(screen.getByText('ETH')).toBeInTheDocument();
        });
    });

    it('switches to Settings view when settings button clicked', async () => {
        setupFetchMocks();
        const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime });
        await act(async () => { render(<Dashboard />); });
        await waitFor(() => expect(screen.getByText('Kraken Rebalancer')).toBeInTheDocument());
        await user.click(screen.getByText(/Settings/));
        expect(screen.getByTestId('settings-view')).toBeInTheDocument();
    });

    it('returns to Dashboard from Settings when back is clicked', async () => {
        setupFetchMocks();
        const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime });
        await act(async () => { render(<Dashboard />); });
        await waitFor(() => expect(screen.getByText('Kraken Rebalancer')).toBeInTheDocument());
        await user.click(screen.getByText(/Settings/));
        expect(screen.getByTestId('settings-view')).toBeInTheDocument();

        await user.click(screen.getByText('Back'));
        await waitFor(() => expect(screen.getByText('Kraken Rebalancer')).toBeInTheDocument());
    });

    it('handles fetch errors gracefully without crashing', async () => {
        global.fetch.mockRejectedValue(new Error('Network error'));
        await act(async () => { render(<Dashboard />); });
        // Should not crash; just stop loading
        await waitFor(() => expect(screen.queryByText('Loading...')).not.toBeInTheDocument());
    });

    it('applies sort indicator on column click', async () => {
        setupFetchMocks();
        const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime });
        await act(async () => { render(<Dashboard />); });
        await waitFor(() => expect(screen.getByText('Asset Performance')).toBeInTheDocument());

        // Click on Asset column header (th) to sort by symbol
        const assetHeader = screen.getAllByRole('columnheader')[0]; // First th = "Asset"
        await user.click(assetHeader);
        // Should show ascending indicator
        expect(assetHeader.textContent).toContain('▲');
    });

    it('toggles sort direction on repeated column click', async () => {
        setupFetchMocks();
        const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime });
        await act(async () => { render(<Dashboard />); });
        await waitFor(() => expect(screen.getByText('Asset Performance')).toBeInTheDocument());

        const assetHeader = screen.getAllByRole('columnheader')[0];
        // Click Asset once (asc), then again (desc)
        await user.click(assetHeader);
        expect(assetHeader.textContent).toContain('▲');

        await user.click(assetHeader);
        expect(assetHeader.textContent).toContain('▼');
    });

    it('sorts assets ascending by symbol', async () => {
        setupFetchMocks();
        const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime });
        await act(async () => { render(<Dashboard />); });
        await waitFor(() => expect(screen.getByText('BTC')).toBeInTheDocument());

        await user.click(screen.getByText('Asset'));

        const rows = screen.getAllByRole('row');
        // First data row (index 1) should be BTC, second ETH (alphabetical)
        const dataCells = rows.slice(1).map(r => r.querySelector('td')?.textContent);
        expect(dataCells[0]).toBe('BTC');
        expect(dataCells[1]).toBe('ETH');
    });

    it('displays deviation with correct CSS class for overweight', async () => {
        setupFetchMocks();
        await act(async () => { render(<Dashboard />); });
        await waitFor(() => {
            // BTC has +5% deviation (overweight = text-danger)
            const btcRow = screen.getByText('BTC').closest('tr');
            const devCell = btcRow.querySelector('.text-danger');
            expect(devCell).not.toBeNull();
            expect(devCell.textContent).toContain('5.00%');
        });
    });

    it('displays deviation with correct CSS class for underweight', async () => {
        setupFetchMocks();
        await act(async () => { render(<Dashboard />); });
        await waitFor(() => {
            // ETH has -5% deviation (underweight = text-success)
            const ethRow = screen.getByText('ETH').closest('tr');
            const devCell = ethRow.querySelector('.text-success');
            expect(devCell).not.toBeNull();
            expect(devCell.textContent).toContain('-5.00%');
        });
    });

    it('shows Data Age section', async () => {
        setupFetchMocks();
        await act(async () => { render(<Dashboard />); });
        await waitFor(() => expect(screen.getByText('Data Age')).toBeInTheDocument());
    });

    it('displays data age in seconds', async () => {
        setupFetchMocks();
        await act(async () => { render(<Dashboard />); });
        await waitFor(() => expect(screen.getByText(/\d+s ago/)).toBeInTheDocument());
    });
});
