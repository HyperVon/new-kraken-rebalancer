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
exports.ConfigServiceImpl = exports.InvalidConfigurationException = void 0;
const fs = __importStar(require("fs"));
const atomicFile_1 = require("../repository/atomicFile");
class InvalidConfigurationException extends Error {
    constructor(message) {
        super(message);
        this.name = 'InvalidConfigurationException';
    }
}
exports.InvalidConfigurationException = InvalidConfigurationException;
class ConfigServiceImpl {
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
            throw new Error(`Failed to save configuration: ${e.message}`);
        }
    }
    validateConfig(config) {
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
        const counts = new Map();
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
exports.ConfigServiceImpl = ConfigServiceImpl;
