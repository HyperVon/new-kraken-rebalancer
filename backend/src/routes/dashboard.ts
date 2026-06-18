import { Router, Request, Response } from 'express';
import { ConfigService, InvalidConfigurationError } from '../config/config';
import { TradeHistoryService } from '../service/history';
import { decimalReplacer } from '../repository/atomicFile';

export function createDashboardRouter(
  configService: ConfigService,
  tradeHistoryService: TradeHistoryService
): Router {
  const router = Router();

  router.get('/config', (req: Request, res: Response) => {
    res.json(configService.getConfig());
  });

  router.post('/config', (req: Request, res: Response) => {
    try {
      const currentConfig = configService.getConfig();
      const updatedConfig = {
        kraken: currentConfig.kraken,
        settings: req.body.settings,
        allocations: req.body.allocations
      };
      configService.updateConfig(updatedConfig);
      res.json({ success: true });
    } catch (e: unknown) {
      if (e instanceof InvalidConfigurationError) {
        res.status(400).json({ error: e.message });
      } else {
        const message = e instanceof Error ? e.message : 'Internal server error';
        res.status(500).json({ error: message });
      }
    }
  });

  router.get('/history', (req: Request, res: Response) => {
    const history = tradeHistoryService.getHistory();
    res.setHeader('Content-Type', 'application/json');
    res.send(JSON.stringify(history, decimalReplacer));
  });

  router.get('/latest', (req: Request, res: Response) => {
    const latest = tradeHistoryService.getLatestSnapshot();
    if (!latest) {
      return res.status(404).json({ error: 'No snapshots available' });
    }
    res.setHeader('Content-Type', 'application/json');
    res.send(JSON.stringify(latest, decimalReplacer));
  });

  router.get('/status/stream', (req: Request, res: Response) => {
    res.setHeader('Content-Type', 'text/event-stream');
    res.setHeader('Cache-Control', 'no-cache');
    res.setHeader('Connection', 'keep-alive');
    res.flushHeaders();

    const latest = tradeHistoryService.getLatestSnapshot();
    if (latest) {
      res.write(`data: ${JSON.stringify(latest, decimalReplacer)}\n\n`);
    }

    const unsubscribe = tradeHistoryService.subscribe((snapshot) => {
      res.write(`data: ${JSON.stringify(snapshot, decimalReplacer)}\n\n`);
    });

    req.on('close', () => {
      unsubscribe();
      res.end();
    });
  });

  return router;
}
