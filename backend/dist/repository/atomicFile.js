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
var __importDefault = (this && this.__importDefault) || function (mod) {
    return (mod && mod.__esModule) ? mod : { "default": mod };
};
Object.defineProperty(exports, "__esModule", { value: true });
exports.AtomicJsonFile = void 0;
exports.decimalReplacer = decimalReplacer;
const fs_1 = __importDefault(require("fs"));
const path = __importStar(require("path"));
const decimal_js_1 = require("decimal.js");
function decimalReplacer(key, value) {
    if (value instanceof decimal_js_1.Decimal) {
        return value.toNumber();
    }
    return value;
}
class AtomicJsonFile {
    static writeSync(targetPath, value) {
        const absoluteTargetPath = path.resolve(targetPath);
        const parentDir = path.dirname(absoluteTargetPath);
        if (!fs_1.default.existsSync(parentDir)) {
            fs_1.default.mkdirSync(parentDir, { recursive: true });
        }
        const tempPath = path.join(parentDir, `${path.basename(targetPath)}.${Date.now()}.tmp`);
        try {
            const data = JSON.stringify(value, decimalReplacer, 2);
            fs_1.default.writeFileSync(tempPath, data, 'utf8');
            fs_1.default.renameSync(tempPath, absoluteTargetPath);
        }
        catch (err) {
            if (fs_1.default.existsSync(tempPath)) {
                try {
                    fs_1.default.unlinkSync(tempPath);
                }
                catch (_) { }
            }
            throw err;
        }
    }
}
exports.AtomicJsonFile = AtomicJsonFile;
