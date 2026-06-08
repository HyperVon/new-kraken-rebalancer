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
			return model.OrderResult{Success: true, Pair: pair, Side: side, Volume: volume, DryRun: true}, nil
		},
	}
	analyzer := NewPortfolioAnalyzer(nil, nil, nil)
	executor := NewOrderExecutor(kraken, analyzer)

	buyOrders := map[string]decimal.Decimal{
		"BTC": d(10.0),
		"ETH": d(0.5),
	}
	sellOrders := map[string]decimal.Decimal{
		"LTC": d(8.0),
		"XRP": d(0.4),
	}
	currentValuesUSD := map[string]decimal.Decimal{
		"USD": d(100.0),
	}
	prices := RawPrices{
		"BTC": 50000.0,
		"LTC": 100.0,
		"ETH": 3000.0,
		"XRP": 1.00,
	}

	settings := config.Settings{
		DustThresholdUSD: d(1.00),
		DryRun:           true,
	}

	var actionLog []string
	executor.ExecuteOrders(buyOrders, sellOrders, currentValuesUSD, prices, settings, &actionLog)

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
			return model.OrderResult{Success: true, Pair: pair, Side: side, Volume: volume}, nil
		},
		GetBalancesFunc: func() (RawBalances, error) {
			return RawBalances{"ZUSD": 108.0}, nil
		},
	}
	analyzer := NewPortfolioAnalyzer(nil, nil, nil)
	executor := NewOrderExecutor(kraken, analyzer)

	buyOrders := map[string]decimal.Decimal{
		"BTC": d(10.0),
	}
	sellOrders := map[string]decimal.Decimal{
		"LTC": d(8.0),
	}
	currentValuesUSD := map[string]decimal.Decimal{
		"USD": d(100.0),
	}
	prices := RawPrices{
		"BTC": 50000.0,
		"LTC": 100.0,
	}

	settings := config.Settings{
		DustThresholdUSD: d(1.00),
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
			return model.OrderResult{Success: true, Pair: pair, Side: side, Volume: volume}, nil
		},
	}
	analyzer := NewPortfolioAnalyzer(nil, nil, nil)
	executor := NewOrderExecutor(kraken, analyzer)

	buyOrders := map[string]decimal.Decimal{
		"BTC": d(200.0),
	}
	sellOrders := map[string]decimal.Decimal{
		"LTC": d(50.0),
	}
	currentValuesUSD := map[string]decimal.Decimal{
		"USD": d(100.0),
	}
	prices := RawPrices{
		"BTC": 50000.0,
		"LTC": 100.0,
	}

	settings := config.Settings{
		DustThresholdUSD: d(1.00),
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
	analyzer := NewPortfolioAnalyzer(nil, nil, nil)

	callCount1 := 0
	kraken1 := &MockExecutorKrakenService{
		GetBalancesFunc: func() (RawBalances, error) {
			callCount1++
			return RawBalances{"ZUSD": 98.0}, nil
		},
	}
	executor1 := NewOrderExecutor(kraken1, analyzer)
	cash1 := executor1.refreshUsdBalanceAfterSells(d(100.0))
	if callCount1 != 1 {
		t.Errorf("Expected 1 call, got %d", callCount1)
	}
	if !cash1.Equal(d(98.0)) {
		t.Errorf("Expected 98.0 cash, got %v", cash1)
	}

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
	cash2 := executor2.refreshUsdBalanceAfterSells(d(100.0))
	if callCount2 != 2 {
		t.Errorf("Expected 2 calls, got %d", callCount2)
	}
	if !cash2.Equal(d(99.0)) {
		t.Errorf("Expected 99.0 cash, got %v", cash2)
	}

	callCount3 := 0
	kraken3 := &MockExecutorKrakenService{
		GetBalancesFunc: func() (RawBalances, error) {
			callCount3++
			if callCount3 == 1 {
				return RawBalances{"ZUSD": 90.0}, nil
			}
			if callCount3 == 2 {
				return RawBalances{"ZUSD": 92.0}, nil
			}
			return RawBalances{"ZUSD": 91.0}, nil
		},
	}
	executor3 := NewOrderExecutor(kraken3, analyzer)
	cash3 := executor3.refreshUsdBalanceAfterSells(d(100.0))
	if callCount3 != 3 {
		t.Errorf("Expected 3 calls, got %d", callCount3)
	}
	if !cash3.Equal(d(91.0)) {
		t.Errorf("Expected 91.0 cash, got %v", cash3)
	}
}

func TestExecuteOrders_MissingPrices(t *testing.T) {
	kraken := &MockExecutorKrakenService{}
	analyzer := NewPortfolioAnalyzer(nil, nil, nil)
	executor := NewOrderExecutor(kraken, analyzer)

	buyOrders := map[string]decimal.Decimal{
		"BTC": d(10.0),
	}
	sellOrders := map[string]decimal.Decimal{
		"LTC": d(8.0),
	}
	currentValuesUSD := map[string]decimal.Decimal{
		"USD": d(100.0),
	}

	prices := RawPrices{}

	settings := config.Settings{
		DustThresholdUSD: d(1.00),
		DryRun:           true,
	}

	var actionLog []string
	executor.ExecuteOrders(buyOrders, sellOrders, currentValuesUSD, prices, settings, &actionLog)

	if len(actionLog) != 0 {
		t.Errorf("Expected no order logging for missing prices, got %v", actionLog)
	}
}
