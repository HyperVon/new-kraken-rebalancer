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
exports.PortfolioStatsRepositoryImpl = void 0;
const fs = __importStar(require("fs"));
const decimal_js_1 = require("decimal.js");
const atomicFile_1 = require("./atomicFile");
class PortfolioStatsRepositoryImpl {
    filePath;
    constructor(filePath = 'portfolio-stats.json') {
        this.filePath = filePath;
    }
    load() {
        if (!fs.existsSync(this.filePath)) {
            return { allTimeHigh: new decimal_js_1.Decimal(0) };
        }
        try {
            const data = fs.readFileSync(this.filePath, 'utf8');
            const obj = JSON.parse(data);
            const ath = obj.allTimeHigh !== undefined && obj.allTimeHigh !== null ? new decimal_js_1.Decimal(obj.allTimeHigh) : new decimal_js_1.Decimal(0);
            return { allTimeHigh: ath };
        }
        catch (e) {
            return { allTimeHigh: new decimal_js_1.Decimal(0) };
        }
    }
    save(stats) {
        atomicFile_1.AtomicJsonFile.writeSync(this.filePath, stats);
    }
}
exports.PortfolioStatsRepositoryImpl = PortfolioStatsRepositoryImpl;
