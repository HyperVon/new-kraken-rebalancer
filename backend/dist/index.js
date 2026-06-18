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
exports.portfolioManager = exports.server = exports.app = void 0;
const express_1 = __importDefault(require("express"));
const cors_1 = __importDefault(require("cors"));
const path = __importStar(require("path"));
const fs = __importStar(require("fs"));
const dotenv_1 = __importDefault(require("dotenv"));
const config_1 = require("./config/config");
const trade_1 = require("./repository/trade");
const stats_1 = require("./repository/stats");
const history_1 = require("./service/history");
const kraken_1 = require("./service/kraken");
const analyzer_1 = require("./service/analyzer");
const executor_1 = require("./service/executor");
const manager_1 = require("./service/manager");
const dashboard_1 = require("./routes/dashboard");
dotenv_1.default.config();
const app = (0, express_1.default)();
exports.app = app;
const port = process.env.PORT || 8080;
app.use((0, cors_1.default)());
app.use(express_1.default.json());
// Initialize services
const configService = new config_1.ConfigService();
const tradeRepository = new trade_1.TradeRepository();
const portfolioStatsRepository = new stats_1.PortfolioStatsRepository();
const tradeHistoryService = new history_1.TradeHistoryService(tradeRepository);
const krakenService = new kraken_1.KrakenService(configService);
const portfolioAnalyzer = new analyzer_1.PortfolioAnalyzer(krakenService, configService, portfolioStatsRepository);
const orderExecutor = new executor_1.OrderExecutor(krakenService, portfolioAnalyzer);
const portfolioManager = new manager_1.PortfolioManager(configService, tradeHistoryService, portfolioAnalyzer, orderExecutor);
exports.portfolioManager = portfolioManager;
// Start rebalancing loop
portfolioManager.startRebalancingLoop();
// Routes
app.use('/api', (0, dashboard_1.createDashboardRouter)(configService, tradeHistoryService));
// Static files (frontend)
const frontendDistPath = path.join(__dirname, '../../frontend/dist');
if (fs.existsSync(frontendDistPath)) {
    app.use(express_1.default.static(frontendDistPath));
    app.get('*', (req, res) => {
        res.sendFile(path.join(frontendDistPath, 'index.html'));
    });
}
else {
    console.log(`Frontend build directory not found at ${frontendDistPath}. API server running as standalone.`);
}
const server = app.listen(port, () => {
    console.log(`Server running on port ${port}`);
});
exports.server = server;
// Graceful shutdown
const shutdown = () => {
    console.log('Shutting down server gracefully...');
    portfolioManager.stopRebalancingLoop();
    server.close(() => {
        console.log('HTTP server closed.');
        process.exit(0);
    });
};
process.on('SIGINT', shutdown);
process.on('SIGTERM', shutdown);
