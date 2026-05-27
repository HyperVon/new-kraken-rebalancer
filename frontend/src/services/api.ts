import {FrontendConfig, PortfolioSnapshot} from '@/types';

export class ApiError extends Error {
    constructor(public status: number, message: string) {
        super(message);
    }
}

async function fetchJson<T>(url: string, options?: RequestInit): Promise<T> {
    const response = await fetch(url, options);
    if (!response.ok) {
        let message = `HTTP error! status: ${response.status}`;
        try {
            const body = await response.json();
            if (body?.error) {
                message = body.error;
            }
        } catch {
            // ignore parse errors for non-JSON bodies
        }
        throw new ApiError(response.status, message);
    }
    return response.json();
}

export const apiService = {
    getStatus: (): Promise<PortfolioSnapshot> => fetchJson('/api/status'),

    getHistory: (): Promise<PortfolioSnapshot[]> => fetchJson('/api/history'),

    getSettings: (): Promise<FrontendConfig> => fetchJson('/api/config'),

    updateSettings: (config: FrontendConfig): Promise<FrontendConfig> =>
        fetchJson('/api/config', {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json'
            },
            body: JSON.stringify(config)
        })
};
