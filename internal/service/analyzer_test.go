package service

import (
	"errors"
	"testing"

	"github.com/HyperVon/new-kraken-rebalancer/internal/config"
	"github.com/HyperVon/new-kraken-rebalancer/internal/model"
	"github.com/shopspring/decimal"
)

func d(f float64) decimal.Decimal { return decimal.NewFromFloat(f) }

// MockConfigService mock implementation
type MockConfigService struct {
	cfg config.AppConfig
}

func (m *MockConfigService) LoadConfig() error {
	return nil
}

func (m *MockConfigService) GetConfig() config.AppConfig {
	return m.cfg
}

func (m *MockConfigService) UpdateConfig(newConfig config.AppConfig) error {
	m.cfg = newConfig
	return nil
}

// MockStatsRepo mock implementation
type MockStatsRepo struct {
	stats model.PortfolioStats
}

func (m *MockStatsRepo) Save(stats model.PortfolioStats) error {
	m.stats = stats
	return nil
}

func (m *MockStatsRepo) Load() (model.PortfolioStats, error) {
	return m.stats, nil
}

// MockKrakenService mock implementation
type MockKrakenService struct {
	balances RawBalances
	prices   RawPrices
	orders   []model.OrderResult
}

func (m *MockKrakenService) GetBalances() (RawBalances, error) {
	return m.balances, nil
}

func (m *MockKrakenService) GetTickerPrices(pairs string) (RawPrices, error) {
	return m.prices, nil
}

func (m *MockKrakenService) ExecuteOrder(pair, orderType, side string, volume decimal.Decimal) (model.OrderResult, error) {
	res := model.OrderResult{Success: true, Pair: pair, Side: side, Volume: volume}
	m.orders = append(m.orders, res)
	return res, nil
}

func TestResolvePriceFromTicker(t *testing.T) {
	analyzer := NewPortfolioAnalyzer(nil, nil, nil)
	rawPrices := RawPrices{
		"XXBTZUSD": 60000.0,
		"XDGUSD":   0.15,
		"ETHUSD":   3000.0,
	}

	tests := []struct {
		symbol   string
		expected float64
	}{
		{"BTC", 60000.0},
		{"DOGE", 0.15},
		{"ETH", 3000.0},
		{"UNKNOWN", 0.0},
	}

	for _, tc := range tests {
		got := analyzer.resolvePriceFromTicker(tc.symbol, rawPrices)
		if got != tc.expected {
			t.Errorf("resolvePriceFromTicker(%q) = %f; expected %f", tc.symbol, got, tc.expected)
		}
	}
}

func TestATHAndDrawdown(t *testing.T) {
	statsRepo := &MockStatsRepo{}
	analyzer := NewPortfolioAnalyzer(nil, nil, statsRepo)

	dd1 := analyzer.UpdateAthAndCalculateDrawdown(d(1000.0))
	if !dd1.IsZero() {
		t.Errorf("Expected 0 drawdown on first run, got %s", dd1.String())
	}
	if statsRepo.stats.AllTimeHigh.Cmp(d(1000.0)) != 0 {
		t.Errorf("Expected ATH to be 1000.0, got %s", statsRepo.stats.AllTimeHigh.String())
	}

	dd2 := analyzer.UpdateAthAndCalculateDrawdown(d(800.0))
	expectedDD := d(20.0)
	if dd2.Cmp(expectedDD) != 0 {
		t.Errorf("Expected drawdown to be %s, got %s", expectedDD.String(), dd2.String())
	}

	dd3 := analyzer.UpdateAthAndCalculateDrawdown(d(1200.0))
	if !dd3.IsZero() {
		t.Errorf("Expected drawdown to be 0 at new ATH, got %s", dd3.String())
	}
}

func TestFiatDeploymentMath(t *testing.T) {
	analyzer := NewPortfolioAnalyzer(nil, nil, nil)

	settings := config.Settings{
		FiatMaxDrawdown:        d(30.0),
		FiatDeploymentExponent: d(1.0),
	}

	deployLinear := analyzer.CalculateFiatDeployment(d(15.0), settings)
	if deployLinear.Cmp(d(50.0)) != 0 {
		t.Errorf("Expected linear deployment to be 50.0, got %s", deployLinear.String())
	}

	settings.FiatDeploymentExponent = d(0.5)
	deployAggressive := analyzer.CalculateFiatDeployment(d(7.5), settings)
	expectedAggressive := d(50.0)
	if deployAggressive.Cmp(expectedAggressive) != 0 {
		t.Errorf("Expected aggressive deployment to be %s, got %s", expectedAggressive.String(), deployAggressive.String())
	}
}

func TestEffectiveUsdTarget(t *testing.T) {
	cfg := config.AppConfig{
		Allocations: []config.Allocation{
			{Symbol: "USD", TargetPercent: d(10.0)},
			{Symbol: "BTC", TargetPercent: d(90.0)},
		},
	}
	cfgService := &MockConfigService{cfg: cfg}
	analyzer := NewPortfolioAnalyzer(nil, cfgService, nil)

	effTarget := analyzer.CalculateEffectiveUsdTarget(d(50.0))
	expectedTarget := d(5.0)
	if effTarget.Cmp(expectedTarget) != 0 {
		t.Errorf("Expected effective target to be %s, got %s", expectedTarget.String(), effTarget.String())
	}
}

func TestCryptoScaleFactor(t *testing.T) {
	cfg := config.AppConfig{
		Allocations: []config.Allocation{
			{Symbol: "USD", TargetPercent: d(10.0)},
			{Symbol: "BTC", TargetPercent: d(45.0)},
			{Symbol: "ETH", TargetPercent: d(45.0)},
		},
	}
	cfgService := &MockConfigService{cfg: cfg}
	analyzer := NewPortfolioAnalyzer(nil, cfgService, nil)

	factor := analyzer.CalculateCryptoScaleFactor(d(5.0))
	expectedFactor := d(1.05555556)
	if factor.Sub(expectedFactor).Abs().GreaterThan(d(0.00001)) {
		t.Errorf("Expected scale factor to be approx %s, got %s", expectedFactor.String(), factor.String())
	}
}

func TestAnalyzeDeviationsAndFiatCorrection(t *testing.T) {
	cfg := config.AppConfig{
		Settings: config.Settings{
			DeviationTriggerPercent: d(5.0),
			DustThresholdUSD:        d(5.0),
		},
		Allocations: []config.Allocation{
			{Symbol: "USD", TargetPercent: d(10.0)},
			{Symbol: "BTC", TargetPercent: d(90.0)},
		},
	}
	cfgService := &MockConfigService{cfg: cfg}
	analyzer := NewPortfolioAnalyzer(nil, cfgService, nil)

	currentValuesUSD := map[string]decimal.Decimal{
		"USD": d(100.0),
		"BTC": d(900.0),
	}

	res := analyzer.AnalyzeDeviations(d(1000.0), currentValuesUSD, d(10.0), d(1.0))
	if len(res.BuyOrders) > 0 || len(res.SellOrders) > 0 {
		t.Errorf("Expected no orders when portfolio matches targets perfectly")
	}

	currentValuesUSDDeviated := map[string]decimal.Decimal{
		"USD": d(40.0),
		"BTC": d(960.0),
	}
	res2 := analyzer.AnalyzeDeviations(d(1000.0), currentValuesUSDDeviated, d(10.0), d(1.0))
	if len(res2.SellOrders) != 1 || res2.SellOrders["BTC"].Cmp(d(60.0)) != 0 {
		t.Errorf("Expected BTC sell order of 60.0, got: %v", res2.SellOrders)
	}

	cfg.Allocations = []config.Allocation{
		{Symbol: "USD", TargetPercent: d(10.0)},
		{Symbol: "BTC", TargetPercent: d(45.0)},
		{Symbol: "ETH", TargetPercent: d(45.0)},
	}
	cfg.Settings.DeviationTriggerPercent = d(25.0)
	cfgService.cfg = cfg

	currentValuesUSDFiatCorr := map[string]decimal.Decimal{
		"USD": d(200.0),
		"BTC": d(400.0),
		"ETH": d(400.0),
	}
	res3 := analyzer.AnalyzeDeviations(d(1000.0), currentValuesUSDFiatCorr, d(10.0), d(1.0))

	if len(res3.BuyOrders) != 2 {
		t.Errorf("Expected 2 buy orders under fiat correction, got %d", len(res3.BuyOrders))
	}
	if res3.BuyOrders["BTC"].Cmp(d(50.0)) != 0 || res3.BuyOrders["ETH"].Cmp(d(50.0)) != 0 {
		t.Errorf("Expected BTC and ETH to get 50.0 buy orders each, got BTC: %s, ETH: %s", res3.BuyOrders["BTC"].String(), res3.BuyOrders["ETH"].String())
	}
}

type FailStatsRepo struct {
	LoadErr error
	SaveErr error
}

func (f *FailStatsRepo) Load() (model.PortfolioStats, error) {
	return model.PortfolioStats{}, f.LoadErr
}

func (f *FailStatsRepo) Save(stats model.PortfolioStats) error {
	return f.SaveErr
}

func TestUpdateAthAndCalculateDrawdown_Errors(t *testing.T) {
	repo := &FailStatsRepo{LoadErr: errors.New("load error")}
	analyzer := NewPortfolioAnalyzer(nil, nil, repo)
	dd := analyzer.UpdateAthAndCalculateDrawdown(d(100.0))
	if !dd.IsZero() {
		t.Errorf("Expected 0 drawdown, got %v", dd)
	}

	repo2 := &FailStatsRepo{SaveErr: errors.New("save error")}
	analyzer2 := NewPortfolioAnalyzer(nil, nil, repo2)
	dd2 := analyzer2.UpdateAthAndCalculateDrawdown(d(100.0))
	if !dd2.IsZero() {
		t.Errorf("Expected 0 drawdown, got %v", dd2)
	}
}

func TestCalculateFiatDeployment_EdgeCases(t *testing.T) {
	analyzer := NewPortfolioAnalyzer(nil, nil, nil)

	settings := config.Settings{FiatMaxDrawdown: d(0.0), FiatDeploymentExponent: d(1.0)}
	d1 := analyzer.CalculateFiatDeployment(d(10.0), settings)
	if !d1.IsZero() {
		t.Errorf("Expected 0 deployment for 0 max drawdown, got %v", d1)
	}

	settings.FiatMaxDrawdown = d(-5.0)
	d2 := analyzer.CalculateFiatDeployment(d(10.0), settings)
	if !d2.IsZero() {
		t.Errorf("Expected 0 deployment for negative max drawdown, got %v", d2)
	}

	settings.FiatMaxDrawdown = d(20.0)
	settings.FiatDeploymentExponent = d(1.0)
	d3 := analyzer.CalculateFiatDeployment(d(30.0), settings)
	if !d3.Equal(d(100.0)) {
		t.Errorf("Expected 100.0 deployment when drawdown exceeds max drawdown, got %v", d3)
	}
}

func TestAnalyzeDeviations_ZeroTargetPositiveCurrent(t *testing.T) {
	cfg := config.AppConfig{
		Settings: config.Settings{
			DeviationTriggerPercent: d(5.0),
			DustThresholdUSD:        d(5.0),
		},
		Allocations: []config.Allocation{
			{Symbol: "USD", TargetPercent: d(100.0)},
			{Symbol: "BTC", TargetPercent: d(0.0)},
		},
	}
	cfgService := &MockConfigService{cfg: cfg}
	analyzer := NewPortfolioAnalyzer(nil, cfgService, nil)

	currentValuesUSD := map[string]decimal.Decimal{
		"USD": d(900.0),
		"BTC": d(100.0),
	}

	res := analyzer.AnalyzeDeviations(d(1000.0), currentValuesUSD, d(100.0), d(1.0))
	if len(res.SellOrders) != 1 || res.SellOrders["BTC"].Cmp(d(100.0)) != 0 {
		t.Errorf("Expected BTC sell order of 100.0, got: %v", res.SellOrders)
	}
}

func TestAnalyzeDeviations_FiatCorrectionWithdrawal(t *testing.T) {
	cfg := config.AppConfig{
		Settings: config.Settings{
			DeviationTriggerPercent: d(10.0),
			DustThresholdUSD:        d(1.0),
		},
		Allocations: []config.Allocation{
			{Symbol: "USD", TargetPercent: d(10.0)},
			{Symbol: "BTC", TargetPercent: d(45.0)},
			{Symbol: "ETH", TargetPercent: d(45.0)},
		},
	}
	cfgService := &MockConfigService{cfg: cfg}
	analyzer := NewPortfolioAnalyzer(nil, cfgService, nil)

	currentValues := map[string]decimal.Decimal{
		"USD": d(50.0),
		"BTC": d(475.0),
		"ETH": d(475.0),
	}

	res := analyzer.AnalyzeDeviations(d(1000.0), currentValues, d(10.0), d(1.0))

	if len(res.SellOrders) != 2 {
		t.Errorf("Expected 2 sell orders, got %d", len(res.SellOrders))
	}
	if res.SellOrders["BTC"].Cmp(d(25.0)) != 0 || res.SellOrders["ETH"].Cmp(d(25.0)) != 0 {
		t.Errorf("Expected BTC and ETH to get 25.0 sell orders each, got BTC: %s, ETH: %s", res.SellOrders["BTC"].String(), res.SellOrders["ETH"].String())
	}
}
