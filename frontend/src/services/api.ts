import { PortfolioSnapshot, StatusResponse, Settings, AppConfig, KrakenCredentials } from '@/types';

class ApiError extends Error {
    constructor(public status: number, message: string) {
        super(message);
    }
}

async function fetchJson<T>(url: string, options?: RequestInit): Promise<T> {
    const response = await fetch(url, options);
    if (!response.ok) {
        throw new ApiError(response.status, `HTTP error! status: ${response.status}`);
    }
    return response.json();
}

export const apiService = {
    getStatus: (): Promise<StatusResponse> => fetchJson('/api/status'),
    
    getHistory: (): Promise<PortfolioSnapshot[]> => fetchJson('/api/history'),
    
    getSettings: (): Promise<Settings> => fetchJson('/api/config'),
    
    updateSettings: (settings: Settings): Promise<void> => 
        fetchJson('/api/config', {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json'
            },
            body: JSON.stringify(settings)
        })
};
