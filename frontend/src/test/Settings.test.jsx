import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import Settings from '../components/Settings';

describe('Settings', () => {
    const mockConfig = {
        settings: {
            loopDelaySeconds: 60,
            deviationTriggerPercent: 3.0,
            dustThresholdUSD: 5.0,
            fiatMaxDrawdown: 10,
            fiatDeploymentExponent: 1.5,
            dryRun: true,
        },
        allocations: [
            { symbol: 'USD', targetPercent: 20 },
            { symbol: 'BTC', targetPercent: 40 },
            { symbol: 'ETH', targetPercent: 25 },
            { symbol: 'SOL', targetPercent: 15 },
        ]
    };

    let mockOnBack;

    beforeEach(() => {
        mockOnBack = vi.fn();
        global.fetch = vi.fn();
    });

    afterEach(() => { vi.restoreAllMocks(); });

    const renderSettings = async (config = mockConfig) => {
        global.fetch.mockResolvedValueOnce({ ok: true, json: () => Promise.resolve(config) });
        render(<Settings onBack={mockOnBack} />);
        await waitFor(() => expect(screen.queryByText('Loading settings...')).not.toBeInTheDocument());
    };

    it('displays loading state initially', () => {
        global.fetch.mockReturnValueOnce(new Promise(() => {}));
        render(<Settings onBack={mockOnBack} />);
        expect(screen.getByText('Loading settings...')).toBeInTheDocument();
    });

    it('displays error when fetch fails', async () => {
        global.fetch.mockResolvedValueOnce({ ok: false });
        render(<Settings onBack={mockOnBack} />);
        await waitFor(() => expect(screen.getByText(/Error: Failed to fetch config/)).toBeInTheDocument());
    });

    it('displays error when fetch throws', async () => {
        global.fetch.mockRejectedValueOnce(new Error('Network error'));
        render(<Settings onBack={mockOnBack} />);
        await waitFor(() => expect(screen.getByText(/Error: Network error/)).toBeInTheDocument());
    });

    it('renders all global settings fields', async () => {
        await renderSettings();
        expect(screen.getByDisplayValue('60')).toBeInTheDocument();
        expect(screen.getByDisplayValue('3')).toBeInTheDocument();
        expect(screen.getByDisplayValue('5')).toBeInTheDocument();
        expect(screen.getByDisplayValue('10')).toBeInTheDocument();
        expect(screen.getByDisplayValue('1.5')).toBeInTheDocument();
    });

    it('renders dry run checkbox as checked', async () => {
        await renderSettings();
        expect(screen.getByRole('checkbox')).toBeChecked();
    });

    it('renders all allocation symbols', async () => {
        await renderSettings();
        ['USD', 'BTC', 'ETH', 'SOL'].forEach(s => expect(screen.getByText(s)).toBeInTheDocument());
    });

    it('shows correct total allocation', async () => {
        await renderSettings();
        expect(screen.getByText('Total: 100.00%')).toBeInTheDocument();
    });

    it('shows total green at 100%', async () => {
        await renderSettings();
        expect(screen.getByText('Total: 100.00%')).toHaveStyle({ color: '#22c55e' });
    });

    it('shows total red when not 100%', async () => {
        await renderSettings({ ...mockConfig, allocations: [{ symbol: 'USD', targetPercent: 20 }, { symbol: 'BTC', targetPercent: 40 }] });
        expect(screen.getByText('Total: 60.00%')).toHaveStyle({ color: '#ef4444' });
    });

    it('calls onBack when back button clicked', async () => {
        const user = userEvent.setup();
        await renderSettings();
        await user.click(screen.getByText(/Back to Dashboard/));
        expect(mockOnBack).toHaveBeenCalledTimes(1);
    });

    it('adds a new allocation', async () => {
        const user = userEvent.setup();
        await renderSettings();
        await user.type(screen.getByPlaceholderText('New Symbol (e.g. DOT)'), 'DOT');
        await user.click(screen.getByText('Add Asset'));
        expect(screen.getByText('DOT')).toBeInTheDocument();
    });

    it('converts symbol to uppercase', async () => {
        const user = userEvent.setup();
        await renderSettings();
        await user.type(screen.getByPlaceholderText('New Symbol (e.g. DOT)'), 'dot');
        await user.click(screen.getByText('Add Asset'));
        expect(screen.getByText('DOT')).toBeInTheDocument();
    });

    it('prevents duplicate allocation', async () => {
        const user = userEvent.setup();
        await renderSettings();
        await user.type(screen.getByPlaceholderText('New Symbol (e.g. DOT)'), 'BTC');
        await user.click(screen.getByText('Add Asset'));
        expect(screen.getByText('Symbol already exists')).toBeInTheDocument();
    });

    it('disables Add Asset when input empty', async () => {
        await renderSettings();
        expect(screen.getByText('Add Asset')).toBeDisabled();
    });

    it('removes an allocation', async () => {
        const user = userEvent.setup();
        await renderSettings();
        const btns = screen.getAllByTitle('Remove Asset');
        expect(btns).toHaveLength(4);
        await user.click(btns[3]); // Remove SOL
        expect(screen.queryByText('SOL')).not.toBeInTheDocument();
    });

    it('clears input after adding', async () => {
        const user = userEvent.setup();
        await renderSettings();
        const input = screen.getByPlaceholderText('New Symbol (e.g. DOT)');
        await user.type(input, 'DOT');
        await user.click(screen.getByText('Add Asset'));
        expect(input).toHaveValue('');
    });

    it('disables save when total != 100%', async () => {
        await renderSettings({ ...mockConfig, allocations: [{ symbol: 'USD', targetPercent: 50 }] });
        expect(screen.getByText('Save Configuration')).toBeDisabled();
    });

    it('shows error saving without USD', async () => {
        const user = userEvent.setup();
        await renderSettings({ ...mockConfig, allocations: [{ symbol: 'BTC', targetPercent: 60 }, { symbol: 'ETH', targetPercent: 40 }] });
        await user.click(screen.getByText('Save Configuration'));
        await waitFor(() => expect(screen.getByText('Must include USD allocation.')).toBeInTheDocument());
    });

    it('shows success message on save', async () => {
        const user = userEvent.setup();
        await renderSettings();
        global.fetch.mockResolvedValueOnce({ ok: true, json: () => Promise.resolve(mockConfig) });
        await user.click(screen.getByText('Save Configuration'));
        await waitFor(() => expect(screen.getByText('Configuration saved successfully!')).toBeInTheDocument());
    });

    it('shows error message on save failure', async () => {
        const user = userEvent.setup();
        await renderSettings();
        global.fetch.mockResolvedValueOnce({ ok: false });
        await user.click(screen.getByText('Save Configuration'));
        await waitFor(() => expect(screen.getByText('Failed to save config')).toBeInTheDocument());
    });

    it('shows Saving... while save in progress', async () => {
        const user = userEvent.setup();
        await renderSettings();
        global.fetch.mockReturnValueOnce(new Promise(() => {}));
        await user.click(screen.getByText('Save Configuration'));
        expect(screen.getByText('Saving...')).toBeInTheDocument();
    });

    it('toggles dry run checkbox', async () => {
        const user = userEvent.setup();
        await renderSettings();
        const cb = screen.getByRole('checkbox');
        expect(cb).toBeChecked();
        await user.click(cb);
        expect(cb).not.toBeChecked();
    });

    it('sends correct payload on save', async () => {
        const user = userEvent.setup();
        await renderSettings();
        global.fetch.mockResolvedValueOnce({ ok: true, json: () => Promise.resolve(mockConfig) });
        await user.click(screen.getByText('Save Configuration'));
        await waitFor(() => {
            const postCall = global.fetch.mock.calls[1];
            expect(postCall[0]).toBe('/api/config');
            expect(postCall[1].method).toBe('POST');
            const body = JSON.parse(postCall[1].body);
            expect(body.allocations).toHaveLength(4);
        });
    });

    it('renders section headers', async () => {
        await renderSettings();
        expect(screen.getByText('Settings')).toBeInTheDocument();
        expect(screen.getByText('Global Parameters')).toBeInTheDocument();
        expect(screen.getByText('Allocations')).toBeInTheDocument();
    });
});
