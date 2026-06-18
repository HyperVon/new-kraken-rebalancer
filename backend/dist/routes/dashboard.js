"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.createDashboardRouter = createDashboardRouter;
const express_1 = require("express");
const config_1 = require("../config/config");
const atomicFile_1 = require("../repository/atomicFile");
function createDashboardRouter(configService, tradeHistoryService) {
    const router = (0, express_1.Router)();
    router.get('/config', (req, res) => {
        res.json(configService.getConfig());
    });
    router.post('/config', (req, res) => {
        try {
            const currentConfig = configService.getConfig();
            const updatedConfig = {
                kraken: currentConfig.kraken,
                settings: req.body.settings,
                allocations: req.body.allocations
            };
            configService.updateConfig(updatedConfig);
            res.json({ success: true });
        }
        catch (e) {
            if (e instanceof config_1.InvalidConfigurationError) {
                res.status(400).json({ error: e.message });
            }
            else {
                const message = e instanceof Error ? e.message : 'Internal server error';
                res.status(500).json({ error: message });
            }
        }
    });
    router.get('/history', (req, res) => {
        const history = tradeHistoryService.getHistory();
        res.setHeader('Content-Type', 'application/json');
        res.send(JSON.stringify(history, atomicFile_1.decimalReplacer));
    });
    router.get('/latest', (req, res) => {
        const latest = tradeHistoryService.getLatestSnapshot();
        if (!latest) {
            return res.status(404).json({ error: 'No snapshots available' });
        }
        res.setHeader('Content-Type', 'application/json');
        res.send(JSON.stringify(latest, atomicFile_1.decimalReplacer));
    });
    router.get('/status/stream', (req, res) => {
        res.setHeader('Content-Type', 'text/event-stream');
        res.setHeader('Cache-Control', 'no-cache');
        res.setHeader('Connection', 'keep-alive');
        res.flushHeaders();
        const latest = tradeHistoryService.getLatestSnapshot();
        if (latest) {
            res.write(`data: ${JSON.stringify(latest, atomicFile_1.decimalReplacer)}\n\n`);
        }
        const unsubscribe = tradeHistoryService.subscribe((snapshot) => {
            res.write(`data: ${JSON.stringify(snapshot, atomicFile_1.decimalReplacer)}\n\n`);
        });
        req.on('close', () => {
            unsubscribe();
            res.end();
        });
    });
    return router;
}
