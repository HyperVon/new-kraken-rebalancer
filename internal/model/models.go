package model

import (
	"strings"
	"time"

	"github.com/shopspring/decimal"
)

// Asset represents a cryptocurrency or fiat symbol.
type Asset string

func (a Asset) String() string {
	return string(a)
}

// KrakenTicker returns the symbol mapped to Kraken's specific ticker.
func (a Asset) KrakenTicker() string {
	v := strings.ToUpper(string(a))
	switch v {
	case "BTC":
		return "XBT"
	case "DOGE":
		return "XDG"
	default:
		return v
	}
}

// TradingPair returns the ticker pair name with USD (e.g. XBTUSD).
func (a Asset) TradingPair() string {
	return a.KrakenTicker() + "USD"
}

// IsUSD returns true if the asset is USD.
func (a Asset) IsUSD() bool {
	return strings.ToUpper(string(a)) == "USD"
}

// AssetSnapshot represents a snapshot of a single asset's metrics.
type AssetSnapshot struct {
	Symbol           Asset           `json:"symbol"`
	Balance          decimal.Decimal `json:"balance"`
	Price            decimal.Decimal `json:"price"`
	ValueUSD         decimal.Decimal `json:"valueUSD"`
	TargetPercent    decimal.Decimal `json:"targetPercent"`
	CurrentPercent   decimal.Decimal `json:"currentPercent"`
	DeviationPercent decimal.Decimal `json:"deviationPercent"`
	DeviationUSD     decimal.Decimal `json:"deviationUSD"`
}

// PortfolioSnapshot represents a complete portfolio status at a given cycle.
type PortfolioSnapshot struct {
	Timestamp                 time.Time                `json:"timestamp"`
	TotalValueUSD             decimal.Decimal          `json:"totalValueUSD"`
	Assets                    map[string]AssetSnapshot `json:"assets"`
	Actions                   []string                 `json:"actions"`
	DrawdownPercent           decimal.Decimal          `json:"drawdownPercent"`
	FiatDeploymentPercent     decimal.Decimal          `json:"fiatDeploymentPercent"`
	EffectiveUsdTargetPercent decimal.Decimal          `json:"effectiveUsdTargetPercent"`
}

// PortfolioStats tracks overall metrics such as the portfolio's All-Time High.
type PortfolioStats struct {
	AllTimeHigh decimal.Decimal `json:"allTimeHigh"`
}

// OrderResult holds the result of a placement attempt.
type OrderResult struct {
	Pair         string          `json:"pair"`
	Side         string          `json:"side"` // "buy" or "sell"
	Volume       decimal.Decimal `json:"volume"`
	DryRun       bool            `json:"dryRun"`
	Success      bool            `json:"success"`
	ErrorMessage string          `json:"errorMessage,omitempty"`
}
