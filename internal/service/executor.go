package service

import (
	"fmt"
	"log"
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

	// Iterate sell orders in sorted key order for deterministic log output and testing
	var sellSymbols []string
	for sym := range sellOrders {
		sellSymbols = append(sellSymbols, sym)
	}
	sort.Strings(sellSymbols)

	for _, symbol := range sellSymbols {
		usdToSell := sellOrders[symbol]
		dustThreshold := decimal.NewFromFloat(settings.DustThresholdUSD)
		if usdToSell.LessThan(dustThreshold) {
			log.Printf("Skipping dust sell for %s ($ %s)", symbol, usdToSell.Round(2).String())
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

	// Iterate buy orders in sorted key order for deterministic log output and testing
	var buySymbols []string
	for sym := range buyOrders {
		buySymbols = append(buySymbols, sym)
	}
	sort.Strings(buySymbols)

	for _, symbol := range buySymbols {
		originalCost := buyOrders[symbol]
		cost := originalCost

		if cost.GreaterThan(actualCash) {
			log.Printf("Warning: Not enough cash to buy %s. Cost: %s, Cash: %s. Reducing.", symbol, cost.Round(2).String(), actualCash.Round(2).String())
			cost = actualCash.Mul(decimal.NewFromFloat(0.99))
		}

		dustThreshold := decimal.NewFromFloat(settings.DustThresholdUSD)
		if cost.LessThan(dustThreshold) {
			log.Printf("Skipping dust buy for %s ($ %s)", symbol, cost.Round(2).String())
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
	maxAttempts := 3
	delayDuration := 250 * time.Millisecond
	bestCash := projectedCash

	for attempt := 0; attempt < maxAttempts; attempt++ {
		time.Sleep(delayDuration)
		updatedBalances, err := e.krakenService.GetBalances()
		if err != nil {
			log.Printf("Warning: Failed to fetch updated USD balance (attempt %d): %v", attempt+1, err)
			continue
		}

		if len(updatedBalances) > 0 {
			usdBalanceFloat := e.analyzer.ResolveBalance("USD", updatedBalances)
			if usdBalanceFloat > 0 {
				bestCash = decimal.NewFromFloat(usdBalanceFloat)
				log.Printf("Updated USD balance after sells (attempt %d): $%s", attempt+1, bestCash.Round(2).String())
				threshold := projectedCash.Mul(decimal.NewFromFloat(0.95))
				if bestCash.GreaterThanOrEqual(threshold) {
					return bestCash
				}
			}
		}
	}

	log.Printf("Warning: Using best observed USD balance after sell refresh: $%s", bestCash.Round(2).String())
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
