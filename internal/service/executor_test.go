package service

import (
	"errors"
	"testing"

	"github.com/HyperVon/new-kraken-rebalancer/internal/config"
	"github.com/HyperVon/new-kraken-rebalancer/internal/model"
	"github.com/shopspring/decimal"
)

type MockExecutorKrakenService struct {
	ExecuteOrderFunc func(pair, orderType, side string, volume decimal.Decimal) (model.OrderResult, error)
	GetBalancesFunc  func() (RawBalances, error)
}

func (m *MockExecutorKrakenService) GetBalances() (RawBalances, error) {
	if m.GetBalancesFunc != nil {
		return m.GetBalancesFunc()
	}
	return RawBalances{}, nil
}

func (m *MockExecutorKrakenService) GetTickerPrices(pairs string) (RawPrices, error) {
	return RawPrices{}, nil
}

func (m *MockExecutorKrakenService) ExecuteOrder(pair, orderType, side string, volume decimal.Decimal) (model.OrderResult, error) {
	if m.ExecuteOrderFunc != nil {
		return m.ExecuteOrderFunc(pair, orderType, side, volume)
	}
	return model.OrderResult{}, nil
}

func TestExecuteOrders_DryRunAndDust(t *testing.T) {
	kraken := &MockExecutorKrakenService{
		ExecuteOrderFunc: func(pair, orderType, side string, volume decimal.Decimal) (model.OrderResult, error) {
			return model.NewOrderResult(true, pair, side, volume, true, ""), nil
		},
	}
	analyzer := NewPortfolioAnalyzer(nil, nil, nil)
	executor := NewOrderExecutor(kraken, analyzer)

	buyOrders := map[string]decimal.Decimal{
		"BTC": decimal.NewFromFloat(10.0), // Above dust
		"ETH": decimal.NewFromFloat(0.5),  // Dust!
	}
	sellOrders := map[string]decimal.Decimal{
		"LTC": decimal.NewFromFloat(8.0),  // Above dust
		"XRP": decimal.NewFromFloat(0.4),  // Dust!
	}
	currentValuesUSD := map[string]decimal.Decimal{
		"USD": decimal.NewFromFloat(100.0),
	}
	prices := RawPrices{
		"BTC": 50000.0,
		"LTC": 100.0,
		"ETH": 3000.0,
		"XRP": 1.00,
	}

	settings := config.Settings{
		DustThresholdUSD: 1.00,
		DryRun:           true,
	}

	var actionLog []string
	executor.ExecuteOrders(buyOrders, sellOrders, currentValuesUSD, prices, settings, &actionLog)

	// Verify skipping dust and executing normal ones
	expectedLogs := []string{
		"Skipping dust sell for XRP ($0.4)",
		"[DRY RUN] SELL LTC Volume: 0.08 Value: $8",
		"Skipping dust buy for ETH ($0.5)",
		"[DRY RUN] BUY BTC Volume: 0.0002 Cost: $10",
	}

	for _, expected := range expectedLogs {
		found := false
		for _, logMsg := range actionLog {
			if logMsg == expected {
				found = true
				break
			}
		}
		if !found {
			t.Errorf("Expected log message not found: %s. Action logs: %v", expected, actionLog)
		}
	}
}

func TestExecuteOrders_LiveExecutionOrder(t *testing.T) {
	kraken := &MockExecutorKrakenService{
		ExecuteOrderFunc: func(pair, orderType, side string, volume decimal.Decimal) (model.OrderResult, error) {
			// Live call success
			return model.NewOrderResult(true, pair, side, volume, false, ""), nil
		},
		GetBalancesFunc: func() (RawBalances, error) {
			return RawBalances{"ZUSD": 108.0}, nil
		},
	}
	analyzer := NewPortfolioAnalyzer(nil, nil, nil)
	executor := NewOrderExecutor(kraken, analyzer)

	buyOrders := map[string]decimal.Decimal{
		"BTC": decimal.NewFromFloat(10.0),
	}
	sellOrders := map[string]decimal.Decimal{
		"LTC": decimal.NewFromFloat(8.0),
	}
	currentValuesUSD := map[string]decimal.Decimal{
		"USD": decimal.NewFromFloat(100.0),
	}
	prices := RawPrices{
		"BTC": 50000.0,
		"LTC": 100.0,
	}

	settings := config.Settings{
		DustThresholdUSD: 1.00,
		DryRun:           false,
	}

	var actionLog []string
	executor.ExecuteOrders(buyOrders, sellOrders, currentValuesUSD, prices, settings, &actionLog)

	expectedLogs := []string{
		"SELL LTC Volume: 0.08 Value: $8",
		"BUY BTC Volume: 0.0002 Cost: $10",
	}

	for _, expected := range expectedLogs {
		found := false
		for _, logMsg := range actionLog {
			if logMsg == expected {
				found = true
				break
			}
		}
		if !found {
			t.Errorf("Expected action log '%s', but got %v", expected, actionLog)
		}
	}
}

func TestExecuteOrders_ReduceCashAndFailures(t *testing.T) {
	kraken := &MockExecutorKrakenService{
		ExecuteOrderFunc: func(pair, orderType, side string, volume decimal.Decimal) (model.OrderResult, error) {
			if side == "sell" {
				return model.OrderResult{}, errors.New("execution order failed")
			}
			return model.NewOrderResult(true, pair, side, volume, false, ""), nil
		},
	}
	analyzer := NewPortfolioAnalyzer(nil, nil, nil)
	executor := NewOrderExecutor(kraken, analyzer)

	buyOrders := map[string]decimal.Decimal{
		"BTC": decimal.NewFromFloat(200.0), // Exceeds USD balance of 100.0!
	}
	sellOrders := map[string]decimal.Decimal{
		"LTC": decimal.NewFromFloat(50.0),
	}
	currentValuesUSD := map[string]decimal.Decimal{
		"USD": decimal.NewFromFloat(100.0),
	}
	prices := RawPrices{
		"BTC": 50000.0,
		"LTC": 100.0,
	}

	settings := config.Settings{
		DustThresholdUSD: 1.00,
		DryRun:           false,
	}

	var actionLog []string
	executor.ExecuteOrders(buyOrders, sellOrders, currentValuesUSD, prices, settings, &actionLog)

	expectedLogs := []string{
		"FAILED SELL LTC: execution order failed",
		"BUY BTC Volume: 0.00198 Cost: $99",
	}

	for _, expected := range expectedLogs {
		found := false
		for _, logMsg := range actionLog {
			if logMsg == expected {
				found = true
				break
			}
		}
		if !found {
			t.Errorf("Expected action log '%s', but got %v", expected, actionLog)
		}
	}
}

func TestRefreshUsdBalanceAfterSells_Attempts(t *testing.T) {
	// Setup executor
	analyzer := NewPortfolioAnalyzer(nil, nil, nil)

	// Case 1: First attempt succeeds and is >= threshold
	callCount1 := 0
	kraken1 := &MockExecutorKrakenService{
		GetBalancesFunc: func() (RawBalances, error) {
			callCount1++
			return RawBalances{"ZUSD": 98.0}, nil // 98 >= 95 (projected 100 * 0.95)
		},
	}
	executor1 := NewOrderExecutor(kraken1, analyzer)
	cash1 := executor1.refreshUsdBalanceAfterSells(decimal.NewFromFloat(100.0))
	if callCount1 != 1 {
		t.Errorf("Expected 1 call, got %d", callCount1)
	}
	if !cash1.Equal(decimal.NewFromFloat(98.0)) {
		t.Errorf("Expected 98.0 cash, got %v", cash1)
	}

	// Case 2: First attempt fails, second attempt succeeds
	callCount2 := 0
	kraken2 := &MockExecutorKrakenService{
		GetBalancesFunc: func() (RawBalances, error) {
			callCount2++
			if callCount2 == 1 {
				return nil, errors.New("temporary connection error")
			}
			return RawBalances{"ZUSD": 99.0}, nil
		},
	}
	executor2 := NewOrderExecutor(kraken2, analyzer)
	cash2 := executor2.refreshUsdBalanceAfterSells(decimal.NewFromFloat(100.0))
	if callCount2 != 2 {
		t.Errorf("Expected 2 calls, got %d", callCount2)
	}
	if !cash2.Equal(decimal.NewFromFloat(99.0)) {
		t.Errorf("Expected 99.0 cash, got %v", cash2)
	}

	// Case 3: All attempts fail or return below threshold (keeps last observed)
	callCount3 := 0
	kraken3 := &MockExecutorKrakenService{
		GetBalancesFunc: func() (RawBalances, error) {
			callCount3++
			if callCount3 == 1 {
				return RawBalances{"ZUSD": 90.0}, nil // below threshold (95)
			}
			if callCount3 == 2 {
				return RawBalances{"ZUSD": 92.0}, nil // below threshold (95)
			}
			return RawBalances{"ZUSD": 91.0}, nil
		},
	}
	executor3 := NewOrderExecutor(kraken3, analyzer)
	cash3 := executor3.refreshUsdBalanceAfterSells(decimal.NewFromFloat(100.0))
	if callCount3 != 3 {
		t.Errorf("Expected 3 calls, got %d", callCount3)
	}
	if !cash3.Equal(decimal.NewFromFloat(91.0)) {
		t.Errorf("Expected 91.0 cash, got %v", cash3)
	}
}

func TestExecuteOrders_MissingPrices(t *testing.T) {
	kraken := &MockExecutorKrakenService{}
	analyzer := NewPortfolioAnalyzer(nil, nil, nil)
	executor := NewOrderExecutor(kraken, analyzer)

	buyOrders := map[string]decimal.Decimal{
		"BTC": decimal.NewFromFloat(10.0),
	}
	sellOrders := map[string]decimal.Decimal{
		"LTC": decimal.NewFromFloat(8.0),
	}
	currentValuesUSD := map[string]decimal.Decimal{
		"USD": decimal.NewFromFloat(100.0),
	}

	// Missing prices (no prices maps matching BTC/LTC)
	prices := RawPrices{}

	settings := config.Settings{
		DustThresholdUSD: 1.00,
		DryRun:           true,
	}

	var actionLog []string
	executor.ExecuteOrders(buyOrders, sellOrders, currentValuesUSD, prices, settings, &actionLog)

	if len(actionLog) != 0 {
		t.Errorf("Expected no order logging for missing prices, got %v", actionLog)
	}
}
