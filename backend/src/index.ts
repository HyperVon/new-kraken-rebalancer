import express from 'express';
import cors from 'cors';
import * as path from 'path';
import * as fs from 'fs';
import dotenv from 'dotenv';

import { ConfigServiceImpl } from './config/config';
import { FileTradeRepositoryImpl } from './repository/trade';
import { PortfolioStatsRepositoryImpl } from './repository/stats';
import { TradeHistoryServiceImpl } from './service/history';
import { KrakenServiceImpl } from './service/kraken';
import { PortfolioAnalyzer } from './service/analyzer';
import { OrderExecutor } from './service/executor';
import { PortfolioManagerImpl } from './service/manager';
import { createDashboardRouter } from './routes/dashboard';

dotenv.config();

const app = express();
const port = process.env.PORT || 8080;

app.use(cors());
app.use(express.json());

// Initialize services
const configService = new ConfigServiceImpl();
const tradeRepository = new FileTradeRepositoryImpl();
const portfolioStatsRepository = new PortfolioStatsRepositoryImpl();
const tradeHistoryService = new TradeHistoryServiceImpl(tradeRepository);
const krakenService = new KrakenServiceImpl(configService);
const portfolioAnalyzer = new PortfolioAnalyzer(krakenService, configService, portfolioStatsRepository);
const orderExecutor = new OrderExecutor(krakenService, portfolioAnalyzer);
const portfolioManager = new PortfolioManagerImpl(configService, tradeHistoryService, portfolioAnalyzer, orderExecutor);

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
