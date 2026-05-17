import { render, screen } from '@testing-library/react';
import { describe, it, expect, vi } from 'vitest';
import App from '../App';

// Mock Dashboard so App test remains isolated
vi.mock('../components/Dashboard', () => ({
    default: () => <div data-testid="dashboard">Dashboard Component</div>
}));

describe('App', () => {
    it('renders the Dashboard component', () => {
        render(<App />);
        expect(screen.getByTestId('dashboard')).toBeInTheDocument();
    });

    it('renders Dashboard text content', () => {
        render(<App />);
        expect(screen.getByText('Dashboard Component')).toBeInTheDocument();
    });
});
