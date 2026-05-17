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
    it('renders "No data" when assets is null', () => {
        render(<AllocationChart assets={null} />);

        expect(screen.getByText('No data')).toBeInTheDocument();
    });

    it('renders "No data" when assets is an empty object', () => {
        render(<AllocationChart assets={{}} />);

        expect(screen.getByText('No data')).toBeInTheDocument();
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
});
