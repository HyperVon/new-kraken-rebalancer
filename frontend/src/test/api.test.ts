import {describe, expect, it, vi, beforeEach, afterEach} from 'vitest';
import {apiService, ApiError} from '../services/api';

describe('apiService', () => {
    const originalFetch = globalThis.fetch;

    beforeEach(() => {
        vi.stubGlobal('fetch', vi.fn());
    });

    afterEach(() => {
        globalThis.fetch = originalFetch;
    });

    it('getStatus returns snapshot on success', async () => {
        const mockSnapshot = { timestamp: '2026-05-26T00:00:00Z', totalValueUSD: 1000.0, assets: {}, actions: [], drawdownPercent: 0, fiatDeploymentPercent: 0, effectiveUsdTargetPercent: 50 };
        vi.mocked(fetch).mockResolvedValueOnce({
            ok: true,
            json: async () => mockSnapshot
        } as unknown as Response);

        const result = await apiService.getStatus();
        expect(result).toEqual(mockSnapshot);
        expect(fetch).toHaveBeenCalledWith('/api/status', undefined);
    });

    it('throws ApiError with default message when response not ok and has no error body', async () => {
        vi.mocked(fetch).mockResolvedValueOnce({
            ok: false,
            status: 500,
            json: async () => { throw new Error('parse error'); }
        } as unknown as Response);

        await expect(apiService.getStatus()).rejects.toThrow(new ApiError(500, 'HTTP error! status: 500'));
    });

    it('throws ApiError with body error message when response not ok and has error field', async () => {
        vi.mocked(fetch).mockResolvedValueOnce({
            ok: false,
            status: 400,
            json: async () => ({ error: 'Invalid config target' })
        } as unknown as Response);

        await expect(apiService.getStatus()).rejects.toThrow(new ApiError(400, 'Invalid config target'));
    });

    it('throws ApiError with default message when response not ok and body is empty or has no error field', async () => {
        vi.mocked(fetch).mockResolvedValueOnce({
            ok: false,
            status: 400,
            json: async () => ({})
        } as unknown as Response);

        await expect(apiService.getStatus()).rejects.toThrow(new ApiError(400, 'HTTP error! status: 400'));
    });

    it('throws ApiError when body is null', async () => {
        vi.mocked(fetch).mockResolvedValueOnce({
            ok: false,
            status: 400,
            json: async () => null
        } as unknown as Response);

        await expect(apiService.getStatus()).rejects.toThrow(new ApiError(400, 'HTTP error! status: 400'));
    });

    it('updateSettings issues POST request', async () => {
        const mockConfig = { settings: { loopDelaySeconds: 60, deviationTriggerPercent: 5, dustThresholdUSD: 5, dryRun: true, fiatMaxDrawdown: 0, fiatDeploymentExponent: 1 }, allocations: [] };
        vi.mocked(fetch).mockResolvedValueOnce({
            ok: true,
            json: async () => mockConfig
        } as unknown as Response);

        const result = await apiService.updateSettings(mockConfig);
        expect(result).toEqual(mockConfig);
        expect(fetch).toHaveBeenCalledWith('/api/config', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(mockConfig)
        });
    });
});
