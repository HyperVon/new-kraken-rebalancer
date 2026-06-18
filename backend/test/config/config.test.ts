import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import * as fs from 'fs';
import * as path from 'path';
import * as os from 'os';
import { ConfigService, InvalidConfigurationError, AppConfig } from '../../src/config/config';
import { AtomicJsonFile } from '../../src/repository/atomicFile';

describe('ConfigService', () => {
  let tempFilePath: string;
  let configService: ConfigService;

  const validConfig: AppConfig = {
    kraken: { apiKey: 'k', privateKey: 's' },
    settings: {
      loopDelaySeconds: 60,
      deviationTriggerPercent: 2.0,
      dustThresholdUSD: 1.0,
      dryRun: true,
      fiatMaxDrawdown: 0.0,
      fiatDeploymentExponent: 1.0
    },
    allocations: [{ symbol: 'USD', targetPercent: 100.0 }]
  };

  beforeEach(() => {
    const tempDir = fs.mkdtempSync(path.join(os.tmpdir(), 'rebalancer-test-'));
    tempFilePath = path.join(tempDir, 'test-config.json');
    fs.writeFileSync(tempFilePath, JSON.stringify(validConfig, null, 2), 'utf8');
    configService = new ConfigService(tempFilePath);
  });

  afterEach(() => {
    if (fs.existsSync(tempFilePath)) {
      try {
        fs.unlinkSync(tempFilePath);
        fs.rmdirSync(path.dirname(tempFilePath));
      } catch (_) {}
    }
  });

  it('should successfully load a valid configuration', () => {
    configService.loadConfig();
    const config = configService.getConfig();
    expect(config).toBeDefined();
    expect(config.allocations[0].symbol.toUpperCase()).toBe('USD');
  });

  it('should throw an error if the configuration file is not found', () => {
    const missingFilePath = path.join(path.dirname(tempFilePath), 'missing.json');
    expect(() => new ConfigService(missingFilePath)).toThrow(/not found/);
  });

  it('should successfully update config and save it to file', () => {
    configService.loadConfig();
    const oldConfig = configService.getConfig();
    const newConfig: AppConfig = {
      ...oldConfig,
      allocations: [
        { symbol: 'USD', targetPercent: 50.0 },
        { symbol: 'BTC', targetPercent: 50.0 }
      ]
    };

    configService.updateConfig(newConfig);

    expect(configService.getConfig().allocations.length).toBe(2);
    const fileContent = JSON.parse(fs.readFileSync(tempFilePath, 'utf8'));
    expect(fileContent.allocations.length).toBe(2);
  });

  it('should throw InvalidConfigurationError if total allocations are not 100%', () => {
    configService.loadConfig();
    const oldConfig = configService.getConfig();
    const invalidConfig: AppConfig = {
      ...oldConfig,
      allocations: [{ symbol: 'USD', targetPercent: 90.0 }]
    };

    expect(() => configService.updateConfig(invalidConfig)).toThrow(InvalidConfigurationError);
  });

  it('should throw InvalidConfigurationError if USD is missing from allocations', () => {
    configService.loadConfig();
    const oldConfig = configService.getConfig();
    const invalidConfig: AppConfig = {
      ...oldConfig,
      allocations: [{ symbol: 'BTC', targetPercent: 100.0 }]
    };

    expect(() => configService.updateConfig(invalidConfig)).toThrow(InvalidConfigurationError);
  });

  it('should throw InvalidConfigurationError if there are duplicate symbols', () => {
    configService.loadConfig();
    const oldConfig = configService.getConfig();
    const invalidConfig: AppConfig = {
      ...oldConfig,
      allocations: [
        { symbol: 'BTC', targetPercent: 50.0 },
        { symbol: 'btc', targetPercent: 50.0 }
      ]
    };

    expect(() => configService.updateConfig(invalidConfig)).toThrow(InvalidConfigurationError);
  });

  it('should throw InvalidConfigurationError if target percent is negative', () => {
    configService.loadConfig();
    const oldConfig = configService.getConfig();
    const invalidConfig: AppConfig = {
      ...oldConfig,
      allocations: [
        { symbol: 'USD', targetPercent: 110.0 },
        { symbol: 'BTC', targetPercent: -10.0 }
      ]
    };

    expect(() => configService.updateConfig(invalidConfig)).toThrow(InvalidConfigurationError);
  });

  it('should throw InvalidConfigurationError if allocations list is empty', () => {
    configService.loadConfig();
    const oldConfig = configService.getConfig();
    const invalidConfig: AppConfig = {
      ...oldConfig,
      allocations: []
    };

    expect(() => configService.updateConfig(invalidConfig)).toThrow(InvalidConfigurationError);
  });

  it('should throw InvalidConfigurationError if allocation symbol is blank', () => {
    configService.loadConfig();
    const oldConfig = configService.getConfig();
    const invalidConfig: AppConfig = {
      ...oldConfig,
      allocations: [
        { symbol: 'USD', targetPercent: 50.0 },
        { symbol: '   ', targetPercent: 50.0 }
      ]
    };

    expect(() => configService.updateConfig(invalidConfig)).toThrow(InvalidConfigurationError);
  });

  it('should throw InvalidConfigurationError if settings parameters are invalid', () => {
    configService.loadConfig();
    const oldConfig = configService.getConfig();

    const badLoopDelay: AppConfig = {
      ...oldConfig,
      settings: { ...oldConfig.settings, loopDelaySeconds: 0 }
    };
    expect(() => configService.updateConfig(badLoopDelay)).toThrow(InvalidConfigurationError);

    const badDev: AppConfig = {
      ...oldConfig,
      settings: { ...oldConfig.settings, deviationTriggerPercent: -1.0 }
    };
    expect(() => configService.updateConfig(badDev)).toThrow(InvalidConfigurationError);

    const badDust: AppConfig = {
      ...oldConfig,
      settings: { ...oldConfig.settings, dustThresholdUSD: -1.0 }
    };
    expect(() => configService.updateConfig(badDust)).toThrow(InvalidConfigurationError);

    const badFiatDrawdown1: AppConfig = {
      ...oldConfig,
      settings: { ...oldConfig.settings, fiatMaxDrawdown: -1.0 }
    };
    expect(() => configService.updateConfig(badFiatDrawdown1)).toThrow(InvalidConfigurationError);

    const badFiatDrawdown2: AppConfig = {
      ...oldConfig,
      settings: { ...oldConfig.settings, fiatMaxDrawdown: 101.0 }
    };
    expect(() => configService.updateConfig(badFiatDrawdown2)).toThrow(InvalidConfigurationError);

    const badFiatExp: AppConfig = {
      ...oldConfig,
      settings: { ...oldConfig.settings, fiatDeploymentExponent: 0.0 }
    };
    expect(() => configService.updateConfig(badFiatExp)).toThrow(InvalidConfigurationError);
  });

  it('should wrap persistence error into a configuration save error', () => {
    // Mock AtomicJsonFile.writeSync to throw an error on save
    const spy = vi.spyOn(AtomicJsonFile, 'writeSync').mockImplementation(() => {
      throw new Error('Write error');
    });

    const newConfig: AppConfig = {
      ...validConfig,
      allocations: [{ symbol: 'USD', targetPercent: 100.0 }]
    };

    expect(() => configService.updateConfig(newConfig)).toThrow(/Failed to save configuration/);
    spy.mockRestore();
  });
});
