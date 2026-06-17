"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.createOrderResult = createOrderResult;
function createOrderResult(success, pair, side, volume, dryRun = false, errorMessage) {
    return {
        success,
        pair,
        side,
        volume,
        dryRun,
        errorMessage: success ? undefined : (errorMessage || 'Unknown error')
    };
}
