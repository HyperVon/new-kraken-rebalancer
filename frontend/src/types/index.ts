export interface Allocation {
    asset: string;
    targetPercentage: number;
}

export interface AppConfig {
    allocations: Allocation[];
    rebalanceIntervalMs: number;
    buyThresholdPercent: number;
    sellThresholdPercent: number;
    dryRun: boolean;
    fiatCurrency: string;
}

export interface KrakenCredentials {
    apiKey: string;
    apiSecret: string;
}

export interface Settings {
    config: AppConfig;
    credentials: KrakenCredentials;
}

export interface AssetSnapshot {
    asset: string;
    balance: number;
    price: number;
    valueUSD: number;
    actualPercentage: number;
    targetPercentage: number;
    deviationPercentage: number;
}

export interface PortfolioSnapshot {
    id?: number;
    timestamp: string;
    totalValueUSD: number;
    assets: AssetSnapshot[];
}

export interface StatusResponse {
    currentStatus: PortfolioSnapshot | null;
    athValueUSD: number;
    errors: string[];
}
