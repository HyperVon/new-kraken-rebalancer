import { Controller, Get, Post, Res, Req, Body, HttpStatus } from '@nestjs/common';
import { Request, Response } from 'express';
import { ConfigService, InvalidConfigurationError, AppConfig } from '../config/config';
import { TradeHistoryService } from '../service/history';
import { decimalReplacer } from '../repository/atomicFile';

@Controller('api')
export class DashboardController {
  constructor(
    private readonly configService: ConfigService,
    private readonly tradeHistoryService: TradeHistoryService
  ) {}

  @Get('config')
  getConfig() {
    return this.configService.getConfig();
  }

  @Post('config')
  updateConfig(
    @Body() body: Pick<AppConfig, 'settings' | 'allocations'>,
    @Res() res: Response
  ) {
    try {
      const currentConfig = this.configService.getConfig();
      const updatedConfig = {
        kraken: currentConfig.kraken,
        settings: body.settings,
        allocations: body.allocations
      };
      this.configService.updateConfig(updatedConfig);
      return res.json({ success: true });
    } catch (e: unknown) {
      if (e instanceof InvalidConfigurationError) {
        return res.status(HttpStatus.BAD_REQUEST).json({ error: e.message });
      } else {
        const message = e instanceof Error ? e.message : 'Internal server error';
        return res.status(HttpStatus.INTERNAL_SERVER_ERROR).json({ error: message });
      }
    }
  }

  @Get('history')
  getHistory(@Res() res: Response) {
    const history = this.tradeHistoryService.getHistory();
    res.setHeader('Content-Type', 'application/json');
    return res.send(JSON.stringify(history, decimalReplacer));
  }

  @Get('latest')
  getLatest(@Res() res: Response) {
    const latest = this.tradeHistoryService.getLatestSnapshot();
    if (!latest) {
      return res.status(HttpStatus.NOT_FOUND).json({ error: 'No snapshots available' });
    }
    res.setHeader('Content-Type', 'application/json');
    return res.send(JSON.stringify(latest, decimalReplacer));
  }

  @Get('status/stream')
  streamStatus(@Res() res: Response, @Req() req: Request) {
    res.setHeader('Content-Type', 'text/event-stream');
    res.setHeader('Cache-Control', 'no-cache');
    res.setHeader('Connection', 'keep-alive');
    res.flushHeaders();

    const latest = this.tradeHistoryService.getLatestSnapshot();
    if (latest) {
      res.write(`data: ${JSON.stringify(latest, decimalReplacer)}\n\n`);
    }

    const unsubscribe = this.tradeHistoryService.subscribe((snapshot) => {
      res.write(`data: ${JSON.stringify(snapshot, decimalReplacer)}\n\n`);
    });

    req.on('close', () => {
      unsubscribe();
      res.end();
    });
  }
}
