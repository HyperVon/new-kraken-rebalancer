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
	"log"
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
	// nonce must be accessed atomically using the sync/atomic package
	// because multiple goroutines might run rebalance/ticker loops concurrently.
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
		// Initialize the nonce to current unix timestamp in microseconds.
		nonce: time.Now().UnixNano() / int64(time.Microsecond),
	}
}

func (s *KrakenServiceImpl) GetBalances() (RawBalances, error) {
	path := fmt.Sprintf("/%s/private/Balance", s.apiVersion)
	resultVal, err := s.queryPrivate(path, nil)
	if err != nil {
		return nil, err
	}

	resultMap, ok := resultVal.(map[string]interface{})
	if !ok {
		return nil, errors.New("invalid response structure for balances: result is not a map")
	}

	balances := make(RawBalances)
	for k, v := range resultMap {
		valFloat, err := convertToFloat(v)
		if err != nil {
			log.Printf("Warning: failed to convert balance for %s: %v", k, err)
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
		Error  []string               `json:"error"`
		Result map[string]interface{} `json:"result"`
	}

	if err := json.Unmarshal(responseBody, &root); err != nil {
		return nil, fmt.Errorf("failed to parse public API response: %w", err)
	}

	if len(root.Error) > 0 {
		return nil, fmt.Errorf("kraken public API error: %s", strings.Join(root.Error, ", "))
	}

	prices := make(RawPrices)
	for key, value := range root.Result {
		pairMap, ok := value.(map[string]interface{})
		if !ok {
			continue
		}
		cVal, exists := pairMap["c"]
		if !exists {
			continue
		}
		cSlice, ok := cVal.([]interface{})
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
	// Kraken requires rounding to 8 decimal places for crypto volumes.
	normalizedVolume := volume.Round(8).Truncate(8)
	cfg := s.configService.GetConfig()

	if cfg.Settings.DryRun {
		log.Printf("[DRY RUN] Would execute order: %s %s %s volume=%s", orderType, side, pair, normalizedVolume.String())
		return model.NewOrderResult(true, pair, side, normalizedVolume, true, ""), nil
	}

	path := fmt.Sprintf("/%s/private/AddOrder", s.apiVersion)
	params := url.Values{}
	params.Set("pair", pair)
	params.Set("type", side)
	params.Set("ordertype", orderType)
	params.Set("volume", normalizedVolume.String())

	_, err := s.queryPrivate(path, params)
	if err != nil {
		log.Printf("Failed to execute order: %s %s %s volume=%s: %v", orderType, side, pair, normalizedVolume.String(), err)
		return model.NewOrderResult(false, pair, side, normalizedVolume, false, err.Error()), err
	}

	log.Printf("Order Executed: %s %s %s volume=%s", orderType, side, pair, normalizedVolume.String())
	return model.NewOrderResult(true, pair, side, normalizedVolume, false, ""), nil
}

func (s *KrakenServiceImpl) queryPublic(path string) ([]byte, error) {
	resp, err := s.client.Do(&http.Request{
		Method: "GET",
		URL: &url.URL{
			Scheme: "https",
			Host:   "api.kraken.com",
			Path:   path,
		},
	})
	if err != nil {
		return nil, fmt.Errorf("failed public API connection: %w", err)
	}
	// defer resp.Body.Close() ensures the response stream is cleaned up
	// once queryPublic exits, preventing memory/socket leaks.
	defer resp.Body.Close()

	bodyBytes, err := io.ReadAll(resp.Body)
	if err != nil {
		return nil, err
	}
	return bodyBytes, nil
}

func (s *KrakenServiceImpl) queryPrivate(path string, params url.Values) (interface{}, error) {
	cfg := s.configService.GetConfig()
	apiKey := cfg.Kraken.APIKey
	if apiKey == "" {
		return nil, errors.New("API Key is blank")
	}

	maxRetries := 5
	retryCount := 0

	if params == nil {
		params = url.Values{}
	}

	for {
		// atomic.AddInt64 dynamically increments the 64-bit nonce by 1 safely
		// in a multi-threaded execution context (similar to Java's AtomicLong).
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
		req, err := http.NewRequest("POST", reqURL, strings.NewReader(postData))
		if err != nil {
			return nil, err
		}

		req.Header.Set("API-Key", apiKey)
		req.Header.Set("API-Sign", signature)
		req.Header.Set("Content-Type", "application/x-www-form-urlencoded")

		resp, err := s.client.Do(req)
		if err != nil {
			return nil, fmt.Errorf("failed private API connection: %w", err)
		}
		defer resp.Body.Close()

		bodyBytes, err := io.ReadAll(resp.Body)
		if err != nil {
			return nil, err
		}

		var root struct {
			Error  []string    `json:"error"`
			Result interface{} `json:"result"`
		}

		if err := json.Unmarshal(bodyBytes, &root); err != nil {
			return nil, fmt.Errorf("failed to parse JSON response: %w", err)
		}

		if len(root.Error) > 0 {
			errorMsg := strings.Join(root.Error, ", ")
			// If Kraken rejects the transaction due to an out-of-order nonce timestamp error,
			// we bump our local atomic nonce generator exponentially and retry the request up to 5 times.
			if strings.Contains(errorMsg, "Invalid nonce") && retryCount < maxRetries {
				// (1 << retryCount) is a bitwise shift performing exponential scaling (e.g. 1, 2, 4, 8, 16)
				bumpAmount := int64(100_000_000 * (1 << retryCount))
				log.Printf("Invalid nonce detected. Adjusting nonce generator by %d and retrying (Attempt %d/%d)", bumpAmount, retryCount+1, maxRetries)
				atomic.AddInt64(&s.nonce, bumpAmount)
				retryCount++
				continue
			}
			return nil, fmt.Errorf("kraken API error: %s", errorMsg)
		}

		return root.Result, nil
	}
}

// signRequest generates Kraken's specific API authentication signature.
// Formula: base64(hmac_sha512(url_path + sha256(nonce + post_data), base64_decoded(private_key)))
func (s *KrakenServiceImpl) signRequest(path string, nonce string, postData string) (string, error) {
	cfg := s.configService.GetConfig()
	privateKeyDecoded, err := base64.StdEncoding.DecodeString(cfg.Kraken.PrivateKey)
	if err != nil {
		return "", fmt.Errorf("failed to decode private key: %w", err)
	}

	// Step 1: sha256(nonce + postData)
	sha := sha256.New()
	sha.Write([]byte(nonce + postData))
	shaSum := sha.Sum(nil)

	// Step 2: hmac_sha512(path + shaSum, privateKey)
	mac := hmac.New(sha512.New, privateKeyDecoded)
	mac.Write([]byte(path))
	mac.Write(shaSum)
	macSum := mac.Sum(nil)

	// Step 3: base64_encode(hmacSum)
	return base64.StdEncoding.EncodeToString(macSum), nil
}

// convertToFloat converts dynamic interfaces (string or float64) to float64.
// Go requires explicit type assertions (switch v.(type)) because it does not support implicit casting.
func convertToFloat(v interface{}) (float64, error) {
	switch val := v.(type) {
	case float64:
		return val, nil
	case string:
		return strconv.ParseFloat(val, 64)
	default:
		return 0, fmt.Errorf("unexpected type %T for float conversion", v)
	}
}
