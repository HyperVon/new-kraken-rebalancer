package web

import (
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/HyperVon/new-kraken-rebalancer/internal/config"
	"github.com/HyperVon/new-kraken-rebalancer/internal/model"
	"github.com/shopspring/decimal"
)

// MockConfigService mock implementation
type MockConfigService struct {
	cfg config.AppConfig
}

func (m *MockConfigService) LoadConfig() error           { return nil }
func (m *MockConfigService) GetConfig() config.AppConfig { return m.cfg }
func (m *MockConfigService) UpdateConfig(newConfig config.AppConfig) error {
	m.cfg = newConfig
	return nil
}

// MockHistoryService mock implementation
type MockHistoryService struct {
	snapshots []model.PortfolioSnapshot
}

func (m *MockHistoryService) Init() error { return nil }
func (m *MockHistoryService) AddSnapshot(snapshot model.PortfolioSnapshot) error {
	m.snapshots = append([]model.PortfolioSnapshot{snapshot}, m.snapshots...)
	return nil
}
func (m *MockHistoryService) GetHistory() []model.PortfolioSnapshot { return m.snapshots }
func (m *MockHistoryService) GetLatestSnapshot() (model.PortfolioSnapshot, bool) {
	if len(m.snapshots) == 0 {
		return model.PortfolioSnapshot{}, false
	}
	return m.snapshots[0], true
}
func (m *MockHistoryService) Subscribe() <-chan model.PortfolioSnapshot {
	return make(chan model.PortfolioSnapshot)
}
func (m *MockHistoryService) Unsubscribe(ch <-chan model.PortfolioSnapshot) {}

func TestRoutes(t *testing.T) {
	InitTemplates()

	cfg := config.AppConfig{
		Settings: config.Settings{
			LoopDelaySeconds:        60,
			DeviationTriggerPercent: 5.0,
			DustThresholdUSD:        5.0,
		},
		Allocations: []config.Allocation{
			{Symbol: "USD", TargetPercent: 10.0},
			{Symbol: "BTC", TargetPercent: 90.0},
		},
	}
	cfgService := &MockConfigService{cfg: cfg}
	historyService := &MockHistoryService{}

	mux := http.NewServeMux()
	RegisterHandlers(mux, cfgService, historyService)

	// Test GET /
	req := httptest.NewRequest("GET", "/", nil)
	rr := httptest.NewRecorder()
	mux.ServeHTTP(rr, req)

	if rr.Code != http.StatusOK {
		t.Errorf("Expected status OK for /, got %d", rr.Code)
	}
	if !strings.Contains(rr.Body.String(), "Kraken Rebalancer") {
		t.Errorf("Expected body to contain title, got: %s", rr.Body.String())
	}

	// Test GET /settings
	req = httptest.NewRequest("GET", "/settings", nil)
	rr = httptest.NewRecorder()
	mux.ServeHTTP(rr, req)

	if rr.Code != http.StatusOK {
		t.Errorf("Expected status OK for /settings, got %d", rr.Code)
	}
	if !strings.Contains(rr.Body.String(), "Global Parameters") {
		t.Errorf("Expected body to contain settings fields, got: %s", rr.Body.String())
	}

	// Test GET /fragments/dashboard when empty
	req = httptest.NewRequest("GET", "/fragments/dashboard", nil)
	rr = httptest.NewRecorder()
	mux.ServeHTTP(rr, req)

	if rr.Code != http.StatusOK {
		t.Errorf("Expected status OK for empty fragment, got %d", rr.Code)
	}
	if !strings.Contains(rr.Body.String(), "Waiting for the first cycle") {
		t.Errorf("Expected spinner and waiting message, got: %s", rr.Body.String())
	}

	// Add a snapshot and test GET /fragments/dashboard again
	snap := model.PortfolioSnapshot{
		Timestamp:                 time.Now(),
		TotalValueUSD:             decimal.NewFromFloat(1000.0),
		DrawdownPercent:           decimal.Zero,
		FiatDeploymentPercent:     decimal.Zero,
		EffectiveUsdTargetPercent: decimal.NewFromFloat(10.0),
		Assets: map[string]model.AssetSnapshot{
			"USD": {
				Symbol:           "USD",
				Balance:          decimal.NewFromFloat(100.0),
				Price:            decimal.NewFromFloat(1.0),
				ValueUSD:         decimal.NewFromFloat(100.0),
				TargetPercent:    decimal.NewFromFloat(10.0),
				CurrentPercent:   decimal.NewFromFloat(10.0),
				DeviationPercent: decimal.Zero,
				DeviationUSD:     decimal.Zero,
			},
			"BTC": {
				Symbol:           "BTC",
				Balance:          decimal.NewFromFloat(1.5),
				Price:            decimal.NewFromFloat(600.0),
				ValueUSD:         decimal.NewFromFloat(900.0),
				TargetPercent:    decimal.NewFromFloat(90.0),
				CurrentPercent:   decimal.NewFromFloat(90.0),
				DeviationPercent: decimal.Zero,
				DeviationUSD:     decimal.Zero,
			},
		},
		Actions: []string{"BUY BTC Volume: 0.1 Cost: $60"},
	}
	historyService.AddSnapshot(snap)

	req = httptest.NewRequest("GET", "/fragments/dashboard", nil)
	rr = httptest.NewRecorder()
	mux.ServeHTTP(rr, req)

	if rr.Code != http.StatusOK {
		t.Errorf("Expected status OK for populated fragment, got %d", rr.Code)
	}
	if !strings.Contains(rr.Body.String(), "Portfolio Allocation") {
		t.Errorf("Expected fragment elements in response, got: %s", rr.Body.String())
	}
}
