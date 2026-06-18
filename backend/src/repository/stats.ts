import * as fs from 'fs';
import { Decimal } from 'decimal.js';
import { PortfolioStats } from '../model/stats';
import { AtomicJsonFile } from './atomicFile';
import { Injectable, Optional } from '@nestjs/common';

@Injectable()
export class PortfolioStatsRepository {
  private readonly filePath: string;

  constructor(@Optional() filePath: string = 'portfolio-stats.json') {
    this.filePath = filePath;
  }

  load(): PortfolioStats {
    if (!fs.existsSync(this.filePath)) {
      return { allTimeHigh: new Decimal(0) };
    }

    try {
      const data = fs.readFileSync(this.filePath, 'utf8');
      const obj = JSON.parse(data);
      const ath = obj.allTimeHigh !== undefined && obj.allTimeHigh !== null ? new Decimal(obj.allTimeHigh) : new Decimal(0);
      return { allTimeHigh: ath };
    } catch (e) {
      return { allTimeHigh: new Decimal(0) };
    }
  }

  save(stats: PortfolioStats): void {
    AtomicJsonFile.writeSync(this.filePath, stats);
  }
}
