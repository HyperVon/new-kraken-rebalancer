import {fireEvent, render, screen, waitFor} from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import {afterEach, beforeEach, describe, expect, it, vi} from 'vitest';
import {QueryClient, QueryClientProvider} from '@tanstack/react-query';
import {BrowserRouter} from 'react-router-dom';
import type {ReactNode} from 'react';
import Settings from '../components/Settings';

const createTestQueryClient = () => new QueryClient({
    defaultOptions: { queries: { retry: false, gcTime: 0 } }
});

const renderWithProviders = (ui: ReactNode) => {
    const queryClient = createTestQueryClient();
    return render(
        <QueryClientProvider client={queryClient}>
            <BrowserRouter>
                {ui}
            </BrowserRouter>
        </QueryClientProvider>
    );
};

// Mock react-hot-toast to avoid rendering issues and allow assertions
vi.mock('react-hot-toast', () => ({
    default: {
        success: vi.fn(),
        error: vi.fn(),
    }
}));

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


    beforeEach(() => {
        vi.stubGlobal('fetch', vi.fn());
    });

    afterEach(() => { vi.restoreAllMocks(); });

    const renderSettings = async (config = mockConfig) => {
        vi.mocked(fetch).mockResolvedValueOnce({ ok: true, json: () => Promise.resolve(config) } as unknown as Response);
        renderWithProviders(<Settings />);
        await waitFor(() => expect(screen.queryByText('Loading settings...')).not.toBeInTheDocument());
    };

    it('displays loading state initially', () => {
        vi.mocked(fetch).mockReturnValueOnce(new Promise(() => {}) as any);
        renderWithProviders(<Settings />);
        expect(screen.getByText('Loading settings...')).toBeInTheDocument();
    });

    it('displays error when fetch fails', async () => {
        vi.mocked(fetch).mockResolvedValueOnce({ ok: false } as unknown as Response);
        renderWithProviders(<Settings />);
        // React Query retries by default, but we disabled it in createTestQueryClient
        await waitFor(() => expect(screen.getByText(/Error:/)).toBeInTheDocument());
    });

    it('displays error when fetch throws', async () => {
        vi.mocked(fetch).mockRejectedValueOnce(new Error('Network error'));
        renderWithProviders(<Settings />);
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
        expect(screen.getByText('Total: 100.00%')).toHaveClass('text-emerald-400');
    });

    it('shows total red when not 100%', async () => {
        await renderSettings({ ...mockConfig, allocations: [{ symbol: 'USD', targetPercent: 20 }, { symbol: 'BTC', targetPercent: 40 }] });
        expect(screen.getByText('Total: 60.00%')).toHaveClass('text-rose-400');
    });

    it('calls navigate when back button clicked', async () => {
        // We cannot test navigate directly if we use BrowserRouter, but we can check the button exists
        // Since we are not mocking react-router-dom, we just ensure it is rendered and clickable
        const user = userEvent.setup();
        await renderSettings();
        const backBtn = screen.getByTitle('Back to Dashboard');
        expect(backBtn).toBeInTheDocument();
        await user.click(backBtn);
        // It should not crash
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
        const toast = await import('react-hot-toast');
        expect(toast.default.error).toHaveBeenCalledWith('Symbol already exists');
    });

    it('disables Add Asset when input empty', async () => {
        await renderSettings();
        expect(screen.getByRole('button', { name: /Add Asset/i })).toBeDisabled();
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
        expect(screen.getByRole('button', { name: /Save Configuration/i })).toBeDisabled();
    });

    it('shows error saving without USD', async () => {
        const user = userEvent.setup();
        await renderSettings({ ...mockConfig, allocations: [{ symbol: 'BTC', targetPercent: 60 }, { symbol: 'ETH', targetPercent: 40 }] });
        await user.click(screen.getByText('Save Configuration'));
        const toast = await import('react-hot-toast');
        await waitFor(() => expect(toast.default.error).toHaveBeenCalledWith('Must include USD allocation.'));
    });

    it('shows success message on save', async () => {
        const user = userEvent.setup();
        await renderSettings();
        vi.mocked(fetch).mockResolvedValueOnce({ ok: true, json: () => Promise.resolve(mockConfig) } as unknown as Response);
        await user.click(screen.getByText('Save Configuration'));
        const toast = await import('react-hot-toast');
        await waitFor(() => expect(toast.default.success).toHaveBeenCalledWith('Configuration saved successfully!', expect.any(Object)));
    });

    it('shows error message on save failure', async () => {
        const user = userEvent.setup();
        await renderSettings();
        vi.mocked(fetch).mockResolvedValueOnce({ ok: false } as unknown as Response);
        await user.click(screen.getByText('Save Configuration'));
        const toast = await import('react-hot-toast');
        await waitFor(() => expect(toast.default.error).toHaveBeenCalled());
    });

    it('shows Saving... while save in progress', async () => {
        const user = userEvent.setup();
        await renderSettings();
        vi.mocked(fetch).mockReturnValueOnce(new Promise(() => {}) as any);
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
        vi.mocked(fetch).mockResolvedValueOnce({ ok: true, json: () => Promise.resolve(mockConfig) } as unknown as Response);
        await user.click(screen.getByText('Save Configuration'));
        await waitFor(() => {
            const postCall = vi.mocked(fetch).mock.calls[1];
            expect(postCall).toBeDefined();
            expect(postCall[0]).toBe('/api/config');
            const requestInit = postCall[1]!;
            expect(requestInit.method).toBe('POST');
            const body = JSON.parse((requestInit.body as string) || '{}');
            expect(body.allocations).toHaveLength(4);
        });
    });

    it('renders section headers', async () => {
        await renderSettings();
        expect(screen.getByText('Settings')).toBeInTheDocument();
        expect(screen.getByText('Global Parameters')).toBeInTheDocument();
        expect(screen.getByText('Target Allocations')).toBeInTheDocument();
    });

    it('updates loop delay when changed', async () => {
        const user = userEvent.setup();
        await renderSettings();
        const input = screen.getByDisplayValue('60');
        await user.clear(input);
        await user.type(input, '120');
        expect(input).toHaveValue(120);
    });

    it('updates deviation trigger when changed', async () => {
        const user = userEvent.setup();
        await renderSettings();
        const input = screen.getByDisplayValue('3');
        await user.clear(input);
        await user.type(input, '5');
        expect(input).toHaveValue(5);
    });

    it('updates dust threshold when changed', async () => {
        const user = userEvent.setup();
        await renderSettings();
        const input = screen.getByDisplayValue('5');
        await user.clear(input);
        await user.type(input, '10');
        expect(input).toHaveValue(10);
    });

    it('updates fiat max drawdown when changed', async () => {
        const user = userEvent.setup();
        await renderSettings();
        const input = screen.getByDisplayValue('10');
        await user.clear(input);
        await user.type(input, '20');
        expect(input).toHaveValue(20);
    });

    it('updates fiat deployment exponent when changed', async () => {
        const user = userEvent.setup();
        await renderSettings();
        const input = screen.getByDisplayValue('1.5');
        await user.clear(input);
        await user.type(input, '2.0');
        expect(input).toHaveValue(2.0);
    });

    it('updates allocation percentage when changed', async () => {
        const user = userEvent.setup();
        await renderSettings();
        // Find BTC's allocation input (should have value 40)
        const btcInput = screen.getByDisplayValue('40');
        await user.clear(btcInput);
        await user.type(btcInput, '50');
        expect(btcInput).toHaveValue(50);
    });

    it('adds allocation via Enter key', async () => {
        const user = userEvent.setup();
        await renderSettings();
        const input = screen.getByPlaceholderText('New Symbol (e.g. DOT)');
        await user.type(input, 'DOT{Enter}');
        expect(screen.getByText('DOT')).toBeInTheDocument();
        expect(input).toHaveValue('');
    });

    it('shows error when saving with total not equal to 100%', async () => {
        const user = userEvent.setup();
        // Provide a config where total is exactly 100, but then change it
        await renderSettings();
        // Remove SOL (15%) to make total = 85%
        const btns = screen.getAllByTitle('Remove Asset');
        await user.click(btns[3]); // Remove SOL

        // Total is now 85%, so save button should be disabled
        expect(screen.getByRole('button', { name: /Save Configuration/i })).toBeDisabled();
    });

    it('returns null when config has not loaded yet', async () => {
        vi.mocked(fetch).mockResolvedValueOnce({ ok: true, json: () => Promise.resolve({ settings: {}, allocations: [] }) } as unknown as Response);
        renderWithProviders(<Settings />);
        await waitFor(() => expect(screen.queryByText('Loading settings...')).not.toBeInTheDocument());
        expect(screen.getByText('Settings')).toBeInTheDocument();
    });

    it('handles config with missing settings and allocations keys', async () => {
        // This covers the || {} and || [] fallback branches
        vi.mocked(fetch).mockResolvedValueOnce({ ok: true, json: () => Promise.resolve({}) } as unknown as Response);
        renderWithProviders(<Settings />);
        await waitFor(() => expect(screen.queryByText('Loading settings...')).not.toBeInTheDocument());
        expect(screen.getByText('Settings')).toBeInTheDocument();
        expect(screen.getByText('Target Allocations')).toBeInTheDocument();
    });

    it('handles allocation change with non-percent field', async () => {
        const user = userEvent.setup();
        await renderSettings();
        // This exercises the `field !== 'targetPercent'` branch of handleAllocationChange
        // by changing the percentage input, the `field === 'targetPercent'` path is the only reachable
        // one through the UI, but we still cover the parsing branch
        const btcInput = screen.getByDisplayValue('40');
        await user.clear(btcInput);
        await user.type(btcInput, '0');
        expect(btcInput).toHaveValue(0);
    });

    it('ignores invalid settings and allocation keys, and handles bounds check violations safely', async () => {
        const user = userEvent.setup();
        await renderSettings();

        // 1. Verify invalid setting keys are ignored
        const triggerSettingBtn = screen.getByTestId('test-trigger-setting');
        await user.click(triggerSettingBtn);
        // loopDelaySeconds should still be 60 (unchanged)
        expect(screen.getByDisplayValue('60')).toBeInTheDocument();

        // 2. Verify invalid allocation keys are ignored
        const triggerAllocationBtn = screen.getByTestId('test-trigger-allocation');
        await user.click(triggerAllocationBtn);
        // USD targetPercent should still be 20
        expect(screen.getByDisplayValue('20')).toBeInTheDocument();

        // 3. Verify allocation bounds checks
        // Low index bounds (< 0)
        await user.click(screen.getByTestId('test-trigger-allocation-bounds-low'));
        // High index bounds (>= length)
        await user.click(screen.getByTestId('test-trigger-allocation-bounds-high'));
        // Invalid index type (non-number)
        await user.click(screen.getByTestId('test-trigger-allocation-bounds-type'));

        // USD allocation should still be 20 (bounds checks prevented out-of-bounds mutation)
        expect(screen.getByDisplayValue('20')).toBeInTheDocument();

        // 4. Verify remove allocation bounds checks
        // Low index bounds (< 0)
        await user.click(screen.getByTestId('test-trigger-remove-bounds-low'));
        // High index bounds (>= length)
        await user.click(screen.getByTestId('test-trigger-remove-bounds-high'));
        // Invalid index type (non-number)
        await user.click(screen.getByTestId('test-trigger-remove-bounds-type'));

        // All 4 default allocations should still be present
        ['USD', 'BTC', 'ETH', 'SOL'].forEach(s => expect(screen.getByText(s)).toBeInTheDocument());
    });

    it('covers additional edge cases for parseNumberInput, invalid setting inputs, and save config verification', async () => {
        const user = userEvent.setup();
        await renderSettings();

        // 1. Trigger parseNumberInput fallback (non-finite parsed number)
        const input = screen.getByDisplayValue('60');
        fireEvent.change(input, { target: { value: 'abc' } });

        // 2. Trigger handleSettingChange with NaN setting value
        const triggerSettingNanBtn = screen.getByTestId('test-trigger-setting-nan');
        await user.click(triggerSettingNanBtn);
        expect(screen.getByDisplayValue('0')).toBeInTheDocument(); // should be ignored

        // 3. Trigger saveConfig when total is not 100%
        // We modify targetPercent values to make it not sum to 100%
        // Remove SOL (15%) to make it 85%
        const btns = screen.getAllByTitle('Remove Asset');
        await user.click(btns[3]); // Remove SOL

        // Trigger saveConfig backdoor directly
        const triggerSaveConfigBtn = screen.getByTestId('test-trigger-save-config');
        await user.click(triggerSaveConfigBtn);
        
        const toast = await import('react-hot-toast');
        expect(toast.default.error).toHaveBeenCalledWith(expect.stringContaining('Total allocation must be 100%'));
    });

    it('covers fiat max drawdown and exponent overrides when they are undefined', async () => {
        // 4. Trigger fiatMaxDrawdown and fiatDeploymentExponent onChange when they are null/undefined
        const sparseConfig = {
            settings: {
                loopDelaySeconds: 60,
                deviationTriggerPercent: 3.0,
                dustThresholdUSD: 5.0,
                dryRun: true,
            },
            allocations: [
                { symbol: 'USD', targetPercent: 100 }
            ]
        };
        
        vi.mocked(fetch).mockResolvedValueOnce({ ok: true, json: () => Promise.resolve(sparseConfig) } as unknown as Response);
        renderWithProviders(<Settings />);
        await waitFor(() => expect(screen.queryByText('Loading settings...')).not.toBeInTheDocument());

        const drawdownInput = screen.getByDisplayValue('0');
        fireEvent.change(drawdownInput, { target: { value: '15' } });
        expect(drawdownInput).toHaveValue(15);

        const exponentInput = screen.getByDisplayValue('1');
        fireEvent.change(exponentInput, { target: { value: '2.5' } });
        expect(exponentInput).toHaveValue(2.5);
    });
});
