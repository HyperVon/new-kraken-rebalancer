import * as fs from 'fs';
import { AtomicJsonFile } from '../repository/atomicFile';

export class InvalidConfigurationException extends Error {
  constructor(message: string) {
    super(message);
    this.name = 'InvalidConfigurationException';
  }
}

export interface Settings {
  loopDelaySeconds: number;
  deviationTriggerPercent: number;
  dustThresholdUSD: number;
  dryRun: boolean;
  fiatMaxDrawdown: number;
  fiatDeploymentExponent: number;
}

export interface Allocation {
  symbol: string;
  targetPercent: number;
}

export interface KrakenCredentials {
  apiKey: string;
  privateKey: string;
}

export interface AppConfig {
  kraken: KrakenCredentials;
  settings: Settings;
  allocations: Allocation[];
}

export interface ConfigService {
  loadConfig(): void;
  getConfig(): AppConfig;
  updateConfig(newConfig: AppConfig): void;
}

export class ConfigServiceImpl implements ConfigService {
  private readonly configFilePath: string;
  private appConfig!: AppConfig;

  constructor(configFilePath: string = 'rebalancer-config.json') {
    this.configFilePath = configFilePath;
    this.loadConfig();
  }

  loadConfig(): void {
    if (!fs.existsSync(this.configFilePath)) {
      throw new Error(
        `Configuration file '${this.configFilePath}' not found in the application directory.`
      );
    }
    const data = fs.readFileSync(this.configFilePath, 'utf8');
    const parsed = JSON.parse(data) as AppConfig;
    this.validateConfig(parsed);
    this.appConfig = parsed;
  }

  getConfig(): AppConfig {
    return this.appConfig;
  }

  updateConfig(newConfig: AppConfig): void {
    this.validateConfig(newConfig);
    this.appConfig = newConfig;
    try {
      AtomicJsonFile.writeSync(this.configFilePath, newConfig);
    } catch (e: any) {
      throw new Error(`Failed to save configuration: ${e.message}`);
    }
  }

  private validateConfig(config: AppConfig): void {
    const settings = config.settings;
    if (!settings) {
      throw new InvalidConfigurationException('Settings are missing.');
    }
    if (settings.loopDelaySeconds <= 0) {
      throw new InvalidConfigurationException('Loop delay must be a positive integer.');
    }
    if (settings.deviationTriggerPercent < 0) {
      throw new InvalidConfigurationException('Deviation trigger percent must be non-negative.');
    }
    if (settings.dustThresholdUSD < 0) {
      throw new InvalidConfigurationException('Dust threshold USD must be non-negative.');
    }
    if (settings.fiatMaxDrawdown < 0 || settings.fiatMaxDrawdown > 100) {
      throw new InvalidConfigurationException('Fiat max drawdown must be between 0% and 100%.');
    }
    if (settings.fiatDeploymentExponent <= 0) {
      throw new InvalidConfigurationException('Fiat deployment exponent must be positive.');
    }

    if (!config.allocations || config.allocations.length === 0) {
      throw new InvalidConfigurationException('At least one allocation is required.');
    }

    const symbols = config.allocations.map(a => a.symbol.toUpperCase());
    const counts = new Map<string, number>();
    for (const sym of symbols) {
      counts.set(sym, (counts.get(sym) || 0) + 1);
    }
    const duplicates = Array.from(counts.entries())
      .filter(([_, count]) => count > 1)
      .map(([sym]) => sym);
    if (duplicates.length > 0) {
      throw new InvalidConfigurationException(`Duplicate allocation symbols are not allowed: ${duplicates.join(', ')}`);
    }

    let totalPercent = 0;
    let hasUsd = false;

    for (const alloc of config.allocations) {
      if (!alloc.symbol || alloc.symbol.trim() === '') {
        throw new InvalidConfigurationException('Allocation symbols cannot be blank.');
      }
      if (alloc.targetPercent < 0) {
        throw new InvalidConfigurationException(`Target percent for ${alloc.symbol} cannot be negative.`);
      }
      totalPercent += alloc.targetPercent;
      if (alloc.symbol.toUpperCase() === 'USD') {
        hasUsd = true;
      }
    }

    if (Math.abs(totalPercent - 100.0) > 0.001) {
      throw new InvalidConfigurationException(`Total allocation percentage must be exactly 100%. Current sum: ${totalPercent}`);
    }

    if (!hasUsd) {
      throw new InvalidConfigurationException('One asset must be USD.');
    }
  }
}
