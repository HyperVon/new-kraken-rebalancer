package config

import (
	"os"
	"path/filepath"
	"testing"
)

func TestValidateConfig(t *testing.T) {
	validConfig := AppConfig{
		Settings: Settings{
			LoopDelaySeconds:        60,
			DeviationTriggerPercent: 5.0,
			DustThresholdUSD:        5.0,
			FiatMaxDrawdown:         30.0,
			FiatDeploymentExponent:  1.0,
		},
		Allocations: []Allocation{
			{Symbol: "USD", TargetPercent: 10.0},
			{Symbol: "BTC", TargetPercent: 90.0},
		},
	}

	// Should pass
	if err := validateConfig(validConfig); err != nil {
		t.Errorf("Expected valid config to pass, got error: %v", err)
	}

	// Test LoopDelaySeconds
	invalid := validConfig
	invalid.Settings.LoopDelaySeconds = 0
	if err := validateConfig(invalid); err == nil {
		t.Error("Expected error for loop delay <= 0")
	}

	// Test DeviationTriggerPercent
	invalid = validConfig
	invalid.Settings.DeviationTriggerPercent = -0.1
	if err := validateConfig(invalid); err == nil {
		t.Error("Expected error for negative deviation trigger")
	}

	// Test DustThresholdUSD
	invalid = validConfig
	invalid.Settings.DustThresholdUSD = -5.0
	if err := validateConfig(invalid); err == nil {
		t.Error("Expected error for negative dust threshold")
	}

	// Test FiatMaxDrawdown negative
	invalid = validConfig
	invalid.Settings.FiatMaxDrawdown = -1.0
	if err := validateConfig(invalid); err == nil {
		t.Error("Expected error for negative fiat max drawdown")
	}

	// Test FiatMaxDrawdown > 100
	invalid = validConfig
	invalid.Settings.FiatMaxDrawdown = 101.0
	if err := validateConfig(invalid); err == nil {
		t.Error("Expected error for fiat max drawdown > 100")
	}

	// Test FiatDeploymentExponent
	invalid = validConfig
	invalid.Settings.FiatDeploymentExponent = 0
	if err := validateConfig(invalid); err == nil {
		t.Error("Expected error for non-positive deployment exponent")
	}

	// Test empty allocations
	invalid = validConfig
	invalid.Allocations = []Allocation{}
	if err := validateConfig(invalid); err == nil {
		t.Error("Expected error for empty allocations")
	}

	// Test blank symbols
	invalid = validConfig
	invalid.Allocations = []Allocation{
		{Symbol: "", TargetPercent: 10.0},
		{Symbol: "BTC", TargetPercent: 90.0},
	}
	if err := validateConfig(invalid); err == nil {
		t.Error("Expected error for blank symbol")
	}

	// Test negative allocation target
	invalid = validConfig
	invalid.Allocations = []Allocation{
		{Symbol: "USD", TargetPercent: 110.0},
		{Symbol: "BTC", TargetPercent: -10.0},
	}
	if err := validateConfig(invalid); err == nil {
		t.Error("Expected error for negative allocation target")
	}

	// Test duplicate symbols
	invalid = validConfig
	invalid.Allocations = []Allocation{
		{Symbol: "USD", TargetPercent: 10.0},
		{Symbol: "BTC", TargetPercent: 45.0},
		{Symbol: "BTC", TargetPercent: 45.0},
	}
	if err := validateConfig(invalid); err == nil {
		t.Error("Expected error for duplicate allocation symbols")
	}

	// Test total allocation sum != 100
	invalid = validConfig
	invalid.Allocations = []Allocation{
		{Symbol: "USD", TargetPercent: 10.0},
		{Symbol: "BTC", TargetPercent: 80.0},
	}
	if err := validateConfig(invalid); err == nil {
		t.Error("Expected error for total allocation sum != 100")
	}

	// Test missing USD
	invalid = validConfig
	invalid.Allocations = []Allocation{
		{Symbol: "BTC", TargetPercent: 50.0},
		{Symbol: "ETH", TargetPercent: 50.0},
	}
	if err := validateConfig(invalid); err == nil {
		t.Error("Expected error for missing USD asset")
	}
}

func TestFileConfigService(t *testing.T) {
	tempDir, err := os.MkdirTemp("", "rebalancer-test-*")
	if err != nil {
		t.Fatalf("Failed to create temp dir: %v", err)
	}
	defer os.RemoveAll(tempDir)

	filePath := filepath.Join(tempDir, "config.json")

	// 1. Loading non-existent file
	_, err = NewFileConfigService(filePath)
	if err == nil {
		t.Error("Expected error when loading missing config file, got nil")
	}

	// 2. Loading bad JSON
	err = os.WriteFile(filePath, []byte("{bad json"), 0644)
	if err != nil {
		t.Fatalf("Failed to write test config: %v", err)
	}
	_, err = NewFileConfigService(filePath)
	if err == nil {
		t.Error("Expected error when loading bad JSON, got nil")
	}

	// 3. Loading invalid configuration properties (fails validation)
	invalidJSON := `{
		"settings": {
			"loopDelaySeconds": 0
		}
	}`
	err = os.WriteFile(filePath, []byte(invalidJSON), 0644)
	if err != nil {
		t.Fatalf("Failed to write test config: %v", err)
	}
	_, err = NewFileConfigService(filePath)
	if err == nil {
		t.Error("Expected validation error on load, got nil")
	}

	// 4. Loading valid config
	validJSON := `{
		"kraken": {
			"apiKey": "key",
			"privateKey": "secret"
		},
		"settings": {
			"loopDelaySeconds": 30,
			"deviationTriggerPercent": 1.5,
			"dustThresholdUSD": 1.0,
			"dryRun": true,
			"fiatMaxDrawdown": 10.0,
			"fiatDeploymentExponent": 1.0
		},
		"allocations": [
			{"symbol": "USD", "targetPercent": 20.0},
			{"symbol": "BTC", "targetPercent": 80.0}
		]
	}`
	err = os.WriteFile(filePath, []byte(validJSON), 0644)
	if err != nil {
		t.Fatalf("Failed to write test config: %v", err)
	}

	service, err := NewFileConfigService(filePath)
	if err != nil {
		t.Fatalf("Expected valid config to load, got error: %v", err)
	}

	cfg := service.GetConfig()
	if cfg.Settings.LoopDelaySeconds != 30 {
		t.Errorf("Expected loopDelaySeconds 30, got %d", cfg.Settings.LoopDelaySeconds)
	}
	if cfg.Kraken.APIKey != "key" {
		t.Errorf("Expected apiKey key, got %s", cfg.Kraken.APIKey)
	}

	// 5. Update with invalid config
	invalidCfg := cfg
	invalidCfg.Settings.LoopDelaySeconds = -10
	err = service.UpdateConfig(invalidCfg)
	if err == nil {
		t.Error("Expected error when updating to invalid configuration, got nil")
	}

	// 6. Update with valid config
	validCfg := cfg
	validCfg.Settings.LoopDelaySeconds = 45
	err = service.UpdateConfig(validCfg)
	if err != nil {
		t.Errorf("Expected successful config update, got %v", err)
	}

	updatedCfg := service.GetConfig()
	if updatedCfg.Settings.LoopDelaySeconds != 45 {
		t.Errorf("Expected updated loopDelaySeconds to be 45, got %d", updatedCfg.Settings.LoopDelaySeconds)
	}

	// 7. Test read failure (point path to directory)
	dirService := &FileConfigService{configFilePath: tempDir}
	err = dirService.LoadConfig()
	if err == nil {
		t.Error("Expected read failure when loading directory, got nil")
	}

	// 8. Test write failure on update (point path to directory)
	cfg.Settings.LoopDelaySeconds = 10
	badService := &FileConfigService{configFilePath: tempDir, appConfig: cfg}
	err = badService.UpdateConfig(cfg)
	if err == nil {
		t.Error("Expected write failure when updating config, got nil")
	}
}
