import { Decimal } from 'decimal.js';

export interface OrderResult {
  pair: string;
  side: 'buy' | 'sell';
  volume: Decimal;
  dryRun: boolean;
  success: boolean;
  errorMessage?: string;
}

export function createOrderResult(
  success: boolean,
  pair: string,
  side: 'buy' | 'sell',
  volume: Decimal,
  dryRun: boolean = false,
  errorMessage?: string
): OrderResult {
  return {
    success,
    pair,
    side,
    volume,
    dryRun,
    errorMessage: success ? undefined : (errorMessage || 'Unknown error')
  };
}
