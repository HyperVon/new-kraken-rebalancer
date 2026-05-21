import { render, screen } from '@testing-library/react';
import { describe, it, expect } from 'vitest';
import StatusCard from '../components/StatusCard';

describe('StatusCard', () => {
    it('renders the title and value', () => {
        render(<StatusCard title="Total Portfolio" value="$10,000.00" />);

        expect(screen.getByText('Total Portfolio')).toBeInTheDocument();
        expect(screen.getByText('$10,000.00')).toBeInTheDocument();
    });

    it('applies the neutral type class by default', () => {
        render(<StatusCard title="Test" value="$100" />);

        const valueElement = screen.getByText('$100');
        expect(valueElement).toHaveClass('from-white');
        expect(valueElement).not.toHaveClass('from-emerald-400');
        expect(valueElement).not.toHaveClass('from-rose-400');
    });

    it('applies the success type class when type is success', () => {
        render(<StatusCard title="Cash" value="$5,000" type="success" />);

        const valueElement = screen.getByText('$5,000');
        expect(valueElement).toHaveClass('from-emerald-400');
    });

    it('applies the danger type class when type is danger', () => {
        render(<StatusCard title="Loss" value="-$500" type="danger" />);

        const valueElement = screen.getByText('-$500');
        expect(valueElement).toHaveClass('from-rose-400');
    });

    it('renders the subValue when provided', () => {
        render(
            <StatusCard
                title="Portfolio"
                value="$10,000"
                subValue={<span>Drawdown: 5.00%</span>}
            />
        );

        expect(screen.getByText('Drawdown: 5.00%')).toBeInTheDocument();
    });

    it('does not render subValue container when subValue is null', () => {
        const { container } = render(
            <StatusCard title="Portfolio" value="$10,000" subValue={null} />
        );

        const subValueContainers = container.querySelectorAll('.subvalue-container');
        expect(subValueContainers).toHaveLength(0);
    });

    it('does not render subValue container when subValue is undefined', () => {
        const { container } = render(
            <StatusCard title="Portfolio" value="$10,000" />
        );

        const subValueContainers = container.querySelectorAll('.subvalue-container');
        expect(subValueContainers).toHaveLength(0);
    });

    it('renders complex JSX subValue correctly', () => {
        const complexSubValue = (
            <div style={{ display: 'flex', gap: '8px' }}>
                <span>10.50%</span>
                <span>Target: 12.00%</span>
                <span>Dev: -1.50%</span>
            </div>
        );

        render(
            <StatusCard title="Cash (USD)" value="$2,000" subValue={complexSubValue} type="success" />
        );

        expect(screen.getByText('10.50%')).toBeInTheDocument();
        expect(screen.getByText('Target: 12.00%')).toBeInTheDocument();
        expect(screen.getByText('Dev: -1.50%')).toBeInTheDocument();
    });
});
