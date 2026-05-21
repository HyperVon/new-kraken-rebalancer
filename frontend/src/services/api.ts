import { PortfolioSnapshot, FrontendConfig } from '@/types';

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

