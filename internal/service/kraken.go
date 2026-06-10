package service

import (
	"crypto/hmac"
	"crypto/sha256"
	"crypto/sha512"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"net/http"
	"net/url"
	"strconv"
	"strings"
	"sync/atomic"
	"time"

	"github.com/HyperVon/new-kraken-rebalancer/internal/config"
	"github.com/HyperVon/new-kraken-rebalancer/internal/model"
	"github.com/shopspring/decimal"
)

// RawBalances holds asset keys mapped to their balance.
type RawBalances map[string]float64

// RawPrices holds asset pair keys mapped to their current market price.
type RawPrices map[string]float64

// KrakenService defines operations targeting Kraken API.
type KrakenService interface {
	GetBalances() (RawBalances, error)
	GetTickerPrices(pairs string) (RawPrices, error)
	ExecuteOrder(pair, orderType, side string, volume decimal.Decimal) (model.OrderResult, error)
}

// HTTPClient interface to support testing mocks.
type HTTPClient interface {
	Do(req *http.Request) (*http.Response, error)
}

// KrakenServiceImpl implements KrakenService.
type KrakenServiceImpl struct {
	configService config.ConfigService
	client        HTTPClient
	apiURL        string
	apiVersion    string
	// nonce must be accessed atomically — concurrent goroutines may run rebalance loops.
	nonce int64
}

// NewKrakenServiceImpl creates a new KrakenServiceImpl.
func NewKrakenServiceImpl(configService config.ConfigService, client HTTPClient) *KrakenServiceImpl {
	if client == nil {
		client = http.DefaultClient
	}
	return &KrakenServiceImpl{
		configService: configService,
		client:        client,
		apiURL:        "https://api.kraken.com",
		apiVersion:    "0",
		nonce:         time.Now().UnixNano() / int64(time.Microsecond),
	}
}

func (s *KrakenServiceImpl) GetBalances() (RawBalances, error) {
	path := fmt.Sprintf("/%s/private/Balance", s.apiVersion)
	resultVal, err := s.queryPrivate(path, nil)
	if err != nil {
		return nil, err
	}

	resultMap, ok := resultVal.(map[string]any)
	if !ok {
		return nil, errors.New("invalid response structure for balances: result is not a map")
	}

	balances := make(RawBalances, len(resultMap))
	for k, v := range resultMap {
		valFloat, err := convertToFloat(v)
		if err != nil {
			slog.Warn("Failed to convert balance", "asset", k, "error", err)
			continue
		}
		balances[k] = valFloat
	}
	return balances, nil
}

func (s *KrakenServiceImpl) GetTickerPrices(pairs string) (RawPrices, error) {
	path := fmt.Sprintf("/%s/public/Ticker?pair=%s", s.apiVersion, url.QueryEscape(pairs))
	responseBody, err := s.queryPublic(path)
	if err != nil {
		return nil, err
	}

	var root struct {
		Error  []string       `json:"error"`
		Result map[string]any `json:"result"`
	}

	if err := json.Unmarshal(responseBody, &root); err != nil {
		return nil, fmt.Errorf("failed to parse public API response: %w", err)
	}

	if len(root.Error) > 0 {
		return nil, fmt.Errorf("kraken public API error: %s", strings.Join(root.Error, ", "))
	}

	prices := make(RawPrices, len(root.Result))
	for key, value := range root.Result {
		pairMap, ok := value.(map[string]any)
		if !ok {
			continue
		}
		cVal, exists := pairMap["c"]
		if !exists {
			continue
		}
		cSlice, ok := cVal.([]any)
		if !ok || len(cSlice) == 0 {
			continue
		}
		priceFloat, err := convertToFloat(cSlice[0])
		if err == nil {
			prices[key] = priceFloat
		}
	}
	return prices, nil
}

func (s *KrakenServiceImpl) ExecuteOrder(pair, orderType, side string, volume decimal.Decimal) (model.OrderResult, error) {
	normalizedVolume := volume.Round(8).Truncate(8)
	cfg := s.configService.GetConfig()

	if cfg.Settings.DryRun {
		slog.Info("Dry run order", "type", orderType, "side", side, "pair", pair, "volume", normalizedVolume)
		return model.OrderResult{
			Success: true,
			Pair:    pair,
			Side:    side,
			Volume:  normalizedVolume,
			DryRun:  true,
		}, nil
	}

	path := fmt.Sprintf("/%s/private/AddOrder", s.apiVersion)
	params := url.Values{}
	params.Set("pair", pair)
	params.Set("type", side)
	params.Set("ordertype", orderType)
	params.Set("volume", normalizedVolume.String())

	_, err := s.queryPrivate(path, params)
	if err != nil {
		slog.Error("Order execution failed", "type", orderType, "side", side, "pair", pair, "volume", normalizedVolume, "error", err)
		return model.OrderResult{
			Success:      false,
			Pair:         pair,
			Side:         side,
			Volume:       normalizedVolume,
			ErrorMessage: err.Error(),
		}, err
	}

	slog.Info("Order executed", "type", orderType, "side", side, "pair", pair, "volume", normalizedVolume)
	return model.OrderResult{
		Success: true,
		Pair:    pair,
		Side:    side,
		Volume:  normalizedVolume,
	}, nil
}

func (s *KrakenServiceImpl) queryPublic(path string) ([]byte, error) {
	reqURL := s.apiURL + path
	req, err := http.NewRequest(http.MethodGet, reqURL, nil)
	if err != nil {
		return nil, fmt.Errorf("failed to create public request: %w", err)
	}
	resp, err := s.client.Do(req)
	if err != nil {
		return nil, fmt.Errorf("failed public API connection: %w", err)
	}
	defer resp.Body.Close()

	return io.ReadAll(resp.Body)
}

// queryPrivate sends an authenticated request to Kraken and handles nonce-retry logic.
// If Kraken rejects a request with "Invalid nonce", the nonce is bumped exponentially
// and the request is retried up to 5 times.
func (s *KrakenServiceImpl) queryPrivate(path string, params url.Values) (any, error) {
	cfg := s.configService.GetConfig()
	apiKey := cfg.Kraken.APIKey
	if apiKey == "" {
		return nil, errors.New("API Key is blank")
	}

	const maxRetries = 5
	retryCount := 0

	if params == nil {
		params = url.Values{}
	}

	for {
		currentNonce := atomic.AddInt64(&s.nonce, 1)
		nonceStr := strconv.FormatInt(currentNonce, 10)

		payload := url.Values{}
		for k, valSlice := range params {
			for _, v := range valSlice {
				payload.Add(k, v)
			}
		}
		payload.Set("nonce", nonceStr)
		postData := payload.Encode()

		signature, err := s.signRequest(path, nonceStr, postData)
		if err != nil {
			return nil, fmt.Errorf("failed to sign request: %w", err)
		}

		reqURL := s.apiURL + path
		req, err := http.NewRequest(http.MethodPost, reqURL, strings.NewReader(postData))
		if err != nil {
			return nil, err
		}

		req.Header.Set("API-Key", apiKey)
		req.Header.Set("API-Sign", signature)
		req.Header.Set("Content-Type", "application/x-www-form-urlencoded")

		result, shouldRetry, err := s.doPrivateRequest(req, retryCount, maxRetries)
		if shouldRetry {
			retryCount++
			continue
		}
		return result, err
	}
}

// doPrivateRequest executes one private API call and returns (result, shouldRetry, error).
// Extracting this ensures resp.Body is closed at function exit — not deferred inside a loop.
func (s *KrakenServiceImpl) doPrivateRequest(req *http.Request, retryCount, maxRetries int) (any, bool, error) {
	resp, err := s.client.Do(req)
	if err != nil {
		return nil, false, fmt.Errorf("failed private API connection: %w", err)
	}
	defer resp.Body.Close()

	bodyBytes, err := io.ReadAll(resp.Body)
	if err != nil {
		return nil, false, err
	}

	var root struct {
		Error  []string `json:"error"`
		Result any      `json:"result"`
	}

	if err := json.Unmarshal(bodyBytes, &root); err != nil {
		return nil, false, fmt.Errorf("failed to parse JSON response: %w", err)
	}

	if len(root.Error) > 0 {
		errorMsg := strings.Join(root.Error, ", ")

		if strings.Contains(errorMsg, "Invalid nonce") && retryCount < maxRetries {
			bumpAmount := int64(100_000_000 * (1 << retryCount))
			slog.Warn("Invalid nonce, retrying",
				"bumpAmount", bumpAmount,
				"attempt", retryCount+1,
				"maxRetries", maxRetries,
			)
			atomic.AddInt64(&s.nonce, bumpAmount)
			return nil, true, nil
		}
		return nil, false, fmt.Errorf("kraken API error: %s", errorMsg)
	}

	return root.Result, false, nil
}

// signRequest generates Kraken's API authentication signature:
// base64(hmac_sha512(url_path + sha256(nonce + post_data), base64_decoded(private_key)))
func (s *KrakenServiceImpl) signRequest(path, nonce, postData string) (string, error) {
	cfg := s.configService.GetConfig()
	privateKeyDecoded, err := base64.StdEncoding.DecodeString(cfg.Kraken.PrivateKey)
	if err != nil {
		return "", fmt.Errorf("failed to decode private key: %w", err)
	}

	sha := sha256.New()
	sha.Write([]byte(nonce + postData))
	shaSum := sha.Sum(nil)

	mac := hmac.New(sha512.New, privateKeyDecoded)
	mac.Write([]byte(path))
	mac.Write(shaSum)
	macSum := mac.Sum(nil)

	return base64.StdEncoding.EncodeToString(macSum), nil
}

// convertToFloat converts dynamic JSON values (string or float64) to float64.
func convertToFloat(v any) (float64, error) {
	switch val := v.(type) {
	case float64:
		return val, nil
	case string:
		return strconv.ParseFloat(val, 64)
	default:
		return 0, fmt.Errorf("unexpected type %T for float conversion", v)
	}
}
