package service

import (
	"context"
	"errors"
	"strings"
	"testing"
	"time"

	"github.com/HyperVon/new-kraken-rebalancer/internal/config"
	"github.com/HyperVon/new-kraken-rebalancer/internal/model"
	"github.com/shopspring/decimal"
)

// FakeKrakenService implements KrakenService with suppliers for testing.
type FakeKrakenService struct {
	getBalancesCallCount int
	balanceSupplier      func() (RawBalances, error)
	pricesSupplier       func(pairs string) (RawPrices, error)
	executedOrders       []struct {
		Pair      string
		OrderType string
		Side      string
		Volume    decimal.Decimal
	}
	orderResultFactory func(pair, orderType, side string, volume decimal.Decimal) (model.OrderResult, error)
}

func (f *FakeKrakenService) GetBalances() (RawBalances, error) {
	f.getBalancesCallCount++
	if f.balanceSupplier != nil {
		return f.balanceSupplier()
	}
	return RawBalances{}, nil
}

func (f *FakeKrakenService) GetTickerPrices(pairs string) (RawPrices, error) {
	if f.pricesSupplier != nil {
		return f.pricesSupplier(pairs)
	}
	return RawPrices{}, nil
}

func (f *FakeKrakenService) ExecuteOrder(pair, orderType, side string, volume decimal.Decimal) (model.OrderResult, error) {
	f.executedOrders = append(f.executedOrders, struct {
		Pair      string
		OrderType string
		Side      string
		Volume    decimal.Decimal
	}{
		Pair:      pair,
		OrderType: orderType,
		Side:      side,
		Volume:    volume,
	})

	if f.orderResultFactory != nil {
		return f.orderResultFactory(pair, orderType, side, volume)
	}

	return model.OrderResult{Success: true, Pair: pair, Side: side, Volume: volume}, nil
}

func TestPortfolioManager_DogeMapping(t *testing.T) {
	settings := config.Settings{
		LoopDelaySeconds:        60,
		DeviationTriggerPercent: d(2.0),
		DustThresholdUSD:        d(1.0),
		DryRun:                  true,
	}
	cfg := config.AppConfig{
		Kraken:   config.KrakenCredentials{APIKey: "k", PrivateKey: "s"},
		Settings: settings,
		Allocations: []config.Allocation{
			{Symbol: "DOGE", TargetPercent: d(50.0)},
			{Symbol: "USD", TargetPercent: d(50.0)},
		},
	}
	cfgService := &MockConfigService{cfg: cfg}

	fakeKraken := &FakeKrakenService{
		balanceSupplier: func() (RawBalances, error) {
			return RawBalances{"XDG": 1000.0, "ZUSD": 500.0}, nil
		},
		pricesSupplier: func(pairs string) (RawPrices, error) {
			if strings.Contains(pairs, "XDGUSD") {
				return RawPrices{"XDGUSD": 0.10}, nil
			}
			return RawPrices{}, nil
		},
	}

	statsRepo := &MockStatsRepo{}
	analyzer := NewPortfolioAnalyzer(fakeKraken, cfgService, statsRepo)
	executor := NewOrderExecutor(fakeKraken, analyzer)
	mgr := NewPortfolioManagerImpl(cfgService, &MockHistoryService{}, analyzer, executor)

	err := mgr.PerformRebalanceCycle()
	if err != nil {
		t.Fatalf("Unexpected cycle error: %v", err)
	}

	if fakeKraken.getBalancesCallCount != 1 {
		t.Errorf("Expected GetBalances to be called 1 time, got %d", fakeKraken.getBalancesCallCount)
	}
}

func TestPortfolioManager_BtcMapping(t *testing.T) {
	settings := config.Settings{
		LoopDelaySeconds:        60,
		DeviationTriggerPercent: d(2.0),
		DustThresholdUSD:        d(1.0),
		DryRun:                  true,
	}
	cfg := config.AppConfig{
		Kraken:   config.KrakenCredentials{APIKey: "k", PrivateKey: "s"},
		Settings: settings,
		Allocations: []config.Allocation{
			{Symbol: "BTC", TargetPercent: d(50.0)},
			{Symbol: "USD", TargetPercent: d(50.0)},
		},
	}
	cfgService := &MockConfigService{cfg: cfg}

	fakeKraken := &FakeKrakenService{
		balanceSupplier: func() (RawBalances, error) {
			return RawBalances{"XXBT": 1.0, "ZUSD": 50000.0}, nil
		},
		pricesSupplier: func(pairs string) (RawPrices, error) {
			if strings.Contains(pairs, "XXBTZUSD") || strings.Contains(pairs, "XBTUSD") {
				return RawPrices{"XXBTZUSD": 50000.0}, nil
			}
			return RawPrices{}, nil
		},
	}

	statsRepo := &MockStatsRepo{}
	analyzer := NewPortfolioAnalyzer(fakeKraken, cfgService, statsRepo)
	executor := NewOrderExecutor(fakeKraken, analyzer)
	mgr := NewPortfolioManagerImpl(cfgService, &MockHistoryService{}, analyzer, executor)

	err := mgr.PerformRebalanceCycle()
	if err != nil {
		t.Fatalf("Unexpected cycle error: %v", err)
	}

	if fakeKraken.getBalancesCallCount != 1 {
		t.Errorf("Expected GetBalances to be called 1 time, got %d", fakeKraken.getBalancesCallCount)
	}
}

func TestPortfolioManager_ZeroAllocation(t *testing.T) {
	cfg := config.AppConfig{
		Settings: config.Settings{
			LoopDelaySeconds:        0,
			DeviationTriggerPercent: d(2.0),
			DustThresholdUSD:        d(1.0),
			DryRun:                  false,
		},
		Allocations: []config.Allocation{
			{Symbol: "A", TargetPercent: d(0.0)},
			{Symbol: "B", TargetPercent: d(100.0)},
		},
	}
	cfgService := &MockConfigService{cfg: cfg}

	fakeKraken := &FakeKrakenService{
		balanceSupplier: func() (RawBalances, error) {
			return RawBalances{"A": 10.0, "B": 0.0, "ZUSD": 100.0}, nil
		},
		pricesSupplier: func(pairs string) (RawPrices, error) {
			return RawPrices{"AUSD": 100.0, "BUSD": 50.0}, nil
		},
	}

	statsRepo := &MockStatsRepo{}
	analyzer := NewPortfolioAnalyzer(fakeKraken, cfgService, statsRepo)
	executor := NewOrderExecutor(fakeKraken, analyzer)
	mgr := NewPortfolioManagerImpl(cfgService, &MockHistoryService{}, analyzer, executor)

	err := mgr.PerformRebalanceCycle()
	if err != nil {
		t.Fatalf("Unexpected cycle error: %v", err)
	}

	found := false
	for _, o := range fakeKraken.executedOrders {
		if o.Pair == "AUSD" && o.OrderType == "market" && o.Side == "sell" {
			found = true
			break
		}
	}
	if !found {
		t.Error("Expected AUSD sell order but none was executed")
	}
}

func TestPortfolioManager_DrawdownAndFiatDeployment(t *testing.T) {
	statsRepo := &MockStatsRepo{stats: model.PortfolioStats{AllTimeHigh: d(2000.0)}}
	cfg := config.AppConfig{
		Settings: config.Settings{
			LoopDelaySeconds:        60,
			DeviationTriggerPercent: d(2.0),
			DustThresholdUSD:        d(1.0),
			DryRun:                  false,
			FiatMaxDrawdown:         d(50.0),
			FiatDeploymentExponent:  d(1.0),
		},
		Allocations: []config.Allocation{
			{Symbol: "A", TargetPercent: d(50.0)},
			{Symbol: "USD", TargetPercent: d(50.0)},
		},
	}
	cfgService := &MockConfigService{cfg: cfg}

	fakeKraken := &FakeKrakenService{
		balanceSupplier: func() (RawBalances, error) {
			return RawBalances{"A": 7.5, "ZUSD": 750.0}, nil
		},
		pricesSupplier: func(pairs string) (RawPrices, error) {
			return RawPrices{"AUSD": 100.0}, nil
		},
	}

	analyzer := NewPortfolioAnalyzer(fakeKraken, cfgService, statsRepo)
	executor := NewOrderExecutor(fakeKraken, analyzer)
	history := &MockHistoryService{}
	mgr := NewPortfolioManagerImpl(cfgService, history, analyzer, executor)

	err := mgr.PerformRebalanceCycle()
	if err != nil {
		t.Fatalf("Unexpected cycle error: %v", err)
	}

	if len(fakeKraken.executedOrders) != 1 {
		t.Fatalf("Expected 1 order executed, got %d", len(fakeKraken.executedOrders))
	}
	order := fakeKraken.executedOrders[0]
	if order.Pair != "AUSD" || order.Side != "buy" || order.OrderType != "market" {
		t.Errorf("Unexpected order executed: %+v", order)
	}
	expectedVol := d(3.75)
	if !order.Volume.Sub(expectedVol).Abs().LessThan(d(0.01)) {
		t.Errorf("Expected volume around 3.75, got %v", order.Volume)
	}

	if len(history.snapshots) != 1 {
		t.Fatalf("Expected 1 snapshot, got %d", len(history.snapshots))
	}
	snap := history.snapshots[0]
	if !snap.DrawdownPercent.Equal(d(25.0)) {
		t.Errorf("Expected drawdown 25.0, got %v", snap.DrawdownPercent)
	}
	if !snap.FiatDeploymentPercent.Equal(d(50.0)) {
		t.Errorf("Expected deployment 50.0, got %v", snap.FiatDeploymentPercent)
	}
	if !snap.EffectiveUsdTargetPercent.Equal(d(25.0)) {
		t.Errorf("Expected effective USD target 25.0, got %v", snap.EffectiveUsdTargetPercent)
	}
}

func TestPortfolioManager_NewATH(t *testing.T) {
	statsRepo := &MockStatsRepo{stats: model.PortfolioStats{AllTimeHigh: d(1000.0)}}
	cfg := config.AppConfig{
		Settings: config.Settings{
			LoopDelaySeconds:        60,
			DeviationTriggerPercent: d(2.0),
			DustThresholdUSD:        d(1.0),
			DryRun:                  false,
			FiatMaxDrawdown:         d(50.0),
			FiatDeploymentExponent:  d(1.0),
		},
		Allocations: []config.Allocation{
			{Symbol: "USD", TargetPercent: d(100.0)},
		},
	}
	cfgService := &MockConfigService{cfg: cfg}

	fakeKraken := &FakeKrakenService{
		balanceSupplier: func() (RawBalances, error) {
			return RawBalances{"ZUSD": 1500.0}, nil
		},
		pricesSupplier: func(pairs string) (RawPrices, error) {
			return RawPrices{}, nil
		},
	}

	analyzer := NewPortfolioAnalyzer(fakeKraken, cfgService, statsRepo)
	executor := NewOrderExecutor(fakeKraken, analyzer)
	mgr := NewPortfolioManagerImpl(cfgService, &MockHistoryService{}, analyzer, executor)

	err := mgr.PerformRebalanceCycle()
	if err != nil {
		t.Fatalf("Unexpected cycle error: %v", err)
	}

	stats, _ := statsRepo.Load()
	if !stats.AllTimeHigh.Equal(d(1500.0)) {
		t.Errorf("Expected new ATH 1500.0, got %v", stats.AllTimeHigh)
	}
}

func TestPortfolioManager_SellsBeforeBuys(t *testing.T) {
	cfg := config.AppConfig{
		Settings: config.Settings{
			LoopDelaySeconds:        0,
			DeviationTriggerPercent: d(1.0),
			DustThresholdUSD:        d(1.0),
			DryRun:                  false,
		},
		Allocations: []config.Allocation{
			{Symbol: "A", TargetPercent: d(10.0)},
			{Symbol: "B", TargetPercent: d(90.0)},
			{Symbol: "USD", TargetPercent: d(0.0)},
		},
	}
	cfgService := &MockConfigService{cfg: cfg}

	fakeKraken := &FakeKrakenService{
		balanceSupplier: func() (RawBalances, error) {
			return RawBalances{"A": 5.0, "B": 50.0, "ZUSD": 0.0}, nil
		},
		pricesSupplier: func(pairs string) (RawPrices, error) {
			return RawPrices{"AUSD": 100.0, "BUSD": 10.0}, nil
		},
	}

	statsRepo := &MockStatsRepo{}
	analyzer := NewPortfolioAnalyzer(fakeKraken, cfgService, statsRepo)
	executor := NewOrderExecutor(fakeKraken, analyzer)
	mgr := NewPortfolioManagerImpl(cfgService, &MockHistoryService{}, analyzer, executor)

	err := mgr.PerformRebalanceCycle()
	if err != nil {
		t.Fatalf("Unexpected cycle error: %v", err)
	}

	if len(fakeKraken.executedOrders) != 2 {
		t.Fatalf("Expected 2 orders, got %d", len(fakeKraken.executedOrders))
	}
	if fakeKraken.executedOrders[0].Pair != "AUSD" || fakeKraken.executedOrders[0].Side != "sell" {
		t.Errorf("Expected first order to be AUSD sell, got: %+v", fakeKraken.executedOrders[0])
	}
	if fakeKraken.executedOrders[1].Pair != "BUSD" || fakeKraken.executedOrders[1].Side != "buy" {
		t.Errorf("Expected second order to be BUSD buy, got: %+v", fakeKraken.executedOrders[1])
	}
}

func TestPortfolioManager_SkipDustSells(t *testing.T) {
	cfg := config.AppConfig{
		Settings: config.Settings{
			LoopDelaySeconds:        0,
			DeviationTriggerPercent: d(0.1),
			DustThresholdUSD:        d(10.0),
			DryRun:                  false,
		},
		Allocations: []config.Allocation{
			{Symbol: "A", TargetPercent: d(10.0)},
			{Symbol: "USD", TargetPercent: d(90.0)},
		},
	}
	cfgService := &MockConfigService{cfg: cfg}

	fakeKraken := &FakeKrakenService{
		balanceSupplier: func() (RawBalances, error) {
			return RawBalances{"A": 1.05, "ZUSD": 895.0}, nil
		},
		pricesSupplier: func(pairs string) (RawPrices, error) {
			return RawPrices{"AUSD": 100.0}, nil
		},
	}

	statsRepo := &MockStatsRepo{}
	analyzer := NewPortfolioAnalyzer(fakeKraken, cfgService, statsRepo)
	executor := NewOrderExecutor(fakeKraken, analyzer)
	mgr := NewPortfolioManagerImpl(cfgService, &MockHistoryService{}, analyzer, executor)

	err := mgr.PerformRebalanceCycle()
	if err != nil {
		t.Fatalf("Unexpected cycle error: %v", err)
	}

	for _, o := range fakeKraken.executedOrders {
		if o.Pair == "AUSD" && o.Side == "sell" {
			t.Error("Expected AUSD sell order to be skipped due to dust threshold")
		}
	}
}

func TestPortfolioManager_CashVerificationFallback(t *testing.T) {
	cfg := config.AppConfig{
		Settings: config.Settings{
			LoopDelaySeconds:        0,
			DeviationTriggerPercent: d(1.0),
			DustThresholdUSD:        d(1.0),
			DryRun:                  false,
		},
		Allocations: []config.Allocation{
			{Symbol: "A", TargetPercent: d(10.0)},
			{Symbol: "B", TargetPercent: d(90.0)},
		},
	}
	cfgService := &MockConfigService{cfg: cfg}

	initialBalances := RawBalances{"A": 5.0, "B": 50.0, "ZUSD": 0.0}
	callCount := 0

	fakeKraken := &FakeKrakenService{
		balanceSupplier: func() (RawBalances, error) {
			callCount++
			if callCount == 1 {
				return initialBalances, nil
			}
			return nil, errors.New("API Error during verification!")
		},
		pricesSupplier: func(pairs string) (RawPrices, error) {
			return RawPrices{"AUSD": 100.0, "BUSD": 10.0}, nil
		},
	}

	statsRepo := &MockStatsRepo{}
	analyzer := NewPortfolioAnalyzer(fakeKraken, cfgService, statsRepo)
	executor := NewOrderExecutor(fakeKraken, analyzer)
	mgr := NewPortfolioManagerImpl(cfgService, &MockHistoryService{}, analyzer, executor)

	err := mgr.PerformRebalanceCycle()
	if err != nil {
		t.Fatalf("Unexpected cycle error: %v", err)
	}

	if len(fakeKraken.executedOrders) != 2 {
		t.Errorf("Expected 2 orders executed, got %d", len(fakeKraken.executedOrders))
	}
}

func TestPortfolioManager_PartialFillCashUpdate(t *testing.T) {
	cfg := config.AppConfig{
		Settings: config.Settings{
			LoopDelaySeconds:        0,
			DeviationTriggerPercent: d(1.0),
			DustThresholdUSD:        d(1.0),
			DryRun:                  false,
		},
		Allocations: []config.Allocation{
			{Symbol: "A", TargetPercent: d(10.0)},
			{Symbol: "B", TargetPercent: d(90.0)},
		},
	}
	cfgService := &MockConfigService{cfg: cfg}

	initialBalances := RawBalances{"A": 5.0, "B": 50.0, "ZUSD": 0.0}
	updatedBalances := RawBalances{"A": 2.0, "B": 50.0, "ZUSD": 200.0}
	callCount := 0

	fakeKraken := &FakeKrakenService{
		balanceSupplier: func() (RawBalances, error) {
			callCount++
			if callCount == 1 {
				return initialBalances, nil
			}
			return updatedBalances, nil
		},
		pricesSupplier: func(pairs string) (RawPrices, error) {
			return RawPrices{"AUSD": 100.0, "BUSD": 10.0}, nil
		},
	}

	statsRepo := &MockStatsRepo{}
	analyzer := NewPortfolioAnalyzer(fakeKraken, cfgService, statsRepo)
	executor := NewOrderExecutor(fakeKraken, analyzer)
	mgr := NewPortfolioManagerImpl(cfgService, &MockHistoryService{}, analyzer, executor)

	err := mgr.PerformRebalanceCycle()
	if err != nil {
		t.Fatalf("Unexpected cycle error: %v", err)
	}

	if len(fakeKraken.executedOrders) != 2 {
		t.Fatalf("Expected 2 orders executed, got %d", len(fakeKraken.executedOrders))
	}

	buyOrder := fakeKraken.executedOrders[1]
	if buyOrder.Pair != "BUSD" || buyOrder.Side != "buy" {
		t.Errorf("Expected second order to be BUSD buy, got: %+v", buyOrder)
	}
	if !buyOrder.Volume.Sub(d(19.8)).Abs().LessThan(d(1.0)) {
		t.Errorf("Expected buy order volume around 19.8, got %v", buyOrder.Volume)
	}
}

func TestPortfolioAnalyzer_DistributeFiatCorrection_Deposit_OnlyBuysUnderweight(t *testing.T) {
	cfg := config.AppConfig{
		Allocations: []config.Allocation{
			{Symbol: "A", TargetPercent: d(50.0)},
			{Symbol: "B", TargetPercent: d(50.0)},
		},
	}
	cfgService := &MockConfigService{cfg: cfg}
	analyzer := NewPortfolioAnalyzer(nil, cfgService, nil)

	usdDev := d(100.0)
	allDevs := mapMap(map[string]float64{
		"A": 10.0,
		"B": -10.0,
	})
	buyOrders := make(map[string]decimal.Decimal)
	sellOrders := make(map[string]decimal.Decimal)
	var actionLog []string

	analyzer.distributeFiatCorrection(usdDev, allDevs, buyOrders, sellOrders, &actionLog)

	if _, ok := buyOrders["B"]; !ok {
		t.Error("Expected buy order for B (underweight)")
	}
	if _, ok := buyOrders["A"]; ok {
		t.Error("Did not expect buy order for A (overweight)")
	}
	if len(sellOrders) != 0 {
		t.Errorf("Expected no sell orders, got %d", len(sellOrders))
	}
}

func TestPortfolioAnalyzer_DistributeFiatCorrection_Withdrawal_OnlySellsOverweight(t *testing.T) {
	cfg := config.AppConfig{
		Allocations: []config.Allocation{
			{Symbol: "A", TargetPercent: d(50.0)},
			{Symbol: "B", TargetPercent: d(50.0)},
		},
	}
	cfgService := &MockConfigService{cfg: cfg}
	analyzer := NewPortfolioAnalyzer(nil, cfgService, nil)

	usdDev := d(-100.0)
	allDevs := mapMap(map[string]float64{
		"A": 10.0,
		"B": -10.0,
	})
	buyOrders := make(map[string]decimal.Decimal)
	sellOrders := make(map[string]decimal.Decimal)
	var actionLog []string

	analyzer.distributeFiatCorrection(usdDev, allDevs, buyOrders, sellOrders, &actionLog)

	if _, ok := sellOrders["A"]; !ok {
		t.Error("Expected sell order for A (overweight)")
	}
	if _, ok := sellOrders["B"]; ok {
		t.Error("Did not expect sell order for B (underweight)")
	}
	if len(buyOrders) != 0 {
		t.Errorf("Expected no buy orders, got %d", len(buyOrders))
	}
}

func TestPortfolioAnalyzer_DistributeFiatCorrection_ProportionalDistribution(t *testing.T) {
	cfg := config.AppConfig{
		Allocations: []config.Allocation{
			{Symbol: "A", TargetPercent: d(30.0)},
			{Symbol: "B", TargetPercent: d(30.0)},
			{Symbol: "C", TargetPercent: d(40.0)},
		},
	}
	cfgService := &MockConfigService{cfg: cfg}
	analyzer := NewPortfolioAnalyzer(nil, cfgService, nil)

	usdDev := d(100.0)
	allDevs := mapMap(map[string]float64{
		"A": -200.0,
		"B": -50.0,
		"C": 50.0,
	})
	buyOrders := make(map[string]decimal.Decimal)
	sellOrders := make(map[string]decimal.Decimal)
	var actionLog []string

	analyzer.distributeFiatCorrection(usdDev, allDevs, buyOrders, sellOrders, &actionLog)

	aShare := buyOrders["A"]
	bShare := buyOrders["B"]
	if !aShare.Equal(d(80.0)) {
		t.Errorf("Expected A buy order share to be 80.0, got %v", aShare)
	}
	if !bShare.Equal(d(20.0)) {
		t.Errorf("Expected B buy order share to be 20.0, got %v", bShare)
	}
}

func TestPortfolioManager_LoopRunsWhenEnabled(t *testing.T) {
	cfg := config.AppConfig{
		Settings: config.Settings{
			LoopDelaySeconds: 1,
		},
	}
	cfgService := &MockConfigService{cfg: cfg}
	fakeKraken := &FakeKrakenService{
		balanceSupplier: func() (RawBalances, error) {
			return RawBalances{"ZUSD": 100.0}, nil
		},
	}
	statsRepo := &MockStatsRepo{}
	analyzer := NewPortfolioAnalyzer(fakeKraken, cfgService, statsRepo)
	executor := NewOrderExecutor(fakeKraken, analyzer)
	mgr := NewPortfolioManagerImpl(cfgService, &MockHistoryService{}, analyzer, executor)

	mgr.StartRebalancingLoop()

	ctx, cancel := context.WithCancel(context.Background())
	go func() {
		time.Sleep(150 * time.Millisecond)
		cancel()
	}()

	mgr.RunLoop(ctx)
	mgr.StopRebalancingLoop()

	if fakeKraken.getBalancesCallCount == 0 {
		t.Error("Expected loop execution to fetch balances")
	}
}

func TestPortfolioManager_LoopStopsExecution(t *testing.T) {
	cfg := config.AppConfig{
		Settings: config.Settings{
			LoopDelaySeconds: 1,
		},
	}
	cfgService := &MockConfigService{cfg: cfg}
	fakeKraken := &FakeKrakenService{
		balanceSupplier: func() (RawBalances, error) {
			return RawBalances{"ZUSD": 100.0}, nil
		},
	}
	statsRepo := &MockStatsRepo{}
	analyzer := NewPortfolioAnalyzer(fakeKraken, cfgService, statsRepo)
	executor := NewOrderExecutor(fakeKraken, analyzer)
	mgr := NewPortfolioManagerImpl(cfgService, &MockHistoryService{}, analyzer, executor)

	mgr.StartRebalancingLoop()
	mgr.StopRebalancingLoop()

	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	mgr.RunLoop(ctx)

	if fakeKraken.getBalancesCallCount != 0 {
		t.Error("Expected zero balances call because loop was stopped")
	}
}

func TestPortfolioManager_LoopHandlesExceptionGracefully(t *testing.T) {
	cfg := config.AppConfig{
		Settings: config.Settings{
			LoopDelaySeconds: 1,
		},
	}
	cfgService := &MockConfigService{cfg: cfg}
	fakeKraken := &FakeKrakenService{
		balanceSupplier: func() (RawBalances, error) {
			return nil, errors.New("API Error!")
		},
	}
	statsRepo := &MockStatsRepo{}
	analyzer := NewPortfolioAnalyzer(fakeKraken, cfgService, statsRepo)
	executor := NewOrderExecutor(fakeKraken, analyzer)
	mgr := NewPortfolioManagerImpl(cfgService, &MockHistoryService{}, analyzer, executor)

	mgr.StartRebalancingLoop()

	ctx, cancel := context.WithCancel(context.Background())
	go func() {
		time.Sleep(100 * time.Millisecond)
		cancel()
	}()

	mgr.RunLoop(ctx)
	mgr.StopRebalancingLoop()

	if fakeKraken.getBalancesCallCount == 0 {
		t.Error("Expected loop execution to fetch balances even if it fails")
	}
}

func TestPortfolioManager_ExecuteOrders_DryRun(t *testing.T) {
	fakeKraken := &FakeKrakenService{
		orderResultFactory: func(pair, orderType, side string, volume decimal.Decimal) (model.OrderResult, error) {
			return model.OrderResult{
				Success: true,
				Pair:    pair,
				Side:    side,
				Volume:  volume,
				DryRun:  true,
			}, nil
		},
	}
	analyzer := NewPortfolioAnalyzer(fakeKraken, &MockConfigService{}, nil)
	executor := NewOrderExecutor(fakeKraken, analyzer)

	buyOrders := map[string]decimal.Decimal{"BTC": d(100.0)}
	sellOrders := map[string]decimal.Decimal{"ETH": d(200.0)}
	currentValuesUSD := map[string]decimal.Decimal{"USD": d(500.0)}
	prices := RawPrices{"BTC": 50000.0, "ETH": 2000.0}
	settings := config.Settings{
		LoopDelaySeconds: 0,
		DustThresholdUSD: d(1.0),
		DryRun:           true,
	}
	var actionLog []string

	executor.ExecuteOrders(buyOrders, sellOrders, currentValuesUSD, prices, settings, &actionLog)

	if len(fakeKraken.executedOrders) != 2 {
		t.Errorf("Expected 2 orders sent to FakeKraken in dry run, got %d", len(fakeKraken.executedOrders))
	}

	dryRunLogFound := false
	for _, log := range actionLog {
		if strings.Contains(log, "[DRY RUN]") {
			dryRunLogFound = true
		}
	}
	if !dryRunLogFound {
		t.Error("Expected dry run prefix in action logs")
	}
}

func TestPortfolioManager_ExecuteOrders_FailedSellDoesNotIncrementCash(t *testing.T) {
	fakeKraken := &FakeKrakenService{
		orderResultFactory: func(pair, orderType, side string, volume decimal.Decimal) (model.OrderResult, error) {
			if side == "sell" {
				return model.OrderResult{Success: false, ErrorMessage: "Invalid amount"}, nil
			}
			return model.OrderResult{Success: true, Pair: pair, Side: side, Volume: volume}, nil
		},
	}
	analyzer := NewPortfolioAnalyzer(fakeKraken, &MockConfigService{}, nil)
	executor := NewOrderExecutor(fakeKraken, analyzer)

	buyOrders := map[string]decimal.Decimal{"BTC": d(100.0)}
	sellOrders := map[string]decimal.Decimal{"ETH": d(200.0)}
	currentValuesUSD := map[string]decimal.Decimal{"USD": d(50.0)}
	prices := RawPrices{"BTC": 50000.0, "ETH": 2000.0}
	settings := config.Settings{
		LoopDelaySeconds: 0,
		DustThresholdUSD: d(1.0),
		DryRun:           false,
	}
	var actionLog []string

	executor.ExecuteOrders(buyOrders, sellOrders, currentValuesUSD, prices, settings, &actionLog)

	failedSellFound := false
	for _, log := range actionLog {
		if strings.Contains(log, "FAILED SELL ETH: Invalid amount") {
			failedSellFound = true
		}
	}

	if !failedSellFound {
		t.Error("Expected failed sell log entry")
	}

	if len(fakeKraken.executedOrders) != 2 {
		t.Fatalf("Expected 2 orders total, got %d", len(fakeKraken.executedOrders))
	}
	buyOrder := fakeKraken.executedOrders[1]
	expectedVol := d(0.00099)
	if !buyOrder.Volume.Equal(expectedVol) {
		t.Errorf("Expected reduced buy volume %v, got %v", expectedVol, buyOrder.Volume)
	}
}

func TestPortfolioManager_RefreshUsdBalanceAfterSells_Timeout(t *testing.T) {
	callCount := 0
	fakeKraken := &FakeKrakenService{
		balanceSupplier: func() (RawBalances, error) {
			callCount++
			return RawBalances{"USD": 900.0}, nil
		},
	}
	analyzer := NewPortfolioAnalyzer(fakeKraken, &MockConfigService{}, nil)
	executor := NewOrderExecutor(fakeKraken, analyzer)

	buyOrders := make(map[string]decimal.Decimal)
	sellOrders := map[string]decimal.Decimal{"BTC": d(100.0)}
	currentValuesUSD := map[string]decimal.Decimal{"USD": d(1000.0)}
	prices := RawPrices{"BTC": 50000.0}
	settings := config.Settings{
		LoopDelaySeconds: 0,
		DustThresholdUSD: d(1.0),
		DryRun:           false,
	}

	executor.ExecuteOrders(buyOrders, sellOrders, currentValuesUSD, prices, settings, &actionLogEmpty)

	if callCount != 3 {
		t.Errorf("Expected refresh polling to hit max attempts (3), got %d calls", callCount)
	}
}

func TestPortfolioManager_LogOrderResult(t *testing.T) {
	fakeKraken := &FakeKrakenService{}
	analyzer := NewPortfolioAnalyzer(fakeKraken, &MockConfigService{}, nil)
	executor := NewOrderExecutor(fakeKraken, analyzer)

	log1 := make([]string, 0)
	executor.logOrderResult(model.OrderResult{Success: true, Pair: "XBTUSD", Side: "sell", Volume: d(1.0), DryRun: true}, nil, &log1, "BTC", d(1.0), d(10.0), "SELL")
	if len(log1) == 0 || log1[0] != "[DRY RUN] SELL BTC Volume: 1 Value: $10" {
		t.Errorf("Unexpected dry run sell log result: %v", log1)
	}

	log2 := make([]string, 0)
	executor.logOrderResult(model.OrderResult{Success: true, Pair: "XBTUSD", Side: "buy", Volume: d(1.0), DryRun: false}, nil, &log2, "BTC", d(1.0), d(10.0), "BUY")
	if len(log2) == 0 || log2[0] != "BUY BTC Volume: 1 Cost: $10" {
		t.Errorf("Unexpected live buy log result: %v", log2)
	}
}

var actionLogEmpty []string

func mapMap(m map[string]float64) map[string]decimal.Decimal {
	res := make(map[string]decimal.Decimal)
	for k, v := range m {
		res[k] = decimal.NewFromFloat(v)
	}
	return res
}
