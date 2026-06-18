"use strict";
var __decorate = (this && this.__decorate) || function (decorators, target, key, desc) {
    var c = arguments.length, r = c < 3 ? target : desc === null ? desc = Object.getOwnPropertyDescriptor(target, key) : desc, d;
    if (typeof Reflect === "object" && typeof Reflect.decorate === "function") r = Reflect.decorate(decorators, target, key, desc);
    else for (var i = decorators.length - 1; i >= 0; i--) if (d = decorators[i]) r = (c < 3 ? d(r) : c > 3 ? d(target, key, r) : d(target, key)) || r;
    return c > 3 && r && Object.defineProperty(target, key, r), r;
};
var __metadata = (this && this.__metadata) || function (k, v) {
    if (typeof Reflect === "object" && typeof Reflect.metadata === "function") return Reflect.metadata(k, v);
};
var __param = (this && this.__param) || function (paramIndex, decorator) {
    return function (target, key) { decorator(target, key, paramIndex); }
};
Object.defineProperty(exports, "__esModule", { value: true });
exports.DashboardController = void 0;
const common_1 = require("@nestjs/common");
const config_1 = require("../config/config");
const history_1 = require("../service/history");
const atomicFile_1 = require("../repository/atomicFile");
let DashboardController = class DashboardController {
    configService;
    tradeHistoryService;
    constructor(configService, tradeHistoryService) {
        this.configService = configService;
        this.tradeHistoryService = tradeHistoryService;
    }
    getConfig() {
        return this.configService.getConfig();
    }
    updateConfig(body, res) {
        try {
            const currentConfig = this.configService.getConfig();
            const updatedConfig = {
                kraken: currentConfig.kraken,
                settings: body.settings,
                allocations: body.allocations
            };
            this.configService.updateConfig(updatedConfig);
            return res.json({ success: true });
        }
        catch (e) {
            if (e instanceof config_1.InvalidConfigurationError) {
                return res.status(common_1.HttpStatus.BAD_REQUEST).json({ error: e.message });
            }
            else {
                const message = e instanceof Error ? e.message : 'Internal server error';
                return res.status(common_1.HttpStatus.INTERNAL_SERVER_ERROR).json({ error: message });
            }
        }
    }
    getHistory(res) {
        const history = this.tradeHistoryService.getHistory();
        res.setHeader('Content-Type', 'application/json');
        return res.send(JSON.stringify(history, atomicFile_1.decimalReplacer));
    }
    getLatest(res) {
        const latest = this.tradeHistoryService.getLatestSnapshot();
        if (!latest) {
            return res.status(common_1.HttpStatus.NOT_FOUND).json({ error: 'No snapshots available' });
        }
        res.setHeader('Content-Type', 'application/json');
        return res.send(JSON.stringify(latest, atomicFile_1.decimalReplacer));
    }
    streamStatus(res, req) {
        res.setHeader('Content-Type', 'text/event-stream');
        res.setHeader('Cache-Control', 'no-cache');
        res.setHeader('Connection', 'keep-alive');
        res.flushHeaders();
        const latest = this.tradeHistoryService.getLatestSnapshot();
        if (latest) {
            res.write(`data: ${JSON.stringify(latest, atomicFile_1.decimalReplacer)}\n\n`);
        }
        const unsubscribe = this.tradeHistoryService.subscribe((snapshot) => {
            res.write(`data: ${JSON.stringify(snapshot, atomicFile_1.decimalReplacer)}\n\n`);
        });
        req.on('close', () => {
            unsubscribe();
            res.end();
        });
    }
};
exports.DashboardController = DashboardController;
__decorate([
    (0, common_1.Get)('config'),
    __metadata("design:type", Function),
    __metadata("design:paramtypes", []),
    __metadata("design:returntype", void 0)
], DashboardController.prototype, "getConfig", null);
__decorate([
    (0, common_1.Post)('config'),
    __param(0, (0, common_1.Body)()),
    __param(1, (0, common_1.Res)()),
    __metadata("design:type", Function),
    __metadata("design:paramtypes", [Object, Object]),
    __metadata("design:returntype", void 0)
], DashboardController.prototype, "updateConfig", null);
__decorate([
    (0, common_1.Get)('history'),
    __param(0, (0, common_1.Res)()),
    __metadata("design:type", Function),
    __metadata("design:paramtypes", [Object]),
    __metadata("design:returntype", void 0)
], DashboardController.prototype, "getHistory", null);
__decorate([
    (0, common_1.Get)('latest'),
    __param(0, (0, common_1.Res)()),
    __metadata("design:type", Function),
    __metadata("design:paramtypes", [Object]),
    __metadata("design:returntype", void 0)
], DashboardController.prototype, "getLatest", null);
__decorate([
    (0, common_1.Get)('status/stream'),
    __param(0, (0, common_1.Res)()),
    __param(1, (0, common_1.Req)()),
    __metadata("design:type", Function),
    __metadata("design:paramtypes", [Object, Object]),
    __metadata("design:returntype", void 0)
], DashboardController.prototype, "streamStatus", null);
exports.DashboardController = DashboardController = __decorate([
    (0, common_1.Controller)('api'),
    __metadata("design:paramtypes", [config_1.ConfigService,
        history_1.TradeHistoryService])
], DashboardController);
