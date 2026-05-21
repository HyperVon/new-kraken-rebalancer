export interface Allocation {
    symbol: string;
    targetPercent: number;
}

export interface Settings {
    loopDelaySeconds: number;
    deviationTriggerPercent: number;
    dustThresholdUSD: number;
    dryRun: boolean;
    fiatMaxDrawdown?: number;
    fiatDeploymentExponent?: number;
}

export interface FrontendConfig {
    settings: Settings;
    allocations: Allocation[];
}

export interface AssetSnapshot {
    symbol: string;
    balance: number;
    price: number;
    valueUSD: number;
    targetPercent: number;
    currentPercent: number;
    deviationPercent: number;
    deviationUSD: number;
}

export interface PortfolioSnapshot {
    timestamp: string;
    totalValueUSD: number;
    assets: Record<string, AssetSnapshot>;
    actions: string[];
    drawdownPercent: number;
    fiatDeploymentPercent: number;
    effectiveUsdTargetPercent?: number;
}

