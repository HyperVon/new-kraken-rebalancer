package config

import (
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

	// Test FiatMaxDrawdown
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
