import { Module } from '@nestjs/common';
import { DashboardController } from './controller/dashboard.controller';
import { ConfigService } from './config/config';
import { TradeRepository } from './repository/trade';
import { PortfolioStatsRepository } from './repository/stats';
import { TradeHistoryService } from './service/history';
import { KrakenService, KRAKEN_SERVICE_TOKEN } from './service/kraken';
import { PortfolioAnalyzer } from './service/analyzer';
import { OrderExecutor } from './service/executor';
import { PortfolioManager } from './service/manager';

@Module({
  controllers: [DashboardController],
  providers: [
    ConfigService,
    TradeRepository,
    PortfolioStatsRepository,
    TradeHistoryService,
    {
      provide: KRAKEN_SERVICE_TOKEN,
      useClass: KrakenService,
    },
    PortfolioAnalyzer,
    OrderExecutor,
    PortfolioManager,
  ],
})
export class AppModule {}
