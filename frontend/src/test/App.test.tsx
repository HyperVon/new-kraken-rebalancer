import {render, screen} from '@testing-library/react';
import {describe, expect, it, vi} from 'vitest';
import {MemoryRouter} from 'react-router-dom';
import App from '../App';

// Mock Dashboard so App test remains isolated
vi.mock('../components/Dashboard', () => ({
    default: () => <div data-testid="dashboard">Dashboard Component</div>
}));

describe('App', () => {
    it('renders the Dashboard component', () => {
        render(
            <MemoryRouter>
                <App />
            </MemoryRouter>
        );
        expect(screen.getByTestId('dashboard')).toBeInTheDocument();
    });

    it('renders Dashboard text content', () => {
        render(
            <MemoryRouter>
                <App />
            </MemoryRouter>
        );
        expect(screen.getByText('Dashboard Component')).toBeInTheDocument();
    });
});
