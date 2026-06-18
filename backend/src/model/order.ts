import { Decimal } from 'decimal.js';

export type OrderResult =
  | {
      success: true;
      pair: string;
      side: 'buy' | 'sell';
      volume: Decimal;
      dryRun: boolean;
    }
  | {
      success: false;
      pair: string;
      side: 'buy' | 'sell';
      volume: Decimal;
      dryRun: boolean;
      errorMessage: string;
    };

