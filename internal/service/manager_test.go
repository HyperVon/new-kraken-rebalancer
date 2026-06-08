package service

import (
	"context"
	"errors"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/HyperVon/new-kraken-rebalancer/internal/config"
	"github.com/HyperVon/new-kraken-rebalancer/internal/model"
	"github.com/shopspring/decimal"
)

type MockHistoryService struct {
	snapshots []model.PortfolioSnapshot
	AddFunc   func(snapshot model.PortfolioSnapshot) error
}

func (m *MockHistoryService) Init() error { return nil }
func (m *MockHistoryService) AddSnapshot(snapshot model.PortfolioSnapshot) error {
	m.snapshots = append(m.snapshots, snapshot)
	if m.AddFunc != nil {
		return m.AddFunc(snapshot)
	}
	return nil
}
func (m *MockHistoryService) GetHistory() []model.PortfolioSnapshot { return m.snapshots }
func (m *MockHistoryService) GetLatestSnapshot() (model.PortfolioSnapshot, bool) {
	if len(m.snapshots) == 0 {
		return model.PortfolioSnapshot{}, false
	}
	return m.snapshots[len(m.snapshots)-1], true
}
func (m *MockHistoryService) Subscribe() <-chan model.PortfolioSnapshot     { return nil }
func (m *MockHistoryService) Unsubscribe(ch <-chan model.PortfolioSnapshot) {}

type MockManagerKrakenService struct {
	balances RawBalances
	prices   RawPrices
}

func (m *MockManagerKrakenService) GetBalances() (RawBalances, error) {
	if m.balances == nil {
		return nil, errors.New("balances error")
	}
	return m.balances, nil
}
func (m *MockManagerKrakenService) GetTickerPrices(pairs string) (RawPrices, error) {
	if m.prices == nil {
		return nil, errors.New("prices error")
	}
	return m.prices, nil
}
func (m *MockManagerKrakenService) ExecuteOrder(pair, orderType, side string, volume decimal.Decimal) (model.OrderResult, error) {
	return model.OrderResult{Success: true}, nil
}

func TestPortfolioManager_StartStop(t *testing.T) {
	mgr := NewPortfolioManagerImpl(nil, nil, nil, nil)
	if mgr.isRunning {
		t.Error("Expected default isRunning to be false")
	}

	mgr.StartRebalancingLoop()
	if !mgr.isRunning {
		t.Error("Expected isRunning to be true after StartRebalancingLoop")
	}

	mgr.StopRebalancingLoop()
	if mgr.isRunning {
		t.Error("Expected isRunning to be false after StopRebalancingLoop")
	}
}

func TestPortfolioManager_PerformRebalanceCycle_Success(t *testing.T) {
	cfg := config.AppConfig{
		Kraken: config.KrakenCredentials{APIKey: "key"},
		Settings: config.Settings{
			LoopDelaySeconds:        10,
			DeviationTriggerPercent: d(5.0),
			DustThresholdUSD:        d(1.0),
			FiatMaxDrawdown:         d(30.0),
			FiatDeploymentExponent:  d(1.0),
		},
		Allocations: []config.Allocation{
			{Symbol: "USD", TargetPercent: d(10.0)},
			{Symbol: "BTC", TargetPercent: d(90.0)},
		},
	}
	cfgService := &MockConfigService{cfg: cfg}
	history := &MockHistoryService{}

	kraken := &MockManagerKrakenService{
		balances: RawBalances{"ZUSD": 100.0, "XXBT": 1.5},
		prices:   RawPrices{"XXBTZUSD": 600.0},
	}

	statsRepo := &MockStatsRepo{stats: model.PortfolioStats{AllTimeHigh: d(1000.0)}}
	analyzer := NewPortfolioAnalyzer(kraken, cfgService, statsRepo)
	executor := NewOrderExecutor(kraken, analyzer)

	mgr := NewPortfolioManagerImpl(cfgService, history, analyzer, executor)

	err := mgr.PerformRebalanceCycle()
	if err != nil {
		t.Fatalf("Expected cycle success, got err: %v", err)
	}

	if len(history.snapshots) != 1 {
		t.Fatalf("Expected 1 snapshot, got %d", len(history.snapshots))
	}

	snap := history.snapshots[0]
	if !snap.TotalValueUSD.Equal(d(1000.0)) {
		t.Errorf("Expected total value 1000.0, got %v", snap.TotalValueUSD)
	}
}

func TestPortfolioManager_PerformRebalanceCycle_Errors(t *testing.T) {
	cfg := config.AppConfig{
		Allocations: []config.Allocation{
			{Symbol: "USD", TargetPercent: d(10.0)},
			{Symbol: "BTC", TargetPercent: d(90.0)},
		},
	}
	cfgService := &MockConfigService{cfg: cfg}
	history := &MockHistoryService{}
	statsRepo := &MockStatsRepo{}

	// 1. Balances fail
	kraken1 := &MockManagerKrakenService{balances: nil}
	analyzer1 := NewPortfolioAnalyzer(kraken1, cfgService, statsRepo)
	executor1 := NewOrderExecutor(kraken1, analyzer1)
	mgr1 := NewPortfolioManagerImpl(cfgService, history, analyzer1, executor1)

	err := mgr1.PerformRebalanceCycle()
	if err == nil || !strings.Contains(err.Error(), "failed to fetch balances") {
		t.Errorf("Expected fetch balances error, got %v", err)
	}

	// 2. Prices fail
	kraken2 := &MockManagerKrakenService{
		balances: RawBalances{"ZUSD": 100.0},
		prices:   nil,
	}
	analyzer2 := NewPortfolioAnalyzer(kraken2, cfgService, statsRepo)
	executor2 := NewOrderExecutor(kraken2, analyzer2)
	mgr2 := NewPortfolioManagerImpl(cfgService, history, analyzer2, executor2)

	err = mgr2.PerformRebalanceCycle()
	if err == nil || !strings.Contains(err.Error(), "failed to fetch prices") {
		t.Errorf("Expected fetch prices error, got %v", err)
	}

	// 3. Aborted due to missing price
	kraken3 := &MockManagerKrakenService{
		balances: RawBalances{"ZUSD": 100.0, "XXBT": 1.0},
		prices:   RawPrices{},
	}
	analyzer3 := NewPortfolioAnalyzer(kraken3, cfgService, statsRepo)
	executor3 := NewOrderExecutor(kraken3, analyzer3)
	mgr3 := NewPortfolioManagerImpl(cfgService, history, analyzer3, executor3)

	err = mgr3.PerformRebalanceCycle()
	if err == nil || !strings.Contains(err.Error(), "aborted rebalance cycle due to missing prices") {
		t.Errorf("Expected aborted due to missing prices, got %v", err)
	}

	// 4. Save history warning logged but no error returned
	kraken4 := &MockManagerKrakenService{
		balances: RawBalances{"ZUSD": 100.0},
		prices:   RawPrices{"XXBTZUSD": 600.0},
	}
	analyzer4 := NewPortfolioAnalyzer(kraken4, cfgService, statsRepo)
	executor4 := NewOrderExecutor(kraken4, analyzer4)
	historyErr := &MockHistoryService{
		AddFunc: func(snapshot model.PortfolioSnapshot) error {
			return errors.New("save history failed")
		},
	}
	mgr4 := NewPortfolioManagerImpl(cfgService, historyErr, analyzer4, executor4)

	err = mgr4.PerformRebalanceCycle()
	if err != nil {
		t.Errorf("Expected no error returned when save history fails, got %v", err)
	}
}

func TestPortfolioManager_RunLoop(t *testing.T) {
	cfg := config.AppConfig{
		Settings: config.Settings{
			LoopDelaySeconds: 1,
		},
		Allocations: []config.Allocation{
			{Symbol: "USD", TargetPercent: d(100.0)},
		},
	}
	cfgService := &MockConfigService{cfg: cfg}
	history := &MockHistoryService{}
	kraken := &MockManagerKrakenService{
		balances: RawBalances{"ZUSD": 100.0},
		prices:   RawPrices{},
	}
	statsRepo := &MockStatsRepo{}
	analyzer := NewPortfolioAnalyzer(kraken, cfgService, statsRepo)
	executor := NewOrderExecutor(kraken, analyzer)
	mgr := NewPortfolioManagerImpl(cfgService, history, analyzer, executor)

	// 1. Loop not running, context cancelled -> exits immediately
	ctxCancel, cancel := context.WithCancel(context.Background())
	cancel()
	mgr.RunLoop(ctxCancel)

	// 2. Loop running, context cancelled -> exits after running
	mgr.StartRebalancingLoop()

	ctx, cancel2 := context.WithCancel(context.Background())
	var wg sync.WaitGroup
	wg.Add(1)
	go func() {
		defer wg.Done()
		mgr.RunLoop(ctx)
	}()

	time.Sleep(150 * time.Millisecond)
	cancel2()

	wg.Wait()
	if len(history.snapshots) == 0 {
		t.Error("Expected at least one rebalance cycle to have run")
	}
}
