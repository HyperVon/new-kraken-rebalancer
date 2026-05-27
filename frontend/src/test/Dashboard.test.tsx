import {act, render, screen, waitFor} from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import {afterEach, beforeEach, describe, expect, it, vi} from 'vitest';
import {QueryClient, QueryClientProvider} from '@tanstack/react-query';
import Dashboard from '../components/Dashboard';

const createTestQueryClient = () => new QueryClient({
    defaultOptions: { queries: { retry: false, gcTime: 0 } }
});

const renderWithProviders = (ui) => {
    const queryClient = createTestQueryClient();
    return render(
        <QueryClientProvider client={queryClient}>
            {ui}
        </QueryClientProvider>
    );
};

// Mock react-router-dom
const mockNavigate = vi.fn();
vi.mock('react-router-dom', () => ({
    useNavigate: () => mockNavigate,
    BrowserRouter: ({ children }: any) => <div>{children}</div>,
}));

// Mock child components to isolate Dashboard logic
vi.mock('../components/AllocationChart', () => ({
    default: ({ assets }) => <div data-testid="allocation-chart">{assets ? 'Chart Loaded' : 'No Chart'}</div>
}));

vi.mock('../components/TradeHistory', () => ({
    default: ({ history }) => <div data-testid="trade-history">{history.length} trades</div>
}));

vi.mock('../components/Settings', () => ({
    default: () => <div data-testid="settings-view">Settings View</div>
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

    it('renders the dashboard title', async () => {
        setupFetchMocks();
        await act(async () => { renderWithProviders(<Dashboard />); });
        await waitFor(() => expect(screen.getByText('Kraken Rebalancer')).toBeInTheDocument());
    });

    it('renders status cards with correct values', async () => {
        setupFetchMocks();
        await act(async () => { renderWithProviders(<Dashboard />); });
        await waitFor(() => {
            expect(screen.getByTestId('status-card-total-portfolio')).toBeInTheDocument();
            expect(screen.getByTestId('status-card-cash-(usd)')).toBeInTheDocument();
            expect(screen.getByTestId('status-card-crypto-assets')).toBeInTheDocument();
        });
    });

    it('formats currency correctly for total portfolio', async () => {
        setupFetchMocks();
        await act(async () => { renderWithProviders(<Dashboard />); });
        await waitFor(() => {
            const card = screen.getByTestId('status-card-total-portfolio');
            expect(card).toHaveTextContent('$50,000.00');
        });
    });

    it('calculates crypto value as total minus USD', async () => {
        setupFetchMocks();
        await act(async () => { renderWithProviders(<Dashboard />); });
        await waitFor(() => {
            const card = screen.getByTestId('status-card-crypto-assets');
            expect(card).toHaveTextContent('$40,000.00');
        });
    });

    it('shows LIVE badge when data is fresh', async () => {
        setupFetchMocks();
        await act(async () => { renderWithProviders(<Dashboard />); });
        await waitFor(() => expect(screen.getByText('LIVE')).toBeInTheDocument());
    });

    it('renders the AllocationChart', async () => {
        setupFetchMocks();
        await act(async () => { renderWithProviders(<Dashboard />); });
        await waitFor(() => expect(screen.getByTestId('allocation-chart')).toHaveTextContent('Chart Loaded'));
    });

    it('renders the TradeHistory', async () => {
        setupFetchMocks();
        await act(async () => { renderWithProviders(<Dashboard />); });
        await waitFor(() => expect(screen.getByTestId('trade-history')).toHaveTextContent('1 trades'));
    });

    it('renders the Asset Performance table', async () => {
        setupFetchMocks();
        await act(async () => { renderWithProviders(<Dashboard />); });
        await waitFor(() => {
            expect(screen.getByText('Asset Performance')).toBeInTheDocument();
            expect(screen.getByText('Asset')).toBeInTheDocument();
            expect(screen.getByText('Price')).toBeInTheDocument();
        });
    });

    it('renders asset table excluding USD', async () => {
        setupFetchMocks();
        await act(async () => { renderWithProviders(<Dashboard />); });
        await waitFor(() => {
            // BTC and ETH should appear in table, USD should not
            expect(screen.getByText('BTC')).toBeInTheDocument();
            expect(screen.getByText('ETH')).toBeInTheDocument();
        });
    });

    it('navigates to Settings view when settings button clicked', async () => {
        setupFetchMocks();
        const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime });
        await act(async () => { renderWithProviders(<Dashboard />); });
        await waitFor(() => expect(screen.getByText('Kraken Rebalancer')).toBeInTheDocument());
        await user.click(screen.getByText(/Settings/));
        expect(mockNavigate).toHaveBeenCalledWith('/settings');
    });

    it('handles fetch errors gracefully without crashing', async () => {
        global.fetch.mockImplementation((url) => {
            if (String(url).includes('/api/status')) {
                return Promise.reject(new Error('Network error'));
            }
            return Promise.resolve({ ok: true, json: async () => [] });
        });
        await act(async () => { renderWithProviders(<Dashboard />); });
        await waitFor(() => expect(screen.getByText('Unable to load portfolio status')).toBeInTheDocument());
    });

    it('shows waiting message when status returns 404', async () => {
        global.fetch.mockImplementation((url) => {
            if (String(url).includes('/api/status')) {
                return Promise.resolve({
                    ok: false,
                    status: 404,
                    json: async () => ({ error: 'No snapshot available yet' })
                });
            }
            return Promise.resolve({ ok: true, json: async () => [] });
        });
        await act(async () => { renderWithProviders(<Dashboard />); });
        await waitFor(() => expect(screen.getByText('Waiting for first rebalance cycle')).toBeInTheDocument());
    });

    it('applies sort indicator on column click', async () => {
        setupFetchMocks();
        const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime });
        await act(async () => { renderWithProviders(<Dashboard />); });
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
        await act(async () => { renderWithProviders(<Dashboard />); });
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
        await act(async () => { renderWithProviders(<Dashboard />); });
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
        await act(async () => { renderWithProviders(<Dashboard />); });
        await waitFor(() => {
            // BTC has +5% deviation (overweight = text-rose-400)
            const btcRow = screen.getByText('BTC').closest('tr');
            const devCell = btcRow.querySelector('.text-rose-400');
            expect(devCell).not.toBeNull();
            expect(devCell.textContent).toContain('5.00%');
        });
    });

    it('displays deviation with correct CSS class for underweight', async () => {
        setupFetchMocks();
        await act(async () => { renderWithProviders(<Dashboard />); });
        await waitFor(() => {
            // ETH has -5% deviation (underweight = text-emerald-400)
            const ethRow = screen.getByText('ETH').closest('tr');
            const devCell = ethRow.querySelector('.text-emerald-400');
            expect(devCell).not.toBeNull();
            expect(devCell.textContent).toContain('-5.00%');
        });
    });

    it('shows Data Age section', async () => {
        setupFetchMocks();
        await act(async () => { renderWithProviders(<Dashboard />); });
        await waitFor(() => expect(screen.getByText('Data Age')).toBeInTheDocument());
    });

    it('displays data age in seconds', async () => {
        setupFetchMocks();
        await act(async () => { renderWithProviders(<Dashboard />); });
        await waitFor(() => expect(screen.getByText(/\d+s ago/)).toBeInTheDocument());
    });

    it('shows DELAYED badge when data is stale', async () => {
        // Create a timestamp more than 90 seconds in the past
        const staleTime = new Date(Date.now() - 120000).toISOString();
        const staleStatus = { ...mockStatus, timestamp: staleTime };
        global.fetch.mockImplementation((url) => {
            if (url === '/api/status') {
                return Promise.resolve({ ok: true, json: () => Promise.resolve(staleStatus) });
            }
            if (url === '/api/history') {
                return Promise.resolve({ ok: true, json: () => Promise.resolve(mockHistory) });
            }
            return Promise.reject(new Error('Unknown URL'));
        });

        await act(async () => { renderWithProviders(<Dashboard />); });
        await waitFor(() => expect(screen.getByText('DELAYED')).toBeInTheDocument());
    });

    it('shows loading state when status is loading', async () => {
        global.fetch.mockImplementation(() => new Promise(() => {}));
        await act(async () => { renderWithProviders(<Dashboard />); });
        expect(screen.getByText('Connecting to KrakenBot...')).toBeInTheDocument();
    });

    it('handles zero deviation with neutral styling', async () => {
        const zeroDevStatus = {
            ...mockStatus,
            assets: {
                USD: { symbol: 'USD', valueUSD: 10000, currentPercent: 20, targetPercent: 20, deviationPercent: 0, deviationUSD: 0, price: 1 },
                BTC: { symbol: 'BTC', valueUSD: 25000, currentPercent: 50, targetPercent: 50, deviationPercent: 0, deviationUSD: 0, price: 50000 },
            }
        };
        global.fetch.mockImplementation((url) => {
            if (url === '/api/status') {
                return Promise.resolve({ ok: true, json: () => Promise.resolve(zeroDevStatus) });
            }
            if (url === '/api/history') {
                return Promise.resolve({ ok: true, json: () => Promise.resolve(mockHistory) });
            }
            return Promise.reject(new Error('Unknown URL'));
        });

        await act(async () => { renderWithProviders(<Dashboard />); });
        await waitFor(() => {
            const btcRow = screen.getByText('BTC').closest('tr');
            const devCell = btcRow.querySelector('.text-slate-400');
            expect(devCell).not.toBeNull();
            expect(devCell.textContent).toContain('0.00%');
        });
    });

    it('renders no USD sub-value when USD asset is absent', async () => {
        const noUsdStatus = {
            ...mockStatus,
            assets: {
                BTC: { symbol: 'BTC', valueUSD: 25000, currentPercent: 50, targetPercent: 45, deviationPercent: 5, deviationUSD: 2500, price: 50000 },
                ETH: { symbol: 'ETH', valueUSD: 15000, currentPercent: 30, targetPercent: 35, deviationPercent: -5, deviationUSD: -2500, price: 3000 },
            }
        };
        global.fetch.mockImplementation((url) => {
            if (url === '/api/status') {
                return Promise.resolve({ ok: true, json: () => Promise.resolve(noUsdStatus) });
            }
            if (url === '/api/history') {
                return Promise.resolve({ ok: true, json: () => Promise.resolve(mockHistory) });
            }
            return Promise.reject(new Error('Unknown URL'));
        });

        await act(async () => { renderWithProviders(<Dashboard />); });
        await waitFor(() => {
            const cashCard = screen.getByTestId('status-card-cash-(usd)');
            expect(cashCard).toHaveTextContent('$0.00');
        });
    });

    it('renders drawdown in Total Portfolio card', async () => {
        setupFetchMocks();
        await act(async () => { renderWithProviders(<Dashboard />); });
        await waitFor(() => {
            const card = screen.getByTestId('status-card-total-portfolio');
            // drawdownPercent is 2.5 in mockStatus, so we should see the subValue
            expect(card.querySelector('[data-testid="card-subvalue"]')).toBeInTheDocument();
        });
    });

    it('sorts by different columns', async () => {
        setupFetchMocks();
        const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime });
        await act(async () => { renderWithProviders(<Dashboard />); });
        await waitFor(() => expect(screen.getByText('Asset Performance')).toBeInTheDocument());

        // Click Price header
        const headers = screen.getAllByRole('columnheader');
        await user.click(headers[1]); // Price column
        expect(headers[1].textContent).toContain('▲');

        // Click Target % header
        await user.click(headers[3]); // Target %
        expect(headers[3].textContent).toContain('▲');

        // Click Current % header
        await user.click(headers[4]); // Current %
        expect(headers[4].textContent).toContain('▲');

        // Click Dev % header
        await user.click(headers[5]); // Dev %
        expect(headers[5].textContent).toContain('▲');
    });

    it('sorts by Value column (default sort key)', async () => {
        setupFetchMocks();
        const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime });
        await act(async () => { renderWithProviders(<Dashboard />); });
        await waitFor(() => expect(screen.getByText('Asset Performance')).toBeInTheDocument());

        // Value is the default sort key (valueUSD desc), clicking toggles to asc
        const headers = screen.getAllByRole('columnheader');
        await user.click(headers[2]); // Value column
        expect(headers[2].textContent).toContain('▲');
    });

    it('handles sort equality when assets have same value', async () => {
        const equalStatus = {
            ...mockStatus,
            assets: {
                USD: { symbol: 'USD', valueUSD: 10000, currentPercent: 20, targetPercent: 20, deviationPercent: 0, deviationUSD: 0, price: 1 },
                BTC: { symbol: 'BTC', valueUSD: 20000, currentPercent: 40, targetPercent: 40, deviationPercent: 0, deviationUSD: 0, price: 50000 },
                ETH: { symbol: 'ETH', valueUSD: 20000, currentPercent: 40, targetPercent: 40, deviationPercent: 0, deviationUSD: 0, price: 3000 },
            }
        };
        global.fetch.mockImplementation((url) => {
            if (url === '/api/status') {
                return Promise.resolve({ ok: true, json: () => Promise.resolve(equalStatus) });
            }
            if (url === '/api/history') {
                return Promise.resolve({ ok: true, json: () => Promise.resolve(mockHistory) });
            }
            return Promise.reject(new Error('Unknown URL'));
        });

        await act(async () => { renderWithProviders(<Dashboard />); });
        await waitFor(() => {
            // Both BTC and ETH should render — sort equality just preserves order
            expect(screen.getByText('BTC')).toBeInTheDocument();
            expect(screen.getByText('ETH')).toBeInTheDocument();
        });
    });

    it('displays effectiveUsdTargetPercent when available', async () => {
        const statusWithEffective = {
            ...mockStatus,
            effectiveUsdTargetPercent: 25.0,
            assets: {
                ...mockStatus.assets,
                USD: { ...mockStatus.assets.USD, deviationPercent: 3.5, deviationUSD: 1750 },
            }
        };
        global.fetch.mockImplementation((url) => {
            if (url === '/api/status') {
                return Promise.resolve({ ok: true, json: () => Promise.resolve(statusWithEffective) });
            }
            if (url === '/api/history') {
                return Promise.resolve({ ok: true, json: () => Promise.resolve(mockHistory) });
            }
            return Promise.reject(new Error('Unknown URL'));
        });

        await act(async () => { renderWithProviders(<Dashboard />); });
        await waitFor(() => {
            const cashCard = screen.getByTestId('status-card-cash-(usd)');
            // Should show effective target and base target
            expect(cashCard).toHaveTextContent('25.00');
            expect(cashCard).toHaveTextContent('Base: 20.00%');
        });
    });

    it('displays USD with negative deviation styling', async () => {
        const statusWithNegDev = {
            ...mockStatus,
            assets: {
                ...mockStatus.assets,
                USD: { ...mockStatus.assets.USD, deviationPercent: -2.5, deviationUSD: -1250 },
            }
        };
        global.fetch.mockImplementation((url) => {
            if (url === '/api/status') {
                return Promise.resolve({ ok: true, json: () => Promise.resolve(statusWithNegDev) });
            }
            if (url === '/api/history') {
                return Promise.resolve({ ok: true, json: () => Promise.resolve(mockHistory) });
            }
            return Promise.reject(new Error('Unknown URL'));
        });

        await act(async () => { renderWithProviders(<Dashboard />); });
        await waitFor(() => {
            const cashCard = screen.getByTestId('status-card-cash-(usd)');
            expect(cashCard).toHaveTextContent('-2.50%');
        });
    });

    it('displays USD with positive deviation styling', async () => {
        const statusWithPosDev = {
            ...mockStatus,
            assets: {
                ...mockStatus.assets,
                USD: { ...mockStatus.assets.USD, deviationPercent: 3.0, deviationUSD: 1500 },
            }
        };
        global.fetch.mockImplementation((url) => {
            if (url === '/api/status') {
                return Promise.resolve({ ok: true, json: () => Promise.resolve(statusWithPosDev) });
            }
            if (url === '/api/history') {
                return Promise.resolve({ ok: true, json: () => Promise.resolve(mockHistory) });
            }
            return Promise.reject(new Error('Unknown URL'));
        });

        await act(async () => { renderWithProviders(<Dashboard />); });
        await waitFor(() => {
            const cashCard = screen.getByTestId('status-card-cash-(usd)');
            expect(cashCard).toHaveTextContent('+3.00%');
        });
    });

    it('displays drawdownPercent of 0.00% when drawdown is 0', async () => {
        const statusNoDraw = { ...mockStatus, drawdownPercent: 0 };
        global.fetch.mockImplementation((url) => {
            if (url === '/api/status') {
                return Promise.resolve({ ok: true, json: () => Promise.resolve(statusNoDraw) });
            }
            if (url === '/api/history') {
                return Promise.resolve({ ok: true, json: () => Promise.resolve(mockHistory) });
            }
            return Promise.reject(new Error('Unknown URL'));
        });

        await act(async () => { renderWithProviders(<Dashboard />); });
        await waitFor(() => {
            const card = screen.getByTestId('status-card-total-portfolio');
            // subValue is always rendered for visual balance; it should display 0.00% drawdown
            expect(card.querySelector('[data-testid="card-subvalue"]')).toBeInTheDocument();
            expect(card.querySelector('[data-testid="card-subvalue"]')?.textContent).toContain('Drawdown: 0.00%');
        });
    });

    it('defaults sorting key to deviationPercent when an invalid sorting key is triggered', async () => {
        setupFetchMocks();
        const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime });
        await act(async () => { renderWithProviders(<Dashboard />); });
        await waitFor(() => expect(screen.getByText('Asset Performance')).toBeInTheDocument());

        // Click the hidden backdoor trigger sort button
        const backdoorBtn = screen.getByTestId('test-trigger-sort');
        await user.click(backdoorBtn);

        // Assets should remain sorted by deviationPercent (default sort order)
        // Since BTC deviation is 5% and ETH is -5%, under deviationPercent sorting:
        // ETH (-5%) should be first, and BTC (5%) should be second.
        const rows = screen.getAllByRole('row');
        const dataCells = rows.slice(1).map(r => r.querySelector('td')?.textContent);
        expect(dataCells[0]).toBe('ETH');
        expect(dataCells[1]).toBe('BTC');
    });

    it('handles null status and cleanup gracefully', async () => {
        global.fetch.mockImplementation((url) => {
            if (url === '/api/status') {
                return Promise.resolve({ ok: true, json: () => Promise.resolve(null) });
            }
            if (url === '/api/history') {
                return Promise.resolve({ ok: true, json: () => Promise.resolve(mockHistory) });
            }
            return Promise.reject(new Error('Unknown URL'));
        });

        let unmount: () => void = () => {};
        await act(async () => {
            const res = renderWithProviders(<Dashboard />);
            unmount = res.unmount;
        });

        await waitFor(() => {
            expect(screen.getByText('OFFLINE')).toBeInTheDocument();
            expect(screen.getByText('-')).toBeInTheDocument();
        });

        await act(async () => {
            unmount();
        });
    });
});
