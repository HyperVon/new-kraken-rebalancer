package config

import (
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"strings"
	"sync"

	"github.com/HyperVon/new-kraken-rebalancer/internal/model"
	"github.com/HyperVon/new-kraken-rebalancer/internal/repository"
	"github.com/shopspring/decimal"
)

// KrakenCredentials holds API access keys.
type KrakenCredentials struct {
	APIKey     string `json:"apiKey"`
	PrivateKey string `json:"privateKey"`
}

// Settings holds rebalancing parameters.
type Settings struct {
	LoopDelaySeconds        int64           `json:"loopDelaySeconds"`
	DeviationTriggerPercent decimal.Decimal `json:"deviationTriggerPercent"`
	DustThresholdUSD        decimal.Decimal `json:"dustThresholdUSD"`
	DryRun                  bool            `json:"dryRun"`
	FiatMaxDrawdown         decimal.Decimal `json:"fiatMaxDrawdown"`
	FiatDeploymentExponent  decimal.Decimal `json:"fiatDeploymentExponent"`
}

// Allocation maps an asset symbol to its target portfolio percentage.
type Allocation struct {
	Symbol        model.Asset     `json:"symbol"`
	TargetPercent decimal.Decimal `json:"targetPercent"`
}

// AppConfig is the root configuration structure.
type AppConfig struct {
	Kraken      KrakenCredentials `json:"kraken"`
	Settings    Settings          `json:"settings"`
	Allocations []Allocation      `json:"allocations"`
}

// ConfigService manages application configuration.
type ConfigService interface {
	LoadConfig() error
	GetConfig() AppConfig
	UpdateConfig(newConfig AppConfig) error
}

// FileConfigService implements ConfigService with a JSON file.
type FileConfigService struct {
	mu             sync.RWMutex
	configFilePath string
	appConfig      AppConfig
}

// NewFileConfigService creates a new FileConfigService.
func NewFileConfigService(configFilePath string) (*FileConfigService, error) {
	s := &FileConfigService{configFilePath: configFilePath}
	if err := s.LoadConfig(); err != nil {
		return nil, err
	}
	return s, nil
}

func (s *FileConfigService) LoadConfig() error {
	s.mu.Lock()
	defer s.mu.Unlock()

	if _, err := os.Stat(s.configFilePath); os.IsNotExist(err) {
		return fmt.Errorf("configuration file '%s' not found in the application directory", s.configFilePath)
	}

	data, err := os.ReadFile(s.configFilePath)
	if err != nil {
		return fmt.Errorf("failed to read configuration file: %w", err)
	}

	var cfg AppConfig
	if err := json.Unmarshal(data, &cfg); err != nil {
		return fmt.Errorf("failed to parse configuration JSON: %w", err)
	}

	if err := validateConfig(cfg); err != nil {
		return err
	}

	s.appConfig = cfg
	return nil
}

func (s *FileConfigService) GetConfig() AppConfig {
	s.mu.RLock()
	defer s.mu.RUnlock()
	return s.appConfig
}

func (s *FileConfigService) UpdateConfig(newConfig AppConfig) error {
	s.mu.Lock()
	defer s.mu.Unlock()

	if err := validateConfig(newConfig); err != nil {
		return err
	}

	if err := repository.WriteAtomicJSON(s.configFilePath, newConfig); err != nil {
		return fmt.Errorf("failed to save configuration: %w", err)
	}

	s.appConfig = newConfig
	return nil
}

var (
	zero    = decimal.Zero
	hundred = decimal.NewFromInt(100)
)

func validateConfig(cfg AppConfig) error {
	settings := cfg.Settings

	if settings.LoopDelaySeconds <= 0 {
		return errors.New("loop delay must be a positive integer")
	}
	if settings.DeviationTriggerPercent.LessThan(zero) {
		return errors.New("deviation trigger percent must be non-negative")
	}
	if settings.DustThresholdUSD.LessThan(zero) {
		return errors.New("dust threshold USD must be non-negative")
	}
	if settings.FiatMaxDrawdown.LessThan(zero) || settings.FiatMaxDrawdown.GreaterThan(hundred) {
		return errors.New("fiat max drawdown must be between 0% and 100%")
	}
	if settings.FiatDeploymentExponent.LessThanOrEqual(zero) {
		return errors.New("fiat deployment exponent must be positive")
	}

	if len(cfg.Allocations) == 0 {
		return errors.New("at least one allocation is required")
	}

	symbolsSeen := make(map[string]bool)
	var duplicateSymbols []string
	totalPercent := decimal.Zero
	hasUsd := false

	for _, alloc := range cfg.Allocations {
		sym := strings.ToUpper(strings.TrimSpace(string(alloc.Symbol)))
		if sym == "" {
			return errors.New("allocation symbols cannot be blank")
		}
		if alloc.TargetPercent.LessThan(zero) {
			return fmt.Errorf("target percent for %s cannot be negative", alloc.Symbol)
		}
		if symbolsSeen[sym] {
			duplicateSymbols = append(duplicateSymbols, sym)
		}
		symbolsSeen[sym] = true
		totalPercent = totalPercent.Add(alloc.TargetPercent)
		if alloc.Symbol.IsUSD() {
			hasUsd = true
		}
	}

	if len(duplicateSymbols) > 0 {
		return fmt.Errorf("duplicate allocation symbols are not allowed: %s", strings.Join(duplicateSymbols, ", "))
	}

	if totalPercent.Sub(hundred).Abs().GreaterThan(decimal.NewFromFloat(0.001)) {
		return fmt.Errorf("total allocation percentage must be exactly 100%%. Current sum: %s", totalPercent.StringFixed(6))
	}

	if !hasUsd {
		return errors.New("one asset must be USD")
	}

	return nil
}
