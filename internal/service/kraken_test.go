package service

import (
	"bytes"
	"encoding/base64"
	"errors"
	"io"
	"net/http"
	"strings"
	"testing"

	"github.com/HyperVon/new-kraken-rebalancer/internal/config"
	"github.com/shopspring/decimal"
)

// MockHTTPClient helper
type MockHTTPClient struct {
	DoFunc func(req *http.Request) (*http.Response, error)
}

func (m *MockHTTPClient) Do(req *http.Request) (*http.Response, error) {
	if m.DoFunc != nil {
		return m.DoFunc(req)
	}
	return nil, errors.New("unimplemented mock")
}

// Helper to make response
func makeResponse(statusCode int, body string) *http.Response {
	return &http.Response{
		StatusCode: statusCode,
		Body:       io.NopCloser(bytes.NewBufferString(body)),
	}
}

func TestNewKrakenServiceImpl(t *testing.T) {
	s := NewKrakenServiceImpl(&MockConfigService{}, nil)
	if s.client != http.DefaultClient {
		t.Error("Expected default HTTP client to be assigned when nil")
	}
}

func TestConvertToFloat(t *testing.T) {
	v1, err := convertToFloat(float64(12.34))
	if err != nil || v1 != 12.34 {
		t.Errorf("Expected 12.34, got %v, err %v", v1, err)
	}

	v2, err := convertToFloat("56.78")
	if err != nil || v2 != 56.78 {
		t.Errorf("Expected 56.78, got %v, err %v", v2, err)
	}

	_, err = convertToFloat(123) // int
	if err == nil {
		t.Error("Expected error converting int to float")
	}
}

func TestGetBalances_Success(t *testing.T) {
	cfg := config.AppConfig{
		Kraken: config.KrakenCredentials{
			APIKey:     "key",
			PrivateKey: base64.StdEncoding.EncodeToString([]byte("secret")),
		},
	}
	cfgService := &MockConfigService{cfg: cfg}

	httpClient := &MockHTTPClient{
		DoFunc: func(req *http.Request) (*http.Response, error) {
			if req.Method != "POST" || !strings.Contains(req.URL.Path, "/0/private/Balance") {
				return nil, errors.New("wrong request")
			}
			body := `{"error":[],"result":{"ZUSD":1000.5,"XXBT":"0.12345"}}`
			return makeResponse(http.StatusOK, body), nil
		},
	}

	s := NewKrakenServiceImpl(cfgService, httpClient)
	balances, err := s.GetBalances()
	if err != nil {
		t.Fatalf("Expected no error, got %v", err)
	}

	if len(balances) != 2 {
		t.Errorf("Expected 2 assets, got %d", len(balances))
	}
	if balances["ZUSD"] != 1000.5 {
		t.Errorf("Expected ZUSD 1000.5, got %f", balances["ZUSD"])
	}
	if balances["XXBT"] != 0.12345 {
		t.Errorf("Expected XXBT 0.12345, got %f", balances["XXBT"])
	}
}

func TestGetBalances_Errors(t *testing.T) {
	cfg := config.AppConfig{
		Kraken: config.KrakenCredentials{
			APIKey:     "key",
			PrivateKey: base64.StdEncoding.EncodeToString([]byte("secret")),
		},
	}
	cfgService := &MockConfigService{cfg: cfg}

	// 1. Result is not a map
	httpClient := &MockHTTPClient{
		DoFunc: func(req *http.Request) (*http.Response, error) {
			body := `{"error":[],"result":["USD", "BTC"]}`
			return makeResponse(http.StatusOK, body), nil
		},
	}
	s := NewKrakenServiceImpl(cfgService, httpClient)
	_, err := s.GetBalances()
	if err == nil || !strings.Contains(err.Error(), "result is not a map") {
		t.Errorf("Expected 'result is not a map' error, got %v", err)
	}

	// 2. HTTP call fails
	httpClientErr := &MockHTTPClient{
		DoFunc: func(req *http.Request) (*http.Response, error) {
			return nil, errors.New("connection failed")
		},
	}
	s2 := NewKrakenServiceImpl(cfgService, httpClientErr)
	_, err = s2.GetBalances()
	if err == nil {
		t.Error("Expected connection error, got nil")
	}
}

func TestGetTickerPrices_Success(t *testing.T) {
	httpClient := &MockHTTPClient{
		DoFunc: func(req *http.Request) (*http.Response, error) {
			body := `{
				"error": [],
				"result": {
					"XXBTZUSD": {
						"a": ["68453.10000", "1", "1.000"],
						"b": ["68453.00000", "1", "1.000"],
						"c": ["68450.50000", "0.01234567"]
					},
					"XETHZUSD": {
						"c": ["3500.25"]
					},
					"INVALID": {
						"c": []
					},
					"INVALID_STRUCT": "not_a_map"
				}
			}`
			return makeResponse(http.StatusOK, body), nil
		},
	}

	s := NewKrakenServiceImpl(&MockConfigService{}, httpClient)
	prices, err := s.GetTickerPrices("XXBTZUSD,XETHZUSD")
	if err != nil {
		t.Fatalf("Expected no error, got %v", err)
	}

	if prices["XXBTZUSD"] != 68450.5 {
		t.Errorf("Expected XXBTZUSD 68450.5, got %f", prices["XXBTZUSD"])
	}
	if prices["XETHZUSD"] != 3500.25 {
		t.Errorf("Expected XETHZUSD 3500.25, got %f", prices["XETHZUSD"])
	}
	if _, exists := prices["INVALID"]; exists {
		t.Error("Expected INVALID price to be ignored")
	}
}

func TestGetTickerPrices_Errors(t *testing.T) {
	// 1. HTTP failure
	httpClientErr := &MockHTTPClient{
		DoFunc: func(req *http.Request) (*http.Response, error) {
			return nil, errors.New("network failure")
		},
	}
	s1 := NewKrakenServiceImpl(&MockConfigService{}, httpClientErr)
	_, err := s1.GetTickerPrices("BTC")
	if err == nil {
		t.Error("Expected network failure error, got nil")
	}

	// 2. Unmarshal error (bad json)
	httpClientBadJSON := &MockHTTPClient{
		DoFunc: func(req *http.Request) (*http.Response, error) {
			return makeResponse(http.StatusOK, "{bad json"), nil
		},
	}
	s2 := NewKrakenServiceImpl(&MockConfigService{}, httpClientBadJSON)
	_, err = s2.GetTickerPrices("BTC")
	if err == nil || !strings.Contains(err.Error(), "failed to parse public API") {
		t.Errorf("Expected unmarshal error, got %v", err)
	}

	// 3. API Error returned
	httpClientAPIErr := &MockHTTPClient{
		DoFunc: func(req *http.Request) (*http.Response, error) {
			body := `{"error":["EQuery:Unknown asset pair"],"result":{}}`
			return makeResponse(http.StatusOK, body), nil
		},
	}
	s3 := NewKrakenServiceImpl(&MockConfigService{}, httpClientAPIErr)
	_, err = s3.GetTickerPrices("BTC")
	if err == nil || !strings.Contains(err.Error(), "kraken public API error") {
		t.Errorf("Expected API error, got %v", err)
	}
}

func TestExecuteOrder_DryRun(t *testing.T) {
	cfg := config.AppConfig{
		Settings: config.Settings{
			DryRun: true,
		},
	}
	cfgService := &MockConfigService{cfg: cfg}

	s := NewKrakenServiceImpl(cfgService, nil)
	res, err := s.ExecuteOrder("XXBTZUSD", "market", "buy", decimal.NewFromFloat(0.123456789))
	if err != nil {
		t.Fatalf("Expected no error on dry-run, got %v", err)
	}

	if !res.DryRun {
		t.Error("Expected DryRun field to be true")
	}
	if !res.Success {
		t.Error("Expected Success to be true")
	}
	// Rounded to 8 decimals
	expectedVol := decimal.NewFromFloat(0.12345679)
	if !res.Volume.Equal(expectedVol) {
		t.Errorf("Expected volume %v, got %v", expectedVol, res.Volume)
	}
}

func TestExecuteOrder_Live(t *testing.T) {
	cfg := config.AppConfig{
		Kraken: config.KrakenCredentials{
			APIKey:     "key",
			PrivateKey: base64.StdEncoding.EncodeToString([]byte("secret")),
		},
		Settings: config.Settings{
			DryRun: false,
		},
	}
	cfgService := &MockConfigService{cfg: cfg}

	// Success case
	httpClient := &MockHTTPClient{
		DoFunc: func(req *http.Request) (*http.Response, error) {
			body := `{"error":[],"result":{"descr":{"order":"buy 0.12345678 XBTUSD"},"txid":["TXID123"]}}`
			return makeResponse(http.StatusOK, body), nil
		},
	}

	s := NewKrakenServiceImpl(cfgService, httpClient)
	res, err := s.ExecuteOrder("XXBTZUSD", "market", "buy", decimal.NewFromFloat(0.12345678))
	if err != nil {
		t.Fatalf("Expected no error, got %v", err)
	}
	if !res.Success || res.DryRun {
		t.Errorf("Expected order success, got %v", res)
	}

	// Failure case (API Error)
	httpClientAPIErr := &MockHTTPClient{
		DoFunc: func(req *http.Request) (*http.Response, error) {
			body := `{"error":["EOrder:Insufficient funds"],"result":{}}`
			return makeResponse(http.StatusOK, body), nil
		},
	}
	sErr := NewKrakenServiceImpl(cfgService, httpClientAPIErr)
	resErr, err := sErr.ExecuteOrder("XXBTZUSD", "market", "buy", decimal.NewFromFloat(0.12345678))
	if err == nil {
		t.Error("Expected execution error, got nil")
	}
	if resErr.Success {
		t.Error("Expected Success to be false")
	}
	if resErr.ErrorMessage != "kraken API error: EOrder:Insufficient funds" {
		t.Errorf("Expected Insufficient funds error message, got '%s'", resErr.ErrorMessage)
	}
}

func TestQueryPrivate_Errors(t *testing.T) {
	// 1. API key blank
	cfgService := &MockConfigService{cfg: config.AppConfig{}}
	s := NewKrakenServiceImpl(cfgService, nil)
	_, err := s.queryPrivate("/0/private/Balance", nil)
	if err == nil || !strings.Contains(err.Error(), "API Key is blank") {
		t.Errorf("Expected 'API Key is blank' error, got %v", err)
	}

	// 2. Private key decode failure (invalid base64)
	cfgBadKey := config.AppConfig{
		Kraken: config.KrakenCredentials{
			APIKey:     "key",
			PrivateKey: "invalid-base64-!!!",
		},
	}
	cfgServiceBad := &MockConfigService{cfg: cfgBadKey}
	sBadKey := NewKrakenServiceImpl(cfgServiceBad, nil)
	_, err = sBadKey.queryPrivate("/0/private/Balance", nil)
	if err == nil || !strings.Contains(err.Error(), "failed to decode private key") {
		t.Errorf("Expected private key decode error, got %v", err)
	}

	// 3. Response ReadAll failure
	cfgValid := config.AppConfig{
		Kraken: config.KrakenCredentials{
			APIKey:     "key",
			PrivateKey: base64.StdEncoding.EncodeToString([]byte("secret")),
		},
	}
	cfgServiceValid := &MockConfigService{cfg: cfgValid}
	httpClientReadFail := &MockHTTPClient{
		DoFunc: func(req *http.Request) (*http.Response, error) {
			// A response body that fails to read
			return &http.Response{
				StatusCode: http.StatusOK,
				Body:       io.NopCloser(errReader{}),
			}, nil
		},
	}
	sReadFail := NewKrakenServiceImpl(cfgServiceValid, httpClientReadFail)
	_, err = sReadFail.queryPrivate("/0/private/Balance", nil)
	if err == nil || !strings.Contains(err.Error(), "read error") {
		t.Errorf("Expected read error, got %v", err)
	}

	// 4. Bad JSON response
	httpClientBadJSON := &MockHTTPClient{
		DoFunc: func(req *http.Request) (*http.Response, error) {
			return makeResponse(http.StatusOK, "{bad json"), nil
		},
	}
	sBadJSON := NewKrakenServiceImpl(cfgServiceValid, httpClientBadJSON)
	_, err = sBadJSON.queryPrivate("/0/private/Balance", nil)
	if err == nil || !strings.Contains(err.Error(), "failed to parse JSON response") {
		t.Errorf("Expected JSON parse error, got %v", err)
	}
}

type errReader struct{}

func (errReader) Read(p []byte) (n int, err error) {
	return 0, errors.New("mock read error")
}

func TestQueryPrivate_NonceRetry(t *testing.T) {
	cfg := config.AppConfig{
		Kraken: config.KrakenCredentials{
			APIKey:     "key",
			PrivateKey: base64.StdEncoding.EncodeToString([]byte("secret")),
		},
	}
	cfgService := &MockConfigService{cfg: cfg}

	callCount := 0
	httpClient := &MockHTTPClient{
		DoFunc: func(req *http.Request) (*http.Response, error) {
			callCount++
			if callCount == 1 {
				// Return Invalid Nonce
				return makeResponse(http.StatusOK, `{"error":["EAPI:Invalid nonce"],"result":null}`), nil
			}
			// Success on second try
			return makeResponse(http.StatusOK, `{"error":[],"result":{"status":"success"}}`), nil
		},
	}

	s := NewKrakenServiceImpl(cfgService, httpClient)
	res, err := s.queryPrivate("/0/private/Balance", nil)
	if err != nil {
		t.Fatalf("Expected successful retry, got error: %v", err)
	}

	if callCount != 2 {
		t.Errorf("Expected 2 HTTP calls (1 retry), got %d", callCount)
	}

	resultMap, ok := res.(map[string]interface{})
	if !ok || resultMap["status"] != "success" {
		t.Errorf("Expected success response, got %v", res)
	}
}

func TestQueryPrivate_NonceRetryFailure(t *testing.T) {
	cfg := config.AppConfig{
		Kraken: config.KrakenCredentials{
			APIKey:     "key",
			PrivateKey: base64.StdEncoding.EncodeToString([]byte("secret")),
		},
	}
	cfgService := &MockConfigService{cfg: cfg}

	httpClient := &MockHTTPClient{
		DoFunc: func(req *http.Request) (*http.Response, error) {
			// Always return Invalid Nonce
			return makeResponse(http.StatusOK, `{"error":["EAPI:Invalid nonce"],"result":null}`), nil
		},
	}

	s := NewKrakenServiceImpl(cfgService, httpClient)
	_, err := s.queryPrivate("/0/private/Balance", nil)
	if err == nil || !strings.Contains(err.Error(), "EAPI:Invalid nonce") {
		t.Errorf("Expected eventual EAPI:Invalid nonce failure, got %v", err)
	}
}

func TestQueryPublic_ReadError(t *testing.T) {
	httpClient := &MockHTTPClient{
		DoFunc: func(req *http.Request) (*http.Response, error) {
			return &http.Response{
				StatusCode: http.StatusOK,
				Body:       io.NopCloser(errReader{}),
			}, nil
		},
	}
	s := NewKrakenServiceImpl(&MockConfigService{}, httpClient)
	_, err := s.queryPublic("/0/public/Ticker")
	if err == nil {
		t.Error("Expected read error on public query, got nil")
	}
}
