package web

import (
	"html/template"
	"testing"
	"time"

	"github.com/HyperVon/new-kraken-rebalancer/internal/model"
	"github.com/shopspring/decimal"
)

func TestTemplateFuncs(t *testing.T) {
	InitTemplates()

	// 1. icon function
	// Test existing icon
	cogHTML := FuncMap["icon"].(func(string) template.HTML)("cog")
	if len(cogHTML) == 0 {
		t.Error("Expected cog icon HTML to be non-empty")
	}
	// Test non-existing icon
	badHTML := FuncMap["icon"].(func(string) template.HTML)("non-existent-icon-xyz")
	if len(badHTML) != 0 {
		t.Errorf("Expected empty HTML for non-existent icon, got: %s", badHTML)
	}

	// 2. currency function
	currencyFunc := FuncMap["currency"].(func(decimal.Decimal) string)
	c1 := currencyFunc(decimal.NewFromFloat(1234567.89))
	if c1 != "1,234,567.89" {
		t.Errorf("Expected 1,234,567.89, got %s", c1)
	}
	c2 := currencyFunc(decimal.NewFromFloat(-500.5))
	if c2 != "-500.50" {
		t.Errorf("Expected -500.50, got %s", c2)
	}

	// 3. percent function
	percentFunc := FuncMap["percent"].(func(interface{}) string)
	p1 := percentFunc(decimal.NewFromFloat(12.3456))
	if p1 != "12.35" {
		t.Errorf("Expected 12.35, got %s", p1)
	}
	p2 := percentFunc(float64(45.678))
	if p2 != "45.68" {
		t.Errorf("Expected 45.68, got %s", p2)
	}
	p3 := percentFunc("invalid-type")
	if p3 != "0.00" {
		t.Errorf("Expected 0.00 for invalid type, got %s", p3)
	}

	// 4. abs function
	absFunc := FuncMap["abs"].(func(decimal.Decimal) decimal.Decimal)
	a1 := absFunc(decimal.NewFromFloat(-15.5))
	if !a1.Equal(decimal.NewFromFloat(15.5)) {
		t.Errorf("Expected abs of -15.5 to be 15.5, got %v", a1)
	}

	// 5. devClass function
	devClassFunc := FuncMap["devClass"].(func(decimal.Decimal) string)
	if devClassFunc(decimal.NewFromFloat(1.5)) != "text-danger" {
		t.Error("Expected positive deviation to have text-danger class")
	}
	if devClassFunc(decimal.NewFromFloat(-1.5)) != "text-success" {
		t.Error("Expected negative deviation to have text-success class")
	}
	if devClassFunc(decimal.Zero) != "" {
		t.Error("Expected zero deviation to have empty class")
	}

	// 6. devSign and usdSign functions
	devSignFunc := FuncMap["devSign"].(func(decimal.Decimal) string)
	if devSignFunc(decimal.NewFromFloat(1.5)) != "+" {
		t.Error("Expected positive deviation sign to be '+'")
	}
	if devSignFunc(decimal.NewFromFloat(-1.5)) != "" {
		t.Error("Expected negative deviation sign to be empty")
	}

	usdSignFunc := FuncMap["usdSign"].(func(decimal.Decimal) string)
	if usdSignFunc(decimal.NewFromFloat(0.0)) != "+" {
		t.Error("Expected zero usdSign to be '+'")
	}
	if usdSignFunc(decimal.NewFromFloat(-1.5)) != "" {
		t.Error("Expected negative usdSign to be empty")
	}

	// 7. usdTargetAdjusted function
	usdAdjFunc := FuncMap["usdTargetAdjusted"].(func(decimal.Decimal, decimal.Decimal) bool)
	if !usdAdjFunc(decimal.NewFromFloat(10.0), decimal.NewFromFloat(8.5)) {
		t.Error("Expected true for target change > 0.01")
	}
	if usdAdjFunc(decimal.NewFromFloat(10.0), decimal.NewFromFloat(10.005)) {
		t.Error("Expected false for target change <= 0.01")
	}

	// 8. crypto helpers (cryptoValue, cryptoPct, cryptoTargetPct, cryptoCount)
	snap := model.PortfolioSnapshot{
		TotalValueUSD: decimal.NewFromFloat(1000.0),
		Assets: map[string]model.AssetSnapshot{
			"USD": {Symbol: "USD", ValueUSD: decimal.NewFromFloat(400.0), CurrentPercent: decimal.NewFromFloat(40.0), TargetPercent: decimal.NewFromFloat(40.0)},
			"BTC": {Symbol: "BTC", ValueUSD: decimal.NewFromFloat(400.0), CurrentPercent: decimal.NewFromFloat(40.0), TargetPercent: decimal.NewFromFloat(30.0), DeviationPercent: decimal.NewFromFloat(10.0)},
			"ETH": {Symbol: "ETH", ValueUSD: decimal.NewFromFloat(200.0), CurrentPercent: decimal.NewFromFloat(20.0), TargetPercent: decimal.NewFromFloat(30.0), DeviationPercent: decimal.NewFromFloat(-10.0)},
		},
	}

	cryptoValFunc := FuncMap["cryptoValue"].(func(model.PortfolioSnapshot) decimal.Decimal)
	if !cryptoValFunc(snap).Equal(decimal.NewFromFloat(600.0)) {
		t.Errorf("Expected cryptoValue 600.0, got %v", cryptoValFunc(snap))
	}

	cryptoPctFunc := FuncMap["cryptoPct"].(func(model.PortfolioSnapshot) decimal.Decimal)
	if !cryptoPctFunc(snap).Equal(decimal.NewFromFloat(60.0)) {
		t.Errorf("Expected cryptoPct 60.0, got %v", cryptoPctFunc(snap))
	}

	cryptoTargetPctFunc := FuncMap["cryptoTargetPct"].(func(model.PortfolioSnapshot) decimal.Decimal)
	if !cryptoTargetPctFunc(snap).Equal(decimal.NewFromFloat(60.0)) {
		t.Errorf("Expected cryptoTargetPct 60.0, got %v", cryptoTargetPctFunc(snap))
	}

	cryptoCountFunc := FuncMap["cryptoCount"].(func(model.PortfolioSnapshot) int)
	if cryptoCountFunc(snap) != 2 {
		t.Errorf("Expected cryptoCount 2, got %d", cryptoCountFunc(snap))
	}

	// 9. sortedAssets
	sortedFunc := FuncMap["sortedAssets"].(func(model.PortfolioSnapshot) []model.AssetSnapshot)
	sorted := sortedFunc(snap)
	if len(sorted) != 3 || sorted[0].Symbol.String() == "ETH" {
		t.Errorf("Expected sorted assets to be ordered by ValueUSD (USD/BTC first, ETH last), got first: %s", sorted[0].Symbol.String())
	}

	// 10. performanceAssets
	perfFunc := FuncMap["performanceAssets"].(func(model.PortfolioSnapshot) []model.AssetSnapshot)
	perf := perfFunc(snap)
	if len(perf) != 2 || perf[0].Symbol.String() != "ETH" {
		t.Errorf("Expected performance assets to be ordered by DeviationPercent asc (ETH first, BTC last), got first: %s", perf[0].Symbol.String())
	}

	// 11. maxUSD and fillPercent
	maxUSDFunc := FuncMap["maxUSD"].(func(model.PortfolioSnapshot) decimal.Decimal)
	if !maxUSDFunc(snap).Equal(decimal.NewFromFloat(400.0)) {
		t.Errorf("Expected maxUSD 400.0, got %v", maxUSDFunc(snap))
	}

	fillPercentFunc := FuncMap["fillPercent"].(func(decimal.Decimal, decimal.Decimal) int)
	if fillPercentFunc(decimal.NewFromFloat(200.0), decimal.NewFromFloat(400.0)) != 50 {
		t.Errorf("Expected fillPercent 50, got %d", fillPercentFunc(decimal.NewFromFloat(200.0), decimal.NewFromFloat(400.0)))
	}
	if fillPercentFunc(decimal.Zero, decimal.Zero) != 0 {
		t.Errorf("Expected fillPercent 0 for zero max, got %d", fillPercentFunc(decimal.Zero, decimal.Zero))
	}

	// 12. dateTime
	dateTimeFunc := FuncMap["dateTime"].(func(time.Time) string)
	timeStr := dateTimeFunc(time.Date(2026, 6, 8, 14, 30, 0, 0, time.UTC))
	if len(timeStr) == 0 {
		t.Error("Expected formatted dateTime to be non-empty")
	}

	// 13. badgeClass and badgeLabel
	badgeClassFunc := FuncMap["badgeClass"].(func(string) string)
	if badgeClassFunc("BUY BTC") != "badge-buy" {
		t.Errorf("Expected badge-buy, got %s", badgeClassFunc("BUY BTC"))
	}
	if badgeClassFunc("SELL BTC") != "badge-sell" {
		t.Errorf("Expected badge-sell, got %s", badgeClassFunc("SELL BTC"))
	}
	if badgeClassFunc("INFO test") != "badge-info" {
		t.Errorf("Expected badge-info, got %s", badgeClassFunc("INFO test"))
	}

	badgeLabelFunc := FuncMap["badgeLabel"].(func(string) string)
	if badgeLabelFunc("BUY BTC") != "BUY" {
		t.Errorf("Expected BUY, got %s", badgeLabelFunc("BUY BTC"))
	}
	if badgeLabelFunc("SELL BTC") != "SELL" {
		t.Errorf("Expected SELL, got %s", badgeLabelFunc("SELL BTC"))
	}
	if badgeLabelFunc("INFO test") != "INFO" {
		t.Errorf("Expected INFO, got %s", badgeLabelFunc("INFO test"))
	}
}
