package web

import (
	"context"
	"errors"
	"html/template"
	"net/http"
	"net/http/httptest"
	"net/url"
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
	err error
}

func (m *MockConfigService) LoadConfig() error           { return nil }
func (m *MockConfigService) GetConfig() config.AppConfig { return m.cfg }
func (m *MockConfigService) UpdateConfig(newConfig config.AppConfig) error {
	if m.err != nil {
		return m.err
	}
	m.cfg = newConfig
	return nil
}

// MockHistoryService mock implementation
type MockHistoryService struct {
	snapshots []model.PortfolioSnapshot
	subCh     chan model.PortfolioSnapshot
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
	if m.subCh == nil {
		m.subCh = make(chan model.PortfolioSnapshot, 5)
	}
	return m.subCh
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

func TestStaticFiles(t *testing.T) {
	mux := http.NewServeMux()
	RegisterHandlers(mux, &MockConfigService{}, &MockHistoryService{})

	req := httptest.NewRequest("GET", "/static/style.css", nil)
	rr := httptest.NewRecorder()
	mux.ServeHTTP(rr, req)

	if rr.Code != http.StatusOK {
		t.Errorf("Expected status OK for static resource, got %d", rr.Code)
	}
}

func TestPostSettings(t *testing.T) {
	InitTemplates()

	cfg := config.AppConfig{
		Allocations: []config.Allocation{
			{Symbol: "USD", TargetPercent: 10.0},
		},
	}
	cfgService := &MockConfigService{cfg: cfg}
	historyService := &MockHistoryService{}

	mux := http.NewServeMux()
	RegisterHandlers(mux, cfgService, historyService)

	// Case 1: Successful form post (updates config and redirects)
	form := url.Values{}
	form.Set("loopDelaySeconds", "120")
	form.Set("deviationTriggerPercent", "2.5")
	form.Set("dustThresholdUSD", "1.5")
	form.Set("dryRun", "on")
	form.Set("fiatMaxDrawdown", "25.0")
	form.Set("fiatDeploymentExponent", "1.5")
	form.Add("symbols", "USD")
	form.Add("targets", "100.0") // target sums up to 100%

	req := httptest.NewRequest("POST", "/settings", strings.NewReader(form.Encode()))
	req.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	rr := httptest.NewRecorder()
	mux.ServeHTTP(rr, req)

	if rr.Code != http.StatusOK {
		t.Errorf("Expected 200 OK for successful HTMX post, got %d", rr.Code)
	}
	if rr.Header().Get("HX-Redirect") != "/" {
		t.Errorf("Expected redirect header 'HX-Redirect' to be '/', got '%s'", rr.Header().Get("HX-Redirect"))
	}

	// Verify config updated
	updated := cfgService.GetConfig()
	if updated.Settings.LoopDelaySeconds != 120 {
		t.Errorf("Expected LoopDelaySeconds 120, got %d", updated.Settings.LoopDelaySeconds)
	}

	// Case 1b: Form post with default fallbacks and mismatched symbol/target arrays
	formFallback := url.Values{}
	formFallback.Set("loopDelaySeconds", "0")          // loops <= 0 -> falls back to 60
	formFallback.Set("fiatDeploymentExponent", "-2.0") // exponent <= 0 -> falls back to 1.0
	formFallback.Add("symbols", "USD")
	formFallback.Add("symbols", "BTC")
	formFallback.Add("targets", "100.0") // Mismatch: 2 symbols, 1 target value

	reqFallback := httptest.NewRequest("POST", "/settings", strings.NewReader(formFallback.Encode()))
	reqFallback.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	rrFallback := httptest.NewRecorder()
	mux.ServeHTTP(rrFallback, reqFallback)

	if rrFallback.Code != http.StatusOK {
		t.Errorf("Expected 200 OK for validation fail on fallback, got %d", rrFallback.Code)
	}

	// Case 2: Validation failure on form post (renders inline form validation error)
	cfgService.err = errors.New("validation failed mock error")

	reqErr := httptest.NewRequest("POST", "/settings", strings.NewReader(form.Encode()))
	reqErr.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	rrErr := httptest.NewRecorder()
	mux.ServeHTTP(rrErr, reqErr)

	if rrErr.Code != http.StatusOK {
		t.Errorf("Expected 200 OK for validation failure, got %d", rrErr.Code)
	}
	if !strings.Contains(rrErr.Body.String(), "validation failed mock error") {
		t.Errorf("Expected page to contain inline validation failure message, got: %s", rrErr.Body.String())
	}
}

func TestPostSettings_ParseFormError(t *testing.T) {
	mux := http.NewServeMux()
	RegisterHandlers(mux, &MockConfigService{}, &MockHistoryService{})

	// Post request with invalid URL escape that causes ParseForm to fail
	req := httptest.NewRequest("POST", "/settings", strings.NewReader("%"))
	req.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	rr := httptest.NewRecorder()
	mux.ServeHTTP(rr, req)

	if rr.Code != http.StatusBadRequest {
		t.Errorf("Expected status Bad Request, got %d", rr.Code)
	}
}

func TestGetDashboardFragment_TimeScenarios(t *testing.T) {
	InitTemplates()
	cfgService := &MockConfigService{}
	historyService := &MockHistoryService{}

	mux := http.NewServeMux()
	RegisterHandlers(mux, cfgService, historyService)

	// 1. Stale snapshot (> 90 seconds old)
	staleSnap := model.PortfolioSnapshot{
		Timestamp:     time.Now().Add(-100 * time.Second),
		TotalValueUSD: decimal.NewFromFloat(500.0),
		Assets:        map[string]model.AssetSnapshot{},
	}
	historyService.AddSnapshot(staleSnap)

	req1 := httptest.NewRequest("GET", "/fragments/dashboard", nil)
	rr1 := httptest.NewRecorder()
	mux.ServeHTTP(rr1, req1)

	if !strings.Contains(rr1.Body.String(), "delayed") && !strings.Contains(rr1.Body.String(), "DELAYED") {
		t.Error("Expected dashboard fragment to render delayed stale data badge")
	}

	// 2. Future snapshot (timestamp is in future, triggering timeSinceUpdate < 0 path)
	futureSnap := model.PortfolioSnapshot{
		Timestamp:     time.Now().Add(10 * time.Second),
		TotalValueUSD: decimal.NewFromFloat(500.0),
		Assets:        map[string]model.AssetSnapshot{},
	}
	// Overwrite snapshot
	historyService.snapshots = []model.PortfolioSnapshot{futureSnap}

	req2 := httptest.NewRequest("GET", "/fragments/dashboard", nil)
	rr2 := httptest.NewRecorder()
	mux.ServeHTTP(rr2, req2)

	if rr2.Code != http.StatusOK {
		t.Errorf("Expected 200 OK, got %d", rr2.Code)
	}
}

// Custom mock response writer that does NOT implement http.Flusher
type NonFlusherResponseWriter struct {
	header     http.Header
	statusCode int
}

func NewNonFlusherResponseWriter() *NonFlusherResponseWriter {
	return &NonFlusherResponseWriter{
		header: make(http.Header),
	}
}

func (n *NonFlusherResponseWriter) Header() http.Header { return n.header }
func (n *NonFlusherResponseWriter) Write(b []byte) (int, error) {
	return len(b), nil
}
func (n *NonFlusherResponseWriter) WriteHeader(statusCode int) {
	n.statusCode = statusCode
}

func TestSSEStream(t *testing.T) {
	cfgService := &MockConfigService{}
	historyService := &MockHistoryService{}

	mux := http.NewServeMux()
	RegisterHandlers(mux, cfgService, historyService)

	// 1. Streaming unsupported (NonFlusher)
	req1 := httptest.NewRequest("GET", "/api/status/stream", nil)
	rw1 := NewNonFlusherResponseWriter()
	mux.ServeHTTP(rw1, req1)
	if rw1.statusCode != http.StatusInternalServerError {
		t.Errorf("Expected 500 error for non-flusher connection, got %d", rw1.statusCode)
	}

	// 2. Normal streaming lifecycle and client context cancellation
	ctx, cancel := context.WithCancel(context.Background())

	// Push initial snapshot into history so it sends it immediately
	snap := model.PortfolioSnapshot{TotalValueUSD: decimal.NewFromFloat(320.0)}
	historyService.AddSnapshot(snap)

	req2 := httptest.NewRequest("GET", "/api/status/stream", nil)
	req2 = req2.WithContext(ctx)
	rr2 := httptest.NewRecorder()

	// Launch in a goroutine and cancel context after 50ms
	go func() {
		time.Sleep(50 * time.Millisecond)
		cancel()
		// Push another snapshot to check channel read but context is cancelled
		historyService.subCh <- model.PortfolioSnapshot{TotalValueUSD: decimal.NewFromFloat(500.0)}
	}()

	mux.ServeHTTP(rr2, req2)

	if !strings.Contains(rr2.Body.String(), "320") {
		t.Errorf("Expected initial stream message to contain '320', got: %s", rr2.Body.String())
	}

	// 3. SSE Stream exits when channel is closed (isOpen == false)
	req3 := httptest.NewRequest("GET", "/api/status/stream", nil)
	rr3 := httptest.NewRecorder()

	go func() {
		time.Sleep(50 * time.Millisecond)
		close(historyService.subCh)
	}()

	mux.ServeHTTP(rr3, req3)
}

func TestRoutes_TemplateErrors(t *testing.T) {
	oldTemplates := Templates
	defer func() { Templates = oldTemplates }()
	Templates = template.New("") // Empty template with no registered templates!

	cfg := config.AppConfig{
		Allocations: []config.Allocation{
			{Symbol: "USD", TargetPercent: 100.0},
		},
	}
	cfgService := &MockConfigService{cfg: cfg}
	historyService := &MockHistoryService{}

	mux := http.NewServeMux()
	RegisterHandlers(mux, cfgService, historyService)

	// 1. GET / fails shell template execution
	req1 := httptest.NewRequest("GET", "/", nil)
	rr1 := httptest.NewRecorder()
	mux.ServeHTTP(rr1, req1)
	if rr1.Code != http.StatusInternalServerError {
		t.Errorf("Expected 500 error for GET /, got %d", rr1.Code)
	}

	// 2. GET /settings fails settings template execution
	req2 := httptest.NewRequest("GET", "/settings", nil)
	rr2 := httptest.NewRecorder()
	mux.ServeHTTP(rr2, req2)
	if rr2.Code != http.StatusInternalServerError {
		t.Errorf("Expected 500 error for GET /settings, got %d", rr2.Code)
	}

	// 3. POST /settings with validation failure fails settings template execution
	cfgService.err = errors.New("validation failed mock error")
	form := url.Values{}
	form.Add("symbols", "USD")
	form.Add("targets", "100.0")
	req3 := httptest.NewRequest("POST", "/settings", strings.NewReader(form.Encode()))
	req3.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	rr3 := httptest.NewRecorder()
	mux.ServeHTTP(rr3, req3)
	if rr3.Code != http.StatusInternalServerError {
		t.Errorf("Expected 500 error for POST /settings, got %d", rr3.Code)
	}

	// 4. GET /fragments/dashboard fails fragment template execution
	snap := model.PortfolioSnapshot{
		Timestamp:     time.Now(),
		TotalValueUSD: decimal.NewFromFloat(100.0),
		Assets:        map[string]model.AssetSnapshot{},
	}
	historyService.AddSnapshot(snap)
	req4 := httptest.NewRequest("GET", "/fragments/dashboard", nil)
	rr4 := httptest.NewRecorder()
	mux.ServeHTTP(rr4, req4)
	if rr4.Code != http.StatusInternalServerError {
		t.Errorf("Expected 500 error for GET /fragments/dashboard, got %d", rr4.Code)
	}
}


