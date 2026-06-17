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
exports.FileTradeRepositoryImpl = void 0;
const fs = __importStar(require("fs"));
const decimal_js_1 = require("decimal.js");
const atomicFile_1 = require("./atomicFile");
class FileTradeRepositoryImpl {
    filePath;
    constructor(filePath = 'trade-history.json') {
        this.filePath = filePath;
    }
    load() {
        if (!fs.existsSync(this.filePath)) {
            return [];
        }
        try {
            const data = fs.readFileSync(this.filePath, 'utf8');
            const list = JSON.parse(data);
            return list.map(item => this.parseSnapshot(item));
        }
        catch (e) {
            return [];
        }
    }
    save(history) {
        atomicFile_1.AtomicJsonFile.writeSync(this.filePath, history);
    }
    parseSnapshot(obj) {
        const assets = {};
        if (obj.assets) {
            for (const key of Object.keys(obj.assets)) {
                const a = obj.assets[key];
                assets[key] = {
                    symbol: a.symbol,
                    balance: new decimal_js_1.Decimal(a.balance),
                    price: new decimal_js_1.Decimal(a.price),
                    valueUSD: new decimal_js_1.Decimal(a.valueUSD),
                    targetPercent: new decimal_js_1.Decimal(a.targetPercent),
                    currentPercent: new decimal_js_1.Decimal(a.currentPercent),
                    deviationPercent: new decimal_js_1.Decimal(a.deviationPercent),
                    deviationUSD: new decimal_js_1.Decimal(a.deviationUSD)
                };
            }
        }
        return {
            timestamp: obj.timestamp,
            totalValueUSD: new decimal_js_1.Decimal(obj.totalValueUSD),
            assets,
            actions: obj.actions || [],
            drawdownPercent: new decimal_js_1.Decimal(obj.drawdownPercent || 0),
            fiatDeploymentPercent: new decimal_js_1.Decimal(obj.fiatDeploymentPercent || 0),
            effectiveUsdTargetPercent: new decimal_js_1.Decimal(obj.effectiveUsdTargetPercent || 0)
        };
    }
}
exports.FileTradeRepositoryImpl = FileTradeRepositoryImpl;
