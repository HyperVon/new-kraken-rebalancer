package service

import (
	"fmt"
	"log/slog"
	"math"
	"strings"

	"github.com/HyperVon/new-kraken-rebalancer/internal/config"
	"github.com/HyperVon/new-kraken-rebalancer/internal/model"
	"github.com/HyperVon/new-kraken-rebalancer/internal/repository"
	"github.com/shopspring/decimal"
)

// PortfolioValues represents total and asset-specific valuations.
type PortfolioValues struct {
	TotalValueUSD    decimal.Decimal
	CurrentValuesUSD map[string]decimal.Decimal
}

// AnalysisResult holds the computed rebalancing order details.
type AnalysisResult struct {
	BuyOrders  map[string]decimal.Decimal
	SellOrders map[string]decimal.Decimal
	ActionLog  []string
}

// PortfolioAnalyzer calculates valuations, deviations, and corrections.
type PortfolioAnalyzer struct {
	krakenService   KrakenService
	configService   config.ConfigService
	statsRepository repository.PortfolioStatsRepository
}

// NewPortfolioAnalyzer creates a new PortfolioAnalyzer.
func NewPortfolioAnalyzer(
	krakenService KrakenService,
	configService config.ConfigService,
	statsRepository repository.PortfolioStatsRepository,
) *PortfolioAnalyzer {
	return &PortfolioAnalyzer{
		krakenService:   krakenService,
		configService:   configService,
		statsRepository: statsRepository,
	}
}

func (a *PortfolioAnalyzer) FetchBalances() (RawBalances, error) {
	return a.krakenService.GetBalances()
}

func (a *PortfolioAnalyzer) FetchPrices() (RawPrices, error) {
	cfg := a.configService.GetConfig()
	var nonUsd []config.Allocation
	for _, alloc := range cfg.Allocations {
		if !alloc.Symbol.IsUSD() {
			nonUsd = append(nonUsd, alloc)
		}
	}

	if len(nonUsd) == 0 {
		return make(RawPrices), nil
	}

	pairs := make([]string, 0, len(nonUsd))
	for _, alloc := range nonUsd {
		pairs = append(pairs, alloc.Symbol.TradingPair())
	}

	rawPrices, err := a.krakenService.GetTickerPrices(strings.Join(pairs, ","))
	if err != nil {
		return nil, err
	}

	prices := make(RawPrices, len(nonUsd))
	for _, alloc := range nonUsd {
		symbol := alloc.Symbol.String()
		prices[symbol] = a.resolvePriceFromTicker(symbol, rawPrices)
	}

	return prices, nil
}

func (a *PortfolioAnalyzer) resolvePriceFromTicker(symbol string, rawPrices RawPrices) float64 {
	asset := model.Asset(symbol)
	expectedPair := asset.TradingPair()
	if price, exists := rawPrices[expectedPair]; exists {
		return price
	}

	ticker := asset.KrakenTicker()
	for k, v := range rawPrices {
		if strings.Contains(k, ticker) && strings.Contains(k, "USD") {
			return v
		}
	}
	return 0.0
}

func (a *PortfolioAnalyzer) CalculatePortfolioValues(balances RawBalances, prices RawPrices) *PortfolioValues {
	cfg := a.configService.GetConfig()
	currentValuesUSD := make(map[string]decimal.Decimal, len(cfg.Allocations))
	totalPortfolioValueUSD := decimal.Zero

	for _, alloc := range cfg.Allocations {
		symbol := alloc.Symbol.String()
		balance := a.ResolveBalance(symbol, balances)
		balDec := decimal.NewFromFloat(balance)
		priceDec := decimal.NewFromFloat(1.0)

		if !alloc.Symbol.IsUSD() {
			priceFloat, exists := prices[symbol]
			if !exists || priceFloat <= 0 {
				slog.Warn("Price not found or invalid — aborting cycle to prevent erroneous trades", "symbol", symbol)
				return nil
			}
			priceDec = decimal.NewFromFloat(priceFloat)
		}

		valUSD := balDec.Mul(priceDec)
		currentValuesUSD[symbol] = valUSD
		totalPortfolioValueUSD = totalPortfolioValueUSD.Add(valUSD)
	}

	return &PortfolioValues{
		TotalValueUSD:    totalPortfolioValueUSD,
		CurrentValuesUSD: currentValuesUSD,
	}
}

func (a *PortfolioAnalyzer) ResolveBalance(symbol string, balances RawBalances) float64 {
	asset := model.Asset(symbol)
	ticker := asset.KrakenTicker()

	keysToTry := []string{
		symbol,
		"X" + symbol,
		"Z" + symbol,
		ticker,
		"X" + ticker,
		"Z" + ticker,
	}

	for _, k := range keysToTry {
		if bal, exists := balances[k]; exists {
			return bal
		}
	}
	return 0.0
}

func (a *PortfolioAnalyzer) UpdateAthAndCalculateDrawdown(totalPortfolioValueUSD decimal.Decimal) decimal.Decimal {
	stats, err := a.statsRepository.Load()
	if err != nil {
		slog.Warn("Failed to load stats", "error", err)
	}

	ath := stats.AllTimeHigh

	if ath.IsZero() || totalPortfolioValueUSD.GreaterThan(ath) {
		ath = totalPortfolioValueUSD
		slog.Info("All-Time High updated", "ath", ath.Round(2))
	}

	stats.AllTimeHigh = ath
	if err := a.statsRepository.Save(stats); err != nil {
		slog.Error("Failed to persist portfolio ATH", "error", err)
	}

	if ath.GreaterThan(decimal.Zero) && totalPortfolioValueUSD.LessThan(ath) {
		diff := ath.Sub(totalPortfolioValueUSD)
		return diff.DivRound(ath, 4).Mul(decimal.NewFromInt(100))
	}
	return decimal.Zero
}

func (a *PortfolioAnalyzer) CalculateFiatDeployment(drawdownPct decimal.Decimal, settings config.Settings) decimal.Decimal {
	if settings.FiatMaxDrawdown.LessThanOrEqual(decimal.Zero) {
		return decimal.Zero
	}

	ratio := drawdownPct.DivRound(settings.FiatMaxDrawdown, 4)
	if ratio.GreaterThan(decimal.NewFromInt(1)) {
		ratio = decimal.NewFromInt(1)
	}

	ratioFloat, _ := ratio.Float64()
	exponent, _ := settings.FiatDeploymentExponent.Float64()
	deployDouble := math.Pow(ratioFloat, exponent) * 100.0
	return decimal.NewFromFloat(deployDouble)
}

func (a *PortfolioAnalyzer) CalculateEffectiveUsdTarget(fiatDeploymentPct decimal.Decimal) decimal.Decimal {
	cfg := a.configService.GetConfig()
	baseUsdTarget := decimal.Zero
	for _, alloc := range cfg.Allocations {
		if alloc.Symbol.IsUSD() {
			baseUsdTarget = baseUsdTarget.Add(alloc.TargetPercent)
		}
	}

	if fiatDeploymentPct.GreaterThan(decimal.Zero) {
		factor := decimal.NewFromInt(1).Sub(fiatDeploymentPct.DivRound(decimal.NewFromInt(100), 4))
		return baseUsdTarget.Mul(factor)
	}
	return baseUsdTarget
}

func (a *PortfolioAnalyzer) CalculateCryptoScaleFactor(effectiveUsdTarget decimal.Decimal) decimal.Decimal {
	cfg := a.configService.GetConfig()
	totalNonUsdTarget := decimal.Zero
	for _, alloc := range cfg.Allocations {
		if !alloc.Symbol.IsUSD() {
			totalNonUsdTarget = totalNonUsdTarget.Add(alloc.TargetPercent)
		}
	}

	remainingForCrypto := decimal.NewFromInt(100).Sub(effectiveUsdTarget)
	if totalNonUsdTarget.GreaterThan(decimal.Zero) {
		return remainingForCrypto.DivRound(totalNonUsdTarget, 8)
	}
	return decimal.NewFromInt(1)
}

func (a *PortfolioAnalyzer) AnalyzeDeviations(
	totalPortfolioValueUSD decimal.Decimal,
	currentValuesUSD map[string]decimal.Decimal,
	effectiveUsdTarget decimal.Decimal,
	cryptoScaleFactor decimal.Decimal,
) AnalysisResult {
	buyOrders := make(map[string]decimal.Decimal)
	sellOrders := make(map[string]decimal.Decimal)
	var actionLog []string

	cfg := a.configService.GetConfig()
	settings := cfg.Settings
	usdTriggered := false
	usdDeviationAmount := decimal.Zero
	allDeviations := make(map[string]decimal.Decimal)

	for _, alloc := range cfg.Allocations {
		symbolVal := alloc.Symbol.String()
		targetPct := alloc.TargetPercent

		if alloc.Symbol.IsUSD() {
			targetPct = effectiveUsdTarget
		} else {
			targetPct = targetPct.Mul(cryptoScaleFactor)
		}

		targetPct = targetPct.DivRound(decimal.NewFromInt(100), 4)
		targetValue := totalPortfolioValueUSD.Mul(targetPct)
		currentVal := currentValuesUSD[symbolVal]

		deviationUSD := currentVal.Sub(targetValue)
		deviationPct := decimal.Zero

		if targetValue.GreaterThan(decimal.Zero) {
			deviationPct = deviationUSD.Abs().DivRound(targetValue, 4).Mul(decimal.NewFromInt(100))
		} else if currentVal.GreaterThan(decimal.Zero) {
			deviationPct = decimal.NewFromInt(100)
		}

		allDeviations[symbolVal] = deviationUSD

		slog.Debug("Asset analysis",
			"symbol", symbolVal,
			"deviationPct", deviationPct.Round(2),
			"deviationUSD", deviationUSD.Round(2),
			"threshold", settings.DeviationTriggerPercent,
		)

		isDeviationSignificant := deviationUSD.Abs().GreaterThanOrEqual(settings.DustThresholdUSD)

		if deviationPct.GreaterThanOrEqual(settings.DeviationTriggerPercent) && isDeviationSignificant {
			actionLog = append(actionLog, fmt.Sprintf("Deviation Triggered details: %s Dev: %s%%", symbolVal, deviationPct.Round(2).String()))
		}

		if alloc.Symbol.IsUSD() {
			if deviationPct.GreaterThanOrEqual(settings.DeviationTriggerPercent) && isDeviationSignificant {
				slog.Info("USD deviation triggered",
					"deviationPct", deviationPct.Round(2),
					"threshold", settings.DeviationTriggerPercent,
					"deviationUSD", deviationUSD.Round(2),
				)
				usdTriggered = true
				usdDeviationAmount = deviationUSD
			}
		} else {
			if deviationPct.GreaterThanOrEqual(settings.DeviationTriggerPercent) && isDeviationSignificant {
				slog.Info("Asset deviation triggered",
					"symbol", symbolVal,
					"deviationPct", deviationPct.Round(2),
					"threshold", settings.DeviationTriggerPercent,
					"deviationUSD", deviationUSD.Round(2),
				)

				if deviationUSD.GreaterThan(decimal.Zero) {
					sellOrders[symbolVal] = deviationUSD
				} else {
					buyOrders[symbolVal] = deviationUSD.Abs()
				}
			}
		}
	}

	if len(buyOrders) == 0 && len(sellOrders) == 0 && usdTriggered {
		slog.Info("USD deviation triggered with no individual asset triggers — enforcing fiat correction")
		actionLog = append(actionLog, "USD Deviation Triggered. Enforcing fiat correction.")
		a.distributeFiatCorrection(usdDeviationAmount, allDeviations, buyOrders, sellOrders, &actionLog)
	}

	return AnalysisResult{
		BuyOrders:  buyOrders,
		SellOrders: sellOrders,
		ActionLog:  actionLog,
	}
}

func (a *PortfolioAnalyzer) distributeFiatCorrection(
	usdDev decimal.Decimal,
	allDevs map[string]decimal.Decimal,
	buyOrders map[string]decimal.Decimal,
	sellOrders map[string]decimal.Decimal,
	actionLog *[]string,
) {
	deviationAbs := usdDev.Abs()
	isDeposit := usdDev.GreaterThan(decimal.Zero)
	totalCounterDev := decimal.Zero
	var candidates []string

	for symbol, d := range allDevs {
		if strings.ToUpper(symbol) == "USD" {
			continue
		}

		if isDeposit && d.LessThan(decimal.Zero) {
			candidates = append(candidates, symbol)
			totalCounterDev = totalCounterDev.Add(d.Abs())
		} else if !isDeposit && d.GreaterThan(decimal.Zero) {
			candidates = append(candidates, symbol)
			totalCounterDev = totalCounterDev.Add(d)
		}
	}

	if totalCounterDev.IsZero() {
		slog.Warn("Fiat correction required but no suitable counter-balancing assets found")
		return
	}

	slog.Info("Distributing fiat correction",
		"amount", deviationAbs.Round(2),
		"candidates", len(candidates),
		"totalCounterDev", totalCounterDev.Round(2),
	)
	*actionLog = append(*actionLog, fmt.Sprintf("Distributing Fiat Correction ($%s) among %d candidates.",
		deviationAbs.Round(2).String(),
		len(candidates),
	))

	for _, symbol := range candidates {
		assetDev := allDevs[symbol].Abs()
		ratio := assetDev.DivRound(totalCounterDev, 8)
		share := deviationAbs.Mul(ratio)

		if isDeposit {
			buyOrders[symbol] = share
		} else {
			sellOrders[symbol] = share
		}
	}
}
