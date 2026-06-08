package service

import (
	"testing"

	"github.com/HyperVon/new-kraken-rebalancer/internal/config"
	"github.com/HyperVon/new-kraken-rebalancer/internal/model"
	"github.com/shopspring/decimal"
)

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
	res := model.NewOrderResult(true, pair, side, volume, false, "")
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

	// First run sets initial ATH
	dd1 := analyzer.UpdateAthAndCalculateDrawdown(decimal.NewFromFloat(1000.0))
	if !dd1.IsZero() {
		t.Errorf("Expected 0 drawdown on first run, got %s", dd1.String())
	}
	if statsRepo.stats.AllTimeHigh.Cmp(decimal.NewFromFloat(1000.0)) != 0 {
		t.Errorf("Expected ATH to be 1000.0, got %s", statsRepo.stats.AllTimeHigh.String())
	}

	// Drop in portfolio value triggers drawdown
	dd2 := analyzer.UpdateAthAndCalculateDrawdown(decimal.NewFromFloat(800.0))
	expectedDD := decimal.NewFromFloat(20.0) // (1000 - 800) / 1000 * 100
	if dd2.Cmp(expectedDD) != 0 {
		t.Errorf("Expected drawdown to be %s, got %s", expectedDD.String(), dd2.String())
	}

	// New ATH resets drawdown
	dd3 := analyzer.UpdateAthAndCalculateDrawdown(decimal.NewFromFloat(1200.0))
	if !dd3.IsZero() {
		t.Errorf("Expected drawdown to be 0 at new ATH, got %s", dd3.String())
	}
}

func TestFiatDeploymentMath(t *testing.T) {
	analyzer := NewPortfolioAnalyzer(nil, nil, nil)

	settings := config.Settings{
		FiatMaxDrawdown:        30.0,
		FiatDeploymentExponent: 1.0, // Linear
	}

	// Linear deployment test
	deployLinear := analyzer.CalculateFiatDeployment(decimal.NewFromFloat(15.0), settings)
	if deployLinear.Cmp(decimal.NewFromFloat(50.0)) != 0 {
		t.Errorf("Expected linear deployment to be 50.0, got %s", deployLinear.String())
	}

	// Non-linear deployment (aggressive: exponent = 0.5)
	settings.FiatDeploymentExponent = 0.5
	deployAggressive := analyzer.CalculateFiatDeployment(decimal.NewFromFloat(7.5), settings)
	expectedAggressive := decimal.NewFromFloat(50.0) // (7.5 / 30) ^ 0.5 * 100 = 0.25 ^ 0.5 * 100 = 0.5 * 100 = 50.0
	if deployAggressive.Cmp(expectedAggressive) != 0 {
		t.Errorf("Expected aggressive deployment to be %s, got %s", expectedAggressive.String(), deployAggressive.String())
	}
}

func TestEffectiveUsdTarget(t *testing.T) {
	cfg := config.AppConfig{
		Allocations: []config.Allocation{
			{Symbol: "USD", TargetPercent: 10.0},
			{Symbol: "BTC", TargetPercent: 90.0},
		},
	}
	cfgService := &MockConfigService{cfg: cfg}
	analyzer := NewPortfolioAnalyzer(nil, cfgService, nil)

	// With 50% fiat deployment, USD target should be cut in half
	effTarget := analyzer.CalculateEffectiveUsdTarget(decimal.NewFromFloat(50.0))
	expectedTarget := decimal.NewFromFloat(5.0)
	if effTarget.Cmp(expectedTarget) != 0 {
		t.Errorf("Expected effective target to be %s, got %s", expectedTarget.String(), effTarget.String())
	}
}

func TestCryptoScaleFactor(t *testing.T) {
	cfg := config.AppConfig{
		Allocations: []config.Allocation{
			{Symbol: "USD", TargetPercent: 10.0},
			{Symbol: "BTC", TargetPercent: 45.0},
			{Symbol: "ETH", TargetPercent: 45.0},
		},
	}
	cfgService := &MockConfigService{cfg: cfg}
	analyzer := NewPortfolioAnalyzer(nil, cfgService, nil)

	// If USD target is reduced to 5%, crypto targets must consume the remaining 95%
	// scale factor = (100 - 5) / (45 + 45) = 95 / 90 = 1.05555556
	factor := analyzer.CalculateCryptoScaleFactor(decimal.NewFromFloat(5.0))
	expectedFactor := decimal.NewFromFloat(1.05555556)
	if factor.Sub(expectedFactor).Abs().GreaterThan(decimal.NewFromFloat(0.00001)) {
		t.Errorf("Expected scale factor to be approx %s, got %s", expectedFactor.String(), factor.String())
	}
}

func TestAnalyzeDeviationsAndFiatCorrection(t *testing.T) {
	cfg := config.AppConfig{
		Settings: config.Settings{
			DeviationTriggerPercent: 5.0,
			DustThresholdUSD:        5.0,
		},
		Allocations: []config.Allocation{
			{Symbol: "USD", TargetPercent: 10.0},
			{Symbol: "BTC", TargetPercent: 90.0},
		},
	}
	cfgService := &MockConfigService{cfg: cfg}
	analyzer := NewPortfolioAnalyzer(nil, cfgService, nil)

	currentValuesUSD := map[string]decimal.Decimal{
		"USD": decimal.NewFromFloat(100.0),
		"BTC": decimal.NewFromFloat(900.0),
	}

	// No deviation case
	res := analyzer.AnalyzeDeviations(decimal.NewFromFloat(1000.0), currentValuesUSD, decimal.NewFromFloat(10.0), decimal.NewFromFloat(1.0))
	if len(res.BuyOrders) > 0 || len(res.SellOrders) > 0 {
		t.Errorf("Expected no orders when portfolio matches targets perfectly")
	}

	// Trigger deviation (e.g. BTC jumps, USD drops)
	currentValuesUSDDeviated := map[string]decimal.Decimal{
		"USD": decimal.NewFromFloat(40.0),
		"BTC": decimal.NewFromFloat(960.0),
	}
	res2 := analyzer.AnalyzeDeviations(decimal.NewFromFloat(1000.0), currentValuesUSDDeviated, decimal.NewFromFloat(10.0), decimal.NewFromFloat(1.0))
	if len(res2.SellOrders) != 1 || res2.SellOrders["BTC"].Cmp(decimal.NewFromFloat(60.0)) != 0 {
		t.Errorf("Expected BTC sell order of 60.0, got: %v", res2.SellOrders)
	}

	// Fiat Correction Scenario: only USD triggers deviation trigger (e.g. due to deposit)
	cfg.Allocations = []config.Allocation{
		{Symbol: "USD", TargetPercent: 10.0},
		{Symbol: "BTC", TargetPercent: 45.0},
		{Symbol: "ETH", TargetPercent: 45.0},
	}
	cfgService.cfg = cfg

	// Portfolio total: $1200. USD target is 10% ($120). BTC/ETH target is 45% ($540) each.
	// Current: USD is $320 (large surplus). BTC is $440 (deficit of $100). ETH is $440 (deficit of $100).
	// USD triggers (deviation is 166.7%), but individual cryptos deviation is 18.5% which triggers too.
	// Let's set crypto deviations below trigger to ensure only USD triggers, e.g.
	// Deviation trigger is 25%. Crypto deviation is 10%.
	// USD target is 10% ($100). BTC/ETH target is 45% ($450) each. Total = $1000.
	// Current: USD is $200 (surplus of $100, dev = 100%). BTC is $400 (deficit of $50, dev = 11.1%). ETH is $400 (deficit of $50, dev = 11.1%).
	// Cryptos (dev 11.1%) do not trigger deviation (threshold 25%). USD (dev 100%) triggers.
	cfg.Settings.DeviationTriggerPercent = 25.0
	cfgService.cfg = cfg
	currentValuesUSDFiatCorr := map[string]decimal.Decimal{
		"USD": decimal.NewFromFloat(200.0),
		"BTC": decimal.NewFromFloat(400.0),
		"ETH": decimal.NewFromFloat(400.0),
	}
	res3 := analyzer.AnalyzeDeviations(decimal.NewFromFloat(1000.0), currentValuesUSDFiatCorr, decimal.NewFromFloat(10.0), decimal.NewFromFloat(1.0))

	// Surplus USD ($100) should be distributed among BTC and ETH proportional to their counter deviations
	// Deficit is $50 for BTC and $50 for ETH. Ratio is 50/50, so each gets $50 of the USD correction.
	if len(res3.BuyOrders) != 2 {
		t.Errorf("Expected 2 buy orders under fiat correction, got %d", len(res3.BuyOrders))
	}
	if res3.BuyOrders["BTC"].Cmp(decimal.NewFromFloat(50.0)) != 0 || res3.BuyOrders["ETH"].Cmp(decimal.NewFromFloat(50.0)) != 0 {
		t.Errorf("Expected BTC and ETH to get 50.0 buy orders each, got BTC: %s, ETH: %s", res3.BuyOrders["BTC"].String(), res3.BuyOrders["ETH"].String())
	}
}
