"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.TradeHistoryService = void 0;
const events_1 = require("events");
class TradeHistoryService {
    repository;
    history = [];
    maxHistorySize = 50;
    emitter = new events_1.EventEmitter();
    constructor(repository) {
        this.repository = repository;
        this.init();
    }
    init() {
        const loaded = this.repository.load();
        if (loaded && loaded.length > 0) {
            this.history.push(...loaded);
        }
    }
    addSnapshot(snapshot) {
        this.history.unshift(snapshot);
        if (this.history.length > this.maxHistorySize) {
            this.history.pop();
        }
        this.repository.save([...this.history]);
        this.emitter.emit('snapshot', snapshot);
    }
    getHistory() {
        return [...this.history];
    }
    getLatestSnapshot() {
        return this.history[0] || null;
    }
    subscribe(listener) {
        this.emitter.on('snapshot', listener);
        return () => {
            this.emitter.off('snapshot', listener);
        };
    }
}
exports.TradeHistoryService = TradeHistoryService;
