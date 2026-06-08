package service

import (
	"bytes"
	"encoding/base64"
	"io"
	"net/http"
	"regexp"
	"strings"
	"testing"

	"github.com/HyperVon/new-kraken-rebalancer/internal/config"
	"github.com/HyperVon/new-kraken-rebalancer/internal/model"
	"github.com/shopspring/decimal"
)

func TestPrecisionRoundingFuzz(t *testing.T) {
	validSecret := base64.StdEncoding.EncodeToString([]byte("secret"))
	appConfig := config.AppConfig{
		Kraken: config.KrakenCredentials{
			APIKey:     "apiKey",
			PrivateKey: validSecret,
		},
		Settings: config.Settings{
			LoopDelaySeconds:        60,
			DeviationTriggerPercent: 2.0,
			DustThresholdUSD:        1.0,
			DryRun:                  false,
			FiatMaxDrawdown:         50.0,
			FiatDeploymentExponent:  1.0,
		},
		Allocations: []config.Allocation{
			{Symbol: "BTC", TargetPercent: 50.0},
			{Symbol: "USD", TargetPercent: 50.0},
		},
	}

	mockConfigService := &MockConfigService{cfg: appConfig}

	var capturedOrderPayload string

	mockHttpClient := &MockHTTPClient{
		DoFunc: func(req *http.Request) (*http.Response, error) {
			path := req.URL.Path
			if strings.Contains(path, "/0/private/Balance") {
				body := `{"error":[],"result":{"XXBT":0.3333333333333333,"ZUSD":31415.9265358979323846}}`
				return makeResponse(http.StatusOK, body), nil
			}
			if strings.Contains(path, "/0/public/Ticker") {
				body := `{"error":[],"result":{"XXBTZUSD":{"c":["68453.123456789"]}}}`
				return makeResponse(http.StatusOK, body), nil
			}
			if strings.Contains(path, "/0/private/AddOrder") {
				buf := new(bytes.Buffer)
				_, _ = io.Copy(buf, req.Body)
				capturedOrderPayload = buf.String()
				body := `{"error":[],"result":{"descr":{"order":"buy"},"txid":["TX-1"]}}`
				return makeResponse(http.StatusOK, body), nil
			}
			return makeResponse(http.StatusNotFound, `{"error":["Unknown path"]}`), nil
		},
	}

	krakenService := NewKrakenServiceImpl(mockConfigService, mockHttpClient)
	krakenService.apiURL = "https://api.kraken.com"

	mockHistory := &MockHistoryService{}
	mockStatsRepo := &MockStatsRepo{stats: model.PortfolioStats{AllTimeHigh: decimal.Zero}}

	analyzer := NewPortfolioAnalyzer(krakenService, mockConfigService, mockStatsRepo)
	executor := NewOrderExecutor(krakenService, analyzer)
	portfolioManager := NewPortfolioManagerImpl(mockConfigService, mockHistory, analyzer, executor)

	err := portfolioManager.PerformRebalanceCycle()
	if err != nil {
		t.Fatalf("Expected no error during rebalance cycle, got: %v", err)
	}

	if capturedOrderPayload == "" {
		t.Fatalf("Expected AddOrder call, but it was not captured")
	}

	// Verify that the volume parameter is rounded to 1 to 8 decimal places and doesn't have a giant precision
	re := regexp.MustCompile(`volume=(\d+\.\d{1,8})(&|$)`)
	match := re.FindStringSubmatch(capturedOrderPayload)
	if len(match) == 0 {
		t.Fatalf("Expected volume to match regex with max 8 decimal places, but got payload: %s", capturedOrderPayload)
	}
}
