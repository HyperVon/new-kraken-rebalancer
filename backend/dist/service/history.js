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
Object.defineProperty(exports, "__esModule", { value: true });
exports.TradeHistoryService = void 0;
const events_1 = require("events");
const trade_1 = require("../repository/trade");
const common_1 = require("@nestjs/common");
let TradeHistoryService = class TradeHistoryService {
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
};
exports.TradeHistoryService = TradeHistoryService;
exports.TradeHistoryService = TradeHistoryService = __decorate([
    (0, common_1.Injectable)(),
    __metadata("design:paramtypes", [trade_1.TradeRepository])
], TradeHistoryService);
