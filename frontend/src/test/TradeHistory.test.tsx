import {render, screen} from '@testing-library/react';
import {describe, expect, it} from 'vitest';
import TradeHistory from '../components/TradeHistory';

describe('TradeHistory', () => {
    it('renders empty state when history is null', () => {
        render(<TradeHistory history={null as any} />);

        expect(screen.getByText('Recent Activity')).toBeInTheDocument();
        expect(screen.getByText('No trading history available.')).toBeInTheDocument();
    });

    it('renders empty state when history is an empty array', () => {
        render(<TradeHistory history={[]} />);

        expect(screen.getByText('No trading history available.')).toBeInTheDocument();
    });

    it('renders a BUY action with the correct badge', () => {
        const history = [{
            timestamp: '2026-05-16T10:30:00Z',
            actions: ['BUY 0.5 BTC @ $50,000']
        }];

        render(<TradeHistory history={history as any} />);

        expect(screen.getByText('BUY')).toBeInTheDocument();
        expect(screen.getByText('BUY 0.5 BTC @ $50,000')).toBeInTheDocument();
        expect(screen.getByText('BUY')).toHaveClass('badge', 'badge-buy');
    });

    it('renders a SELL action with the correct badge', () => {
        const history = [{
            timestamp: '2026-05-16T10:30:00Z',
            actions: ['SELL 1.0 ETH @ $3,000']
        }];

        render(<TradeHistory history={history as any} />);

        expect(screen.getByText('SELL')).toBeInTheDocument();
        expect(screen.getByText('SELL 1.0 ETH @ $3,000')).toBeInTheDocument();
        expect(screen.getByText('SELL')).toHaveClass('badge', 'badge-sell');
    });

    it('renders an INFO badge for non-buy/sell actions', () => {
        const history = [{
            timestamp: '2026-05-16T10:30:00Z',
            actions: ['Rebalance cycle complete']
        }];

        render(<TradeHistory history={history as any} />);

        expect(screen.getByText('INFO')).toBeInTheDocument();
        expect(screen.getByText('INFO')).toHaveClass('badge');
        expect(screen.getByText('INFO')).not.toHaveClass('badge-buy');
        expect(screen.getByText('INFO')).not.toHaveClass('badge-sell');
    });

    it('renders multiple actions from the same snapshot', () => {
        const history = [{
            timestamp: '2026-05-16T10:30:00Z',
            actions: [
                'BUY 0.5 BTC @ $50,000',
                'SELL 1.0 ETH @ $3,000'
            ]
        }];

        render(<TradeHistory history={history as any} />);

        expect(screen.getByText('BUY')).toBeInTheDocument();
        expect(screen.getByText('SELL')).toBeInTheDocument();
    });

    it('renders "No trades executed" for snapshots with no actions', () => {
        const history = [{
            timestamp: '2026-05-16T10:30:00Z',
            actions: []
        }];

        render(<TradeHistory history={history as any} />);

        expect(screen.getByText('No trades executed (Cycle complete)')).toBeInTheDocument();
    });

    it('renders "No trades executed" for snapshots with null actions', () => {
        const history = [{
            timestamp: '2026-05-16T10:30:00Z',
            actions: null
        }];

        render(<TradeHistory history={history as any} />);

        expect(screen.getByText('No trades executed (Cycle complete)')).toBeInTheDocument();
    });

    it('renders multiple snapshots correctly', () => {
        const history = [
            {
                timestamp: '2026-05-16T10:30:00Z',
                actions: ['BUY 0.5 BTC @ $50,000']
            },
            {
                timestamp: '2026-05-16T11:30:00Z',
                actions: ['SELL 2.0 SOL @ $150']
            }
        ];

        render(<TradeHistory history={history as any} />);

        expect(screen.getByText('BUY')).toBeInTheDocument();
        expect(screen.getByText('SELL')).toBeInTheDocument();
        expect(screen.getByText('BUY 0.5 BTC @ $50,000')).toBeInTheDocument();
        expect(screen.getByText('SELL 2.0 SOL @ $150')).toBeInTheDocument();
    });

    it('handles numeric timestamps (seconds) correctly', () => {
        // Epoch seconds for 2026-05-16T10:30:00Z = 1778937000
        const history = [{
            timestamp: 1778937000,
            actions: ['BUY 1.0 BTC @ $50,000']
        }];

        render(<TradeHistory history={history as any} />);

        // Should render without crashing - the component handles numeric timestamps
        expect(screen.getByText('BUY')).toBeInTheDocument();
    });

    it('renders table headers correctly', () => {
        const history = [{
            timestamp: '2026-05-16T10:30:00Z',
            actions: ['BUY 0.5 BTC']
        }];

        render(<TradeHistory history={history as any} />);

        expect(screen.getByText('Time')).toBeInTheDocument();
        expect(screen.getByText('Action')).toBeInTheDocument();
    });

    it('applies case-insensitive matching for buy/sell detection', () => {
        const history = [{
            timestamp: '2026-05-16T10:30:00Z',
            actions: ['buy 0.5 BTC @ $50,000', 'sell 1.0 ETH @ $3,000']
        }];

        render(<TradeHistory history={history as any} />);

        const badges = screen.getAllByText('BUY');
        expect(badges).toHaveLength(1);
        expect(badges[0]).toHaveClass('badge-buy');

        const sellBadges = screen.getAllByText('SELL');
        expect(sellBadges).toHaveLength(1);
        expect(sellBadges[0]).toHaveClass('badge-sell');
    });
});
