package service

import (
	"fmt"
	"log/slog"
	"sort"
	"time"

	"github.com/HyperVon/new-kraken-rebalancer/internal/config"
	"github.com/HyperVon/new-kraken-rebalancer/internal/model"
	"github.com/shopspring/decimal"
)

// OrderExecutor manages the execution sequence of generated trades.
type OrderExecutor struct {
	krakenService KrakenService
	analyzer      *PortfolioAnalyzer
}

// NewOrderExecutor creates a new OrderExecutor.
func NewOrderExecutor(krakenService KrakenService, analyzer *PortfolioAnalyzer) *OrderExecutor {
	return &OrderExecutor{
		krakenService: krakenService,
		analyzer:      analyzer,
	}
}

func (e *OrderExecutor) ExecuteOrders(
	buyOrders map[string]decimal.Decimal,
	sellOrders map[string]decimal.Decimal,
	currentValuesUSD map[string]decimal.Decimal,
	prices RawPrices,
	settings config.Settings,
	actionLog *[]string,
) {
	projectedCash := currentValuesUSD["USD"]
	executedSells := false

	for _, symbol := range sortedKeys(sellOrders) {
		usdToSell := sellOrders[symbol]
		if usdToSell.LessThan(settings.DustThresholdUSD) {
			slog.Debug("Skipping dust sell", "symbol", symbol, "amount", usdToSell.Round(2))
			*actionLog = append(*actionLog, fmt.Sprintf("Skipping dust sell for %s ($%s)", symbol, usdToSell.Round(2).String()))
			continue
		}

		priceFloat, exists := prices[symbol]
		if !exists || priceFloat <= 0 {
			continue
		}
		price := decimal.NewFromFloat(priceFloat)

		volume := usdToSell.DivRound(price, 8)
		asset := model.Asset(symbol)
		pair := asset.TradingPair()

		result, err := e.krakenService.ExecuteOrder(pair, "market", "sell", volume)
		e.logOrderResult(result, err, actionLog, symbol, volume, usdToSell, "SELL")

		if err == nil && result.Success {
			projectedCash = projectedCash.Add(usdToSell)
			executedSells = true
		}
	}

	actualCash := projectedCash
	if executedSells && !settings.DryRun {
		actualCash = e.refreshUsdBalanceAfterSells(projectedCash)
	}

	for _, symbol := range sortedKeys(buyOrders) {
		originalCost := buyOrders[symbol]
		cost := originalCost

		if cost.GreaterThan(actualCash) {
			slog.Warn("Insufficient cash for buy — reducing order",
				"symbol", symbol,
				"cost", cost.Round(2),
				"cash", actualCash.Round(2),
			)
			cost = actualCash.Mul(decimal.NewFromFloat(0.99))
		}

		if cost.LessThan(settings.DustThresholdUSD) {
			slog.Debug("Skipping dust buy", "symbol", symbol, "amount", cost.Round(2))
			*actionLog = append(*actionLog, fmt.Sprintf("Skipping dust buy for %s ($%s)", symbol, cost.Round(2).String()))
			continue
		}

		priceFloat, exists := prices[symbol]
		if !exists || priceFloat <= 0 {
			continue
		}
		price := decimal.NewFromFloat(priceFloat)

		volume := cost.DivRound(price, 8)
		asset := model.Asset(symbol)
		pair := asset.TradingPair()

		result, err := e.krakenService.ExecuteOrder(pair, "market", "buy", volume)
		e.logOrderResult(result, err, actionLog, symbol, volume, cost, "BUY")

		if err == nil && result.Success {
			actualCash = actualCash.Sub(cost)
		}
	}
}

func (e *OrderExecutor) refreshUsdBalanceAfterSells(projectedCash decimal.Decimal) decimal.Decimal {
	const maxAttempts = 3
	delayDuration := 250 * time.Millisecond
	bestCash := projectedCash

	for attempt := range maxAttempts {
		time.Sleep(delayDuration)
		updatedBalances, err := e.krakenService.GetBalances()
		if err != nil {
			slog.Warn("Failed to fetch updated USD balance", "attempt", attempt+1, "error", err)
			continue
		}

		if len(updatedBalances) > 0 {
			usdBalanceFloat := e.analyzer.ResolveBalance("USD", updatedBalances)
			if usdBalanceFloat > 0 {
				bestCash = decimal.NewFromFloat(usdBalanceFloat)
				slog.Info("Updated USD balance after sells", "attempt", attempt+1, "balance", bestCash.Round(2))
				threshold := projectedCash.Mul(decimal.NewFromFloat(0.95))
				if bestCash.GreaterThanOrEqual(threshold) {
					return bestCash
				}
			}
		}
	}

	slog.Warn("Using best observed USD balance after sell refresh", "balance", bestCash.Round(2))
	return bestCash
}

func (e *OrderExecutor) logOrderResult(
	result model.OrderResult,
	err error,
	actionLog *[]string,
	symbol string,
	volume decimal.Decimal,
	usdAmount decimal.Decimal,
	side string,
) {
	if err == nil && result.Success {
		prefix := ""
		if result.DryRun {
			prefix = "[DRY RUN] "
		}
		if side == "SELL" {
			*actionLog = append(*actionLog, fmt.Sprintf("%sSELL %s Volume: %s Value: $%s", prefix, symbol, volume.String(), usdAmount.Round(2).String()))
		} else {
			*actionLog = append(*actionLog, fmt.Sprintf("%sBUY %s Volume: %s Cost: $%s", prefix, symbol, volume.String(), usdAmount.Round(2).String()))
		}
	} else {
		errMsg := "Unknown error"
		if err != nil {
			errMsg = err.Error()
		} else if result.ErrorMessage != "" {
			errMsg = result.ErrorMessage
		}
		*actionLog = append(*actionLog, fmt.Sprintf("FAILED %s %s: %s", side, symbol, errMsg))
	}
}

// sortedKeys returns the keys of a map in sorted order for deterministic iteration.
func sortedKeys(m map[string]decimal.Decimal) []string {
	keys := make([]string, 0, len(m))
	for k := range m {
		keys = append(keys, k)
	}
	sort.Strings(keys)
	return keys
}
