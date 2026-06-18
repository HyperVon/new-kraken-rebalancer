"use strict";
var __decorate = (this && this.__decorate) || function (decorators, target, key, desc) {
    var c = arguments.length, r = c < 3 ? target : desc === null ? desc = Object.getOwnPropertyDescriptor(target, key) : desc, d;
    if (typeof Reflect === "object" && typeof Reflect.decorate === "function") r = Reflect.decorate(decorators, target, key, desc);
    else for (var i = decorators.length - 1; i >= 0; i--) if (d = decorators[i]) r = (c < 3 ? d(r) : c > 3 ? d(target, key, r) : d(target, key)) || r;
    return c > 3 && r && Object.defineProperty(target, key, r), r;
};
Object.defineProperty(exports, "__esModule", { value: true });
exports.AppModule = void 0;
const common_1 = require("@nestjs/common");
const dashboard_controller_1 = require("./controller/dashboard.controller");
const config_1 = require("./config/config");
const trade_1 = require("./repository/trade");
const stats_1 = require("./repository/stats");
const history_1 = require("./service/history");
const kraken_1 = require("./service/kraken");
const analyzer_1 = require("./service/analyzer");
const executor_1 = require("./service/executor");
const manager_1 = require("./service/manager");
let AppModule = class AppModule {
};
exports.AppModule = AppModule;
exports.AppModule = AppModule = __decorate([
    (0, common_1.Module)({
        controllers: [dashboard_controller_1.DashboardController],
        providers: [
            config_1.ConfigService,
            trade_1.TradeRepository,
            stats_1.PortfolioStatsRepository,
            history_1.TradeHistoryService,
            {
                provide: kraken_1.KRAKEN_SERVICE_TOKEN,
                useClass: kraken_1.KrakenService,
            },
            analyzer_1.PortfolioAnalyzer,
            executor_1.OrderExecutor,
            manager_1.PortfolioManager,
        ],
    })
], AppModule);
