import { render, screen } from '@testing-library/react';
import { describe, it, expect, vi } from 'vitest';
import AllocationChart from '../components/AllocationChart';

// Mock chart.js and react-chartjs-2 to avoid canvas rendering issues in jsdom
vi.mock('react-chartjs-2', () => ({
    Bar: ({ data }) => (
        <div data-testid="mock-bar-chart">
            {data.labels.map((label, i) => (
                <span key={label} data-testid={`chart-label-${label}`}>
                    {label}: ${data.datasets[0].data[i]}
                </span>
            ))}
        </div>
    )
}));

vi.mock('chart.js', () => ({
    Chart: { register: vi.fn() },
    CategoryScale: 'CategoryScale',
    LinearScale: 'LinearScale',
    BarElement: 'BarElement',
    Tooltip: 'Tooltip',
    Legend: 'Legend',
}));

describe('AllocationChart', () => {
    it('renders "No Asset Data Available" when assets is null', () => {
        render(<AllocationChart assets={null} />);

        expect(screen.getByText('No asset data available.')).toBeInTheDocument();
    });

    it('renders "No Asset Data Available" when assets is an empty object', () => {
        render(<AllocationChart assets={{}} />);

        expect(screen.getByText('No asset data available.')).toBeInTheDocument();
    });

    it('renders the chart with correct title', () => {
        const assets = {
            BTC: { symbol: 'BTC', valueUSD: 5000 },
            ETH: { symbol: 'ETH', valueUSD: 3000 },
        };

        render(<AllocationChart assets={assets} />);

        expect(screen.getByText('Portfolio Allocation (Top Assets)')).toBeInTheDocument();
    });

    it('sorts assets by valueUSD descending for chart display', () => {
        const assets = {
            SOL: { symbol: 'SOL', valueUSD: 1000 },
            BTC: { symbol: 'BTC', valueUSD: 5000 },
            ETH: { symbol: 'ETH', valueUSD: 3000 },
        };

        render(<AllocationChart assets={assets} />);

        const chart = screen.getByTestId('mock-bar-chart');
        const labels = chart.querySelectorAll('[data-testid^="chart-label-"]');

        // BTC should be first (highest value), then ETH, then SOL
        expect(labels[0]).toHaveAttribute('data-testid', 'chart-label-BTC');
        expect(labels[1]).toHaveAttribute('data-testid', 'chart-label-ETH');
        expect(labels[2]).toHaveAttribute('data-testid', 'chart-label-SOL');
    });

    it('limits display to top 15 assets', () => {
        const assets = {};
        for (let i = 1; i <= 20; i++) {
            assets[`COIN${i}`] = { symbol: `COIN${i}`, valueUSD: i * 100 };
        }

        render(<AllocationChart assets={assets} />);

        const chart = screen.getByTestId('mock-bar-chart');
        const labels = chart.querySelectorAll('[data-testid^="chart-label-"]');

        expect(labels).toHaveLength(15);
    });

    it('passes correct USD values to chart dataset', () => {
        const assets = {
            BTC: { symbol: 'BTC', valueUSD: 50000 },
            ETH: { symbol: 'ETH', valueUSD: 10000 },
        };

        render(<AllocationChart assets={assets} />);

        expect(screen.getByTestId('chart-label-BTC')).toHaveTextContent('BTC: $50000');
        expect(screen.getByTestId('chart-label-ETH')).toHaveTextContent('ETH: $10000');
    });

    it('renders chart when only a single asset is present', () => {
        const assets = {
            BTC: { symbol: 'BTC', valueUSD: 50000 },
        };

        render(<AllocationChart assets={assets} />);

        expect(screen.getByTestId('mock-bar-chart')).toBeInTheDocument();
        expect(screen.getByTestId('chart-label-BTC')).toBeInTheDocument();
    });

    it('renders chart when assets is an array', () => {
        const assets = [
            { symbol: 'BTC', valueUSD: 50000 },
            { symbol: 'ETH', valueUSD: 10000 },
        ];

        render(<AllocationChart assets={assets} />);

        expect(screen.getByTestId('mock-bar-chart')).toBeInTheDocument();
        expect(screen.getByTestId('chart-label-BTC')).toBeInTheDocument();
        expect(screen.getByTestId('chart-label-ETH')).toBeInTheDocument();
    });
});

describe('AllocationChart helpers', () => {
    it('formatTooltipLabel formats currency correctly', async () => {
        const { formatTooltipLabel } = await import('../components/AllocationChart');
        expect(formatTooltipLabel({ raw: 50000 })).toBe('$50,000.00');
        expect(formatTooltipLabel({ raw: 0 })).toBe('$0.00');
        expect(formatTooltipLabel({ raw: 1234.56 })).toBe('$1,234.56');
    });

    it('formatTickLabel prepends dollar sign', async () => {
        const { formatTickLabel } = await import('../components/AllocationChart');
        expect(formatTickLabel(1000)).toBe('$1000');
        expect(formatTickLabel(0)).toBe('$0');
        expect(formatTickLabel('500')).toBe('$500');
    });
});
