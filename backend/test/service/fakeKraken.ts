import { Decimal } from 'decimal.js';
import { KrakenService, IKrakenService } from '../../src/service/kraken';
import { OrderResult } from '../../src/model/order';

export interface OrderCall {
  pair: string;
  type: string;
  side: 'buy' | 'sell';
  volume: Decimal;
}

export class FakeKrakenService implements IKrakenService {
  public balanceSupplier: () => Record<string, number> = () => ({});
  public pricesSupplier: (pairs: string) => Record<string, number> = () => ({});
  public executeOrderAction?: (pair: string, type: string, side: 'buy' | 'sell', volume: Decimal) => void;
  public orderResultFactory?: (pair: string, type: string, side: 'buy' | 'sell', volume: Decimal) => OrderResult;

  public executedOrders: OrderCall[] = [];
  public getBalancesCallCount = 0;

  async getBalances(): Promise<Record<string, number>> {
    this.getBalancesCallCount++;
    return this.balanceSupplier();
  }

  async getTickerPrices(pairs: string): Promise<Record<string, number>> {
    return this.pricesSupplier(pairs);
  }

  async executeOrder(
    pair: string,
    type: string,
    side: 'buy' | 'sell',
    volume: Decimal
  ): Promise<OrderResult> {
    this.executedOrders.push({ pair, type, side, volume });
    if (this.executeOrderAction) {
      this.executeOrderAction(pair, type, side, volume);
    }
    if (this.orderResultFactory) {
      return this.orderResultFactory(pair, type, side, volume);
    }
    return {
      success: true,
      pair,
      side,
      volume,
      dryRun: false
    };
  }
}
