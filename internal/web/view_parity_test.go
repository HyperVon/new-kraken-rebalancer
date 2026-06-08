package web

import (
	"bytes"
	"testing"
	"time"

	"github.com/HyperVon/new-kraken-rebalancer/internal/model"
	"github.com/shopspring/decimal"
)

func TestDashboardViewParity_Live(t *testing.T) {
	InitTemplates()

	now := time.Now()
	latest := model.PortfolioSnapshot{
		Timestamp:                 now,
		TotalValueUSD:             decimal.NewFromFloat(10000.00),
		DrawdownPercent:           decimal.NewFromFloat(5.0),
		FiatDeploymentPercent:     decimal.NewFromFloat(25.0),
		EffectiveUsdTargetPercent: decimal.NewFromFloat(7.5),
		Assets: map[string]model.AssetSnapshot{
			"USD": {
				Symbol:           "USD",
				Balance:          decimal.NewFromFloat(1000.0),
				Price:            decimal.NewFromFloat(1.0),
				ValueUSD:         decimal.NewFromFloat(1000.0),
				TargetPercent:    decimal.NewFromFloat(10.0),
				CurrentPercent:   decimal.NewFromFloat(10.0),
				DeviationPercent: decimal.NewFromFloat(0.0),
				DeviationUSD:     decimal.NewFromFloat(0.0),
			},
			"BTC": {
				Symbol:           "BTC",
				Balance:          decimal.NewFromFloat(0.1),
				Price:            decimal.NewFromFloat(50000.0),
				ValueUSD:         decimal.NewFromFloat(5000.0),
				TargetPercent:    decimal.NewFromFloat(50.0),
				CurrentPercent:   decimal.NewFromFloat(50.0),
				DeviationPercent: decimal.NewFromFloat(5.0),
				DeviationUSD:     decimal.NewFromFloat(250.0),
			},
			"ETH": {
				Symbol:           "ETH",
				Balance:          decimal.NewFromFloat(2.0),
				Price:            decimal.NewFromFloat(2000.0),
				ValueUSD:         decimal.NewFromFloat(4000.0),
				TargetPercent:    decimal.NewFromFloat(40.0),
				CurrentPercent:   decimal.NewFromFloat(40.0),
				DeviationPercent: decimal.NewFromFloat(-2.5),
				DeviationUSD:     decimal.NewFromFloat(-100.0),
			},
		},
		Actions: []string{
			"BUY BTC Volume: 0.05 Value: $2500.0",
			"SELL ETH Volume: 1.0 Value: $2000.0",
		},
	}

	history := []model.PortfolioSnapshot{latest}

	data := map[string]any{
		"Latest":        latest,
		"History":       history,
		"TimeSince":     0,
		"IsStale":       false,
		"FormattedTime": now.Format("03:04:05 PM"),
	}

	var buf bytes.Buffer
	err := Templates.ExecuteTemplate(&buf, "dashboard_fragment.tmpl", data)
	if err != nil {
		t.Fatalf("Failed to execute template: %v", err)
	}

	html := buf.String()

	checks := []string{
		"LIVE",
		"Total Portfolio",
		"$10,000.00",
		"Drawdown: 5.00%",
		"Cash USD",
		"$1,000.00",
		"10.00% | Target: 7.50%",
		"(Base: 10.00%)",
		"Dev: 0.00%",
		"Crypto Assets",
		"$9,000.00",
		"90.00% | Target: 90.00% | 2 Assets",
		"BTC",
		"ETH",
		"badge badge-buy",
		"badge badge-sell",
	}

	for _, check := range checks {
		if !bytes.Contains(buf.Bytes(), []byte(check)) {
			t.Errorf("Expected HTML to contain %q, but it did not. HTML:\n%s", check, html)
		}
	}

	if bytes.Contains(buf.Bytes(), []byte("DELAYED")) {
		t.Error("Did not expect HTML to contain 'DELAYED'")
	}
}

func TestDashboardViewParity_Stale(t *testing.T) {
	InitTemplates()

	oldTime := time.Now().Add(-100 * time.Second)
	latest := model.PortfolioSnapshot{
		Timestamp:                 oldTime,
		TotalValueUSD:             decimal.NewFromFloat(1000.00),
		DrawdownPercent:           decimal.Zero,
		FiatDeploymentPercent:     decimal.Zero,
		EffectiveUsdTargetPercent: decimal.NewFromFloat(100.0),
		Assets: map[string]model.AssetSnapshot{
			"USD": {
				Symbol:           "USD",
				Balance:          decimal.NewFromFloat(1000.0),
				Price:            decimal.NewFromFloat(1.0),
				ValueUSD:         decimal.NewFromFloat(1000.0),
				TargetPercent:    decimal.NewFromFloat(100.0),
				CurrentPercent:   decimal.NewFromFloat(100.0),
				DeviationPercent: decimal.Zero,
				DeviationUSD:     decimal.Zero,
			},
		},
		Actions: nil,
	}

	data := map[string]any{
		"Latest":        latest,
		"History":       nil,
		"TimeSince":     100,
		"IsStale":       true,
		"FormattedTime": oldTime.Format("03:04:05 PM"),
	}

	var buf bytes.Buffer
	err := Templates.ExecuteTemplate(&buf, "dashboard_fragment.tmpl", data)
	if err != nil {
		t.Fatalf("Failed to execute template: %v", err)
	}

	html := buf.String()

	if !bytes.Contains(buf.Bytes(), []byte("DELAYED")) {
		t.Error("Expected HTML to contain 'DELAYED'")
	}
	if bytes.Contains(buf.Bytes(), []byte("LIVE")) {
		t.Error("Did not expect HTML to contain 'LIVE'")
	}
	if !bytes.Contains(buf.Bytes(), []byte("No trading history available.")) {
		t.Errorf("Expected 'No trading history available.', got: %s", html)
	}
}

func TestDashboardViewParity_EdgeCases(t *testing.T) {
	InitTemplates()

	now := time.Now()
	latest := model.PortfolioSnapshot{
		Timestamp:                 now,
		TotalValueUSD:             decimal.Zero,
		DrawdownPercent:           decimal.Zero,
		FiatDeploymentPercent:     decimal.Zero,
		EffectiveUsdTargetPercent: decimal.NewFromFloat(10.0),
		Assets: map[string]model.AssetSnapshot{
			"BTC": {
				Symbol:           "BTC",
				Balance:          decimal.Zero,
				Price:            decimal.Zero,
				ValueUSD:         decimal.Zero,
				TargetPercent:    decimal.Zero,
				CurrentPercent:   decimal.Zero,
				DeviationPercent: decimal.Zero,
				DeviationUSD:     decimal.Zero,
			},
		},
		Actions: []string{"INFO Rebalancer initialized"},
	}

	noActionsSnapshot := model.PortfolioSnapshot{
		Timestamp:                 now.Add(-60 * time.Second),
		TotalValueUSD:             decimal.Zero,
		DrawdownPercent:           decimal.Zero,
		FiatDeploymentPercent:     decimal.Zero,
		EffectiveUsdTargetPercent: decimal.NewFromFloat(10.0),
		Assets:                    nil,
		Actions:                   nil,
	}

	history := []model.PortfolioSnapshot{latest, noActionsSnapshot}

	data := map[string]any{
		"Latest":        latest,
		"History":       history,
		"TimeSince":     0,
		"IsStale":       false,
		"FormattedTime": now.Format("03:04:05 PM"),
	}

	var buf bytes.Buffer
	err := Templates.ExecuteTemplate(&buf, "dashboard_fragment.tmpl", data)
	if err != nil {
		t.Fatalf("Failed to execute template: %v", err)
	}

	html := buf.String()

	if !bytes.Contains(buf.Bytes(), []byte("No USD Data")) {
		t.Error("Expected 'No USD Data'")
	}
	if !bytes.Contains(buf.Bytes(), []byte("Drawdown: 0.00%")) {
		t.Error("Expected Drawdown: 0.00%")
	}
	if !bytes.Contains(buf.Bytes(), []byte("badge badge-info")) {
		t.Error("Expected badge-info")
	}
	if !bytes.Contains(buf.Bytes(), []byte("No trades executed")) {
		t.Errorf("Expected 'No trades executed', got: %s", html)
	}
}

func TestDashboardViewParity_UsdTargetEqual(t *testing.T) {
	InitTemplates()

	now := time.Now()
	latest := model.PortfolioSnapshot{
		Timestamp:                 now,
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
		},
	}

	data := map[string]any{
		"Latest":        latest,
		"History":       nil,
		"TimeSince":     0,
		"IsStale":       false,
		"FormattedTime": now.Format("03:04:05 PM"),
	}

	var buf bytes.Buffer
	err := Templates.ExecuteTemplate(&buf, "dashboard_fragment.tmpl", data)
	if err != nil {
		t.Fatalf("Failed to execute template: %v", err)
	}

	html := buf.String()

	if !bytes.Contains(buf.Bytes(), []byte("10.00% | Target: 10.00%")) {
		t.Errorf("Expected '10.00%% | Target: 10.00%%', got: %s", html)
	}
	if bytes.Contains(buf.Bytes(), []byte("Base:")) {
		t.Errorf("Did not expect base target indicator when effective USD target matches base target, got: %s", html)
	}
}
