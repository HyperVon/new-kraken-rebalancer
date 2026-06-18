"use strict";
var __createBinding = (this && this.__createBinding) || (Object.create ? (function(o, m, k, k2) {
    if (k2 === undefined) k2 = k;
    var desc = Object.getOwnPropertyDescriptor(m, k);
    if (!desc || ("get" in desc ? !m.__esModule : desc.writable || desc.configurable)) {
      desc = { enumerable: true, get: function() { return m[k]; } };
    }
    Object.defineProperty(o, k2, desc);
}) : (function(o, m, k, k2) {
    if (k2 === undefined) k2 = k;
    o[k2] = m[k];
}));
var __setModuleDefault = (this && this.__setModuleDefault) || (Object.create ? (function(o, v) {
    Object.defineProperty(o, "default", { enumerable: true, value: v });
}) : function(o, v) {
    o["default"] = v;
});
var __importStar = (this && this.__importStar) || (function () {
    var ownKeys = function(o) {
        ownKeys = Object.getOwnPropertyNames || function (o) {
            var ar = [];
            for (var k in o) if (Object.prototype.hasOwnProperty.call(o, k)) ar[ar.length] = k;
            return ar;
        };
        return ownKeys(o);
    };
    return function (mod) {
        if (mod && mod.__esModule) return mod;
        var result = {};
        if (mod != null) for (var k = ownKeys(mod), i = 0; i < k.length; i++) if (k[i] !== "default") __createBinding(result, mod, k[i]);
        __setModuleDefault(result, mod);
        return result;
    };
})();
Object.defineProperty(exports, "__esModule", { value: true });
exports.ConfigService = exports.AppConfigSchema = exports.KrakenCredentialsSchema = exports.AllocationSchema = exports.SettingsSchema = exports.InvalidConfigurationError = void 0;
const fs = __importStar(require("fs"));
const atomicFile_1 = require("../repository/atomicFile");
const zod_1 = require("zod");
class InvalidConfigurationError extends Error {
    constructor(message) {
        super(message);
        this.name = 'InvalidConfigurationError';
    }
}
exports.InvalidConfigurationError = InvalidConfigurationError;
exports.SettingsSchema = zod_1.z.object({
    loopDelaySeconds: zod_1.z.number()
        .int('Loop delay must be a positive integer.')
        .positive('Loop delay must be a positive integer.'),
    deviationTriggerPercent: zod_1.z.number().nonnegative('Deviation trigger percent must be non-negative.'),
    dustThresholdUSD: zod_1.z.number().nonnegative('Dust threshold USD must be non-negative.'),
    dryRun: zod_1.z.boolean(),
    fiatMaxDrawdown: zod_1.z.number().min(0, 'Fiat max drawdown must be between 0% and 100%.').max(100, 'Fiat max drawdown must be between 0% and 100%.'),
    fiatDeploymentExponent: zod_1.z.number().positive('Fiat deployment exponent must be positive.'),
});
exports.AllocationSchema = zod_1.z.object({
    symbol: zod_1.z.string(),
    targetPercent: zod_1.z.number(),
});
exports.KrakenCredentialsSchema = zod_1.z.object({
    apiKey: zod_1.z.string(),
    privateKey: zod_1.z.string(),
});
exports.AppConfigSchema = zod_1.z.object({
    kraken: exports.KrakenCredentialsSchema,
    settings: exports.SettingsSchema,
    allocations: zod_1.z.array(exports.AllocationSchema),
}).superRefine((data, ctx) => {
    if (!data.settings) {
        ctx.addIssue({
            code: zod_1.z.ZodIssueCode.custom,
            message: 'Settings are missing.',
            path: ['settings']
        });
        return;
    }
    if (!data.allocations || data.allocations.length === 0) {
        ctx.addIssue({
            code: zod_1.z.ZodIssueCode.custom,
            message: 'At least one allocation is required.',
            path: ['allocations']
        });
        return;
    }
    const symbols = data.allocations.map(a => a.symbol.toUpperCase());
    const counts = new Map();
    for (const sym of symbols) {
        counts.set(sym, (counts.get(sym) || 0) + 1);
    }
    const duplicates = Array.from(counts.entries())
        .filter(([_, count]) => count > 1)
        .map(([sym]) => sym);
    if (duplicates.length > 0) {
        ctx.addIssue({
            code: zod_1.z.ZodIssueCode.custom,
            message: `Duplicate allocation symbols are not allowed: ${duplicates.join(', ')}`,
            path: ['allocations']
        });
    }
    let totalPercent = 0;
    let hasUsd = false;
    for (const alloc of data.allocations) {
        if (!alloc.symbol || alloc.symbol.trim() === '') {
            ctx.addIssue({
                code: zod_1.z.ZodIssueCode.custom,
                message: 'Allocation symbols cannot be blank.',
                path: ['allocations']
            });
        }
        if (alloc.targetPercent < 0) {
            ctx.addIssue({
                code: zod_1.z.ZodIssueCode.custom,
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
            code: zod_1.z.ZodIssueCode.custom,
            message: `Total allocation percentage must be exactly 100%. Current sum: ${totalPercent}`,
            path: ['allocations']
        });
    }
    if (!hasUsd) {
        ctx.addIssue({
            code: zod_1.z.ZodIssueCode.custom,
            message: 'One asset must be USD.',
            path: ['allocations']
        });
    }
});
class ConfigService {
    configFilePath;
    appConfig;
    constructor(configFilePath = 'rebalancer-config.json') {
        this.configFilePath = configFilePath;
        this.loadConfig();
    }
    loadConfig() {
        if (!fs.existsSync(this.configFilePath)) {
            throw new Error(`Configuration file '${this.configFilePath}' not found in the application directory.`);
        }
        const data = fs.readFileSync(this.configFilePath, 'utf8');
        const parsed = JSON.parse(data);
        this.validateConfig(parsed);
        this.appConfig = parsed;
    }
    getConfig() {
        return this.appConfig;
    }
    updateConfig(newConfig) {
        this.validateConfig(newConfig);
        this.appConfig = newConfig;
        try {
            atomicFile_1.AtomicJsonFile.writeSync(this.configFilePath, newConfig);
        }
        catch (e) {
            const message = e instanceof Error ? e.message : String(e);
            throw new Error(`Failed to save configuration: ${message}`);
        }
    }
    validateConfig(config) {
        const result = exports.AppConfigSchema.safeParse(config);
        if (!result.success) {
            const firstError = result.error.issues[0];
            throw new InvalidConfigurationError(firstError.message);
        }
    }
}
exports.ConfigService = ConfigService;
