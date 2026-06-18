import express from 'express';
import cors from 'cors';
import * as path from 'path';
import * as fs from 'fs';
import dotenv from 'dotenv';

import { ConfigService } from './config/config';
import { TradeRepository } from './repository/trade';
import { PortfolioStatsRepository } from './repository/stats';
import { TradeHistoryService } from './service/history';
import { KrakenService } from './service/kraken';
import { PortfolioAnalyzer } from './service/analyzer';
import { OrderExecutor } from './service/executor';
import { PortfolioManager } from './service/manager';
import { createDashboardRouter } from './routes/dashboard';

dotenv.config();

const app = express();
const port = process.env.PORT || 8080;

app.use(cors());
app.use(express.json());

// Initialize services
const configService = new ConfigService();
const tradeRepository = new TradeRepository();
const portfolioStatsRepository = new PortfolioStatsRepository();
const tradeHistoryService = new TradeHistoryService(tradeRepository);
const krakenService = new KrakenService(configService);
const portfolioAnalyzer = new PortfolioAnalyzer(krakenService, configService, portfolioStatsRepository);
const orderExecutor = new OrderExecutor(krakenService, portfolioAnalyzer);
const portfolioManager = new PortfolioManager(configService, tradeHistoryService, portfolioAnalyzer, orderExecutor);

// Start rebalancing loop
portfolioManager.startRebalancingLoop();

// Routes
app.use('/api', createDashboardRouter(configService, tradeHistoryService));

// Static files (frontend)
const frontendDistPath = path.join(__dirname, '../../frontend/dist');
if (fs.existsSync(frontendDistPath)) {
  app.use(express.static(frontendDistPath));
  app.get('*', (req, res) => {
    res.sendFile(path.join(frontendDistPath, 'index.html'));
  });
} else {
  console.log(`Frontend build directory not found at ${frontendDistPath}. API server running as standalone.`);
}

const server = app.listen(port, () => {
  console.log(`Server running on port ${port}`);
});

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
export { app, server, portfolioManager };
