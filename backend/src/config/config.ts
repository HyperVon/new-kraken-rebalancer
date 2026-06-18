import * as fs from 'fs';
import { AtomicJsonFile } from '../repository/atomicFile';

import { z } from 'zod';

export class InvalidConfigurationError extends Error {
  constructor(message: string) {
    super(message);
    this.name = 'InvalidConfigurationError';
  }
}

export const SettingsSchema = z.object({
  loopDelaySeconds: z.number()
    .int('Loop delay must be a positive integer.')
    .positive('Loop delay must be a positive integer.'),
  deviationTriggerPercent: z.number().nonnegative('Deviation trigger percent must be non-negative.'),
  dustThresholdUSD: z.number().nonnegative('Dust threshold USD must be non-negative.'),
  dryRun: z.boolean(),
  fiatMaxDrawdown: z.number().min(0, 'Fiat max drawdown must be between 0% and 100%.').max(100, 'Fiat max drawdown must be between 0% and 100%.'),
  fiatDeploymentExponent: z.number().positive('Fiat deployment exponent must be positive.'),
});

export const AllocationSchema = z.object({
  symbol: z.string(),
  targetPercent: z.number(),
});

export const KrakenCredentialsSchema = z.object({
  apiKey: z.string(),
  privateKey: z.string(),
});

export const AppConfigSchema = z.object({
  kraken: KrakenCredentialsSchema,
  settings: SettingsSchema,
  allocations: z.array(AllocationSchema),
}).superRefine((data, ctx) => {
  if (!data.settings) {
    ctx.addIssue({
      code: z.ZodIssueCode.custom,
      message: 'Settings are missing.',
      path: ['settings']
    });
    return;
  }

  if (!data.allocations || data.allocations.length === 0) {
    ctx.addIssue({
      code: z.ZodIssueCode.custom,
      message: 'At least one allocation is required.',
      path: ['allocations']
    });
    return;
  }

  const symbols = data.allocations.map(a => a.symbol.toUpperCase());
  const counts = new Map<string, number>();
  for (const sym of symbols) {
    counts.set(sym, (counts.get(sym) || 0) + 1);
  }
  const duplicates = Array.from(counts.entries())
    .filter(([_, count]) => count > 1)
    .map(([sym]) => sym);
  if (duplicates.length > 0) {
    ctx.addIssue({
      code: z.ZodIssueCode.custom,
      message: `Duplicate allocation symbols are not allowed: ${duplicates.join(', ')}`,
      path: ['allocations']
    });
  }

  let totalPercent = 0;
  let hasUsd = false;

  for (const alloc of data.allocations) {
    if (!alloc.symbol || alloc.symbol.trim() === '') {
      ctx.addIssue({
        code: z.ZodIssueCode.custom,
        message: 'Allocation symbols cannot be blank.',
        path: ['allocations']
      });
    }
    if (alloc.targetPercent < 0) {
      ctx.addIssue({
        code: z.ZodIssueCode.custom,
        message: `Target percent for ${alloc.symbol} cannot be negative.`,
        path: ['allocations']
      });
    }
    totalPercent += alloc.targetPercent;
    if (alloc.symbol.toUpperCase() === 'USD') {
      hasUsd = true;
    }
  }

  if (Math.abs(totalPercent - 100.0) > 0.001) {
    ctx.addIssue({
      code: z.ZodIssueCode.custom,
      message: `Total allocation percentage must be exactly 100%. Current sum: ${totalPercent}`,
      path: ['allocations']
    });
  }

  if (!hasUsd) {
    ctx.addIssue({
      code: z.ZodIssueCode.custom,
      message: 'One asset must be USD.',
      path: ['allocations']
    });
  }
});

export type Settings = z.infer<typeof SettingsSchema>;
export type Allocation = z.infer<typeof AllocationSchema>;
export type KrakenCredentials = z.infer<typeof KrakenCredentialsSchema>;
export type AppConfig = z.infer<typeof AppConfigSchema>;

export class ConfigService {
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
    } catch (e: unknown) {
      const message = e instanceof Error ? e.message : String(e);
      throw new Error(`Failed to save configuration: ${message}`);
    }
  }

  private validateConfig(config: AppConfig): void {
    const result = AppConfigSchema.safeParse(config);
    if (!result.success) {
      const firstError = result.error.issues[0];
      throw new InvalidConfigurationError(firstError.message);
    }
  }
}
