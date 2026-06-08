package service

import (
	"encoding/base64"
	"errors"
	"net/http"
	"testing"

	"github.com/HyperVon/new-kraken-rebalancer/internal/config"
)

func TestResilienceChaos_BadGateway(t *testing.T) {
	appConfig := config.AppConfig{
		Kraken: config.KrakenCredentials{
			APIKey:     "apiKey",
			PrivateKey: base64.StdEncoding.EncodeToString([]byte("secret")),
		},
		Settings: config.Settings{
			LoopDelaySeconds:        60,
			DeviationTriggerPercent: d(2.0),
			DustThresholdUSD:        d(1.0),
			DryRun:                  false,
			FiatMaxDrawdown:         d(50.0),
			FiatDeploymentExponent:  d(1.0),
		},
		Allocations: []config.Allocation{
			{Symbol: "BTC", TargetPercent: d(50.0)},
		},
	}
	mockConfigService := &MockConfigService{cfg: appConfig}

	mockHttpClient := &MockHTTPClient{
		DoFunc: func(req *http.Request) (*http.Response, error) {
			return makeResponse(http.StatusBadGateway, "Bad Gateway"), nil
		},
	}

	krakenService := NewKrakenServiceImpl(mockConfigService, mockHttpClient)
	mockHistory := &MockHistoryService{}
	mockStatsRepo := &MockStatsRepo{}

	analyzer := NewPortfolioAnalyzer(krakenService, mockConfigService, mockStatsRepo)
	executor := NewOrderExecutor(krakenService, analyzer)
	portfolioManager := NewPortfolioManagerImpl(mockConfigService, mockHistory, analyzer, executor)

	err := portfolioManager.PerformRebalanceCycle()
	if err == nil {
		t.Error("Expected error during rebalance cycle with Bad Gateway, got nil")
	}
}

func TestResilienceChaos_NetworkError(t *testing.T) {
	appConfig := config.AppConfig{
		Kraken: config.KrakenCredentials{
			APIKey:     "apiKey",
			PrivateKey: base64.StdEncoding.EncodeToString([]byte("secret")),
		},
		Settings: config.Settings{
			LoopDelaySeconds:        60,
			DeviationTriggerPercent: d(2.0),
			DustThresholdUSD:        d(1.0),
			DryRun:                  false,
			FiatMaxDrawdown:         d(50.0),
			FiatDeploymentExponent:  d(1.0),
		},
		Allocations: []config.Allocation{
			{Symbol: "BTC", TargetPercent: d(50.0)},
		},
	}
	mockConfigService := &MockConfigService{cfg: appConfig}

	mockHttpClient := &MockHTTPClient{
		DoFunc: func(req *http.Request) (*http.Response, error) {
			return nil, errors.New("Connection reset by peer")
		},
	}

	krakenService := NewKrakenServiceImpl(mockConfigService, mockHttpClient)
	mockHistory := &MockHistoryService{}
	mockStatsRepo := &MockStatsRepo{}

	analyzer := NewPortfolioAnalyzer(krakenService, mockConfigService, mockStatsRepo)
	executor := NewOrderExecutor(krakenService, analyzer)
	portfolioManager := NewPortfolioManagerImpl(mockConfigService, mockHistory, analyzer, executor)

	err := portfolioManager.PerformRebalanceCycle()
	if err == nil {
		t.Error("Expected error during rebalance cycle with network failure, got nil")
	}
}

func TestResilienceChaos_NonceRetryExhausted(t *testing.T) {
	appConfig := config.AppConfig{
		Kraken: config.KrakenCredentials{
			APIKey:     "apiKey",
			PrivateKey: base64.StdEncoding.EncodeToString([]byte("secret")),
		},
	}
	mockConfigService := &MockConfigService{cfg: appConfig}

	callCount := 0
	mockHttpClient := &MockHTTPClient{
		DoFunc: func(req *http.Request) (*http.Response, error) {
			callCount++
			body := `{"error":["EAPI:Invalid nonce"],"result":null}`
			return makeResponse(http.StatusOK, body), nil
		},
	}

	krakenService := NewKrakenServiceImpl(mockConfigService, mockHttpClient)
	_, err := krakenService.GetBalances()
	if err == nil {
		t.Fatal("Expected error on invalid nonce retry loop exhaustion, got nil")
	}

	if callCount != 6 { // Initial try + 5 retries
		t.Errorf("Expected 6 calls to Kraken API (1 try + 5 retries), got %d", callCount)
	}
}
