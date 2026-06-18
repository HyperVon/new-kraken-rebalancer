import { EventEmitter } from 'events';
import { PortfolioSnapshot } from '../model/snapshot';
import { TradeRepository } from '../repository/trade';
import { Injectable } from '@nestjs/common';

@Injectable()
export class TradeHistoryService {
  private readonly repository: TradeRepository;
  private readonly history: PortfolioSnapshot[] = [];
  private readonly maxHistorySize = 50;
  private readonly emitter = new EventEmitter();

  constructor(repository: TradeRepository) {
    this.repository = repository;
    this.init();
  }

  init(): void {
    const loaded = this.repository.load();
    if (loaded && loaded.length > 0) {
      this.history.push(...loaded);
    }
  }

  addSnapshot(snapshot: PortfolioSnapshot): void {
    this.history.unshift(snapshot);
    if (this.history.length > this.maxHistorySize) {
      this.history.pop();
    }
    this.repository.save([...this.history]);
    this.emitter.emit('snapshot', snapshot);
  }

  getHistory(): PortfolioSnapshot[] {
    return [...this.history];
  }

  getLatestSnapshot(): PortfolioSnapshot | null {
    return this.history[0] || null;
  }

  subscribe(listener: (snapshot: PortfolioSnapshot) => void): () => void {
    this.emitter.on('snapshot', listener);
    return () => {
      this.emitter.off('snapshot', listener);
    };
  }
}
