package service

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"sync"
	"time"

	"github.com/HyperVon/new-kraken-rebalancer/internal/config"
	"github.com/HyperVon/new-kraken-rebalancer/internal/model"
	"github.com/shopspring/decimal"
)

// PortfolioManager manages starting, stopping, and running the rebalancing loop.
type PortfolioManager interface {
	StartRebalancingLoop()
	StopRebalancingLoop()
	RunLoop(ctx context.Context)
	PerformRebalanceCycle() error
}

// PortfolioManagerImpl implements PortfolioManager.
type PortfolioManagerImpl struct {
	configService       config.ConfigService
	tradeHistoryService TradeHistoryService
	analyzer            *PortfolioAnalyzer
	executor            *OrderExecutor

	mu        sync.RWMutex
	isRunning bool
}

// NewPortfolioManagerImpl creates a new PortfolioManagerImpl.
func NewPortfolioManagerImpl(
	cfgService config.ConfigService,
	historyService TradeHistoryService,
	analyzer *PortfolioAnalyzer,
	executor *OrderExecutor,
) *PortfolioManagerImpl {
	return &PortfolioManagerImpl{
		configService:       cfgService,
		tradeHistoryService: historyService,
		analyzer:            analyzer,
		executor:            executor,
	}
}

func (m *PortfolioManagerImpl) StartRebalancingLoop() {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.isRunning = true
	slog.Info("Rebalancing loop started")
}

func (m *PortfolioManagerImpl) StopRebalancingLoop() {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.isRunning = false
	slog.Info("Rebalancing loop stopped")
}

func (m *PortfolioManagerImpl) RunLoop(ctx context.Context) {
	for {
		m.mu.RLock()
		running := m.isRunning
		m.mu.RUnlock()

		if !running {
			select {
			case <-ctx.Done():
				return
			case <-time.After(1 * time.Second):
				continue
			}
		}

		cfg := m.configService.GetConfig()
		slog.Info("Starting rebalance cycle", "dryRun", cfg.Settings.DryRun)

		if err := m.PerformRebalanceCycle(); err != nil {
			slog.Error("Error in rebalancing cycle", "error", err)
		}

		delaySeconds := cfg.Settings.LoopDelaySeconds
		if delaySeconds <= 0 {
			delaySeconds = 60
		}

		select {
		case <-ctx.Done():
			return
		case <-time.After(time.Duration(delaySeconds) * time.Second):
		}
	}
}

func (m *PortfolioManagerImpl) PerformRebalanceCycle() error {
	slog.Info("--- Starting Snapshot Phase ---")
	var actionLog []string

	balances, err := m.analyzer.FetchBalances()
	if err != nil {
		return fmt.Errorf("failed to fetch balances: %w", err)
	}

	prices, err := m.analyzer.FetchPrices()
	if err != nil {
		return fmt.Errorf("failed to fetch prices: %w", err)
	}

	portfolioValues := m.analyzer.CalculatePortfolioValues(balances, prices)
	if portfolioValues == nil {
		return errors.New("aborted rebalance cycle due to missing prices")
	}

	totalPortfolioValueUSD := portfolioValues.TotalValueUSD
	currentValuesUSD := portfolioValues.CurrentValuesUSD

	slog.Info("Portfolio valued", "totalUSD", totalPortfolioValueUSD.Round(2))

	drawdownPct := m.analyzer.UpdateAthAndCalculateDrawdown(totalPortfolioValueUSD)
	cfg := m.configService.GetConfig()
	fiatDeploymentPct := m.analyzer.CalculateFiatDeployment(drawdownPct, cfg.Settings)

	if fiatDeploymentPct.GreaterThan(decimal.Zero) {
		slog.Info("Drawdown detected",
			"drawdownPct", drawdownPct.Round(2),
			"fiatDeploymentPct", fiatDeploymentPct.Round(2),
		)
	}

	effectiveUsdTarget := m.analyzer.CalculateEffectiveUsdTarget(fiatDeploymentPct)
	cryptoScaleFactor := m.analyzer.CalculateCryptoScaleFactor(effectiveUsdTarget)

	analysisResult := m.analyzer.AnalyzeDeviations(totalPortfolioValueUSD, currentValuesUSD, effectiveUsdTarget, cryptoScaleFactor)
	actionLog = append(actionLog, analysisResult.ActionLog...)

	m.executor.ExecuteOrders(
		analysisResult.BuyOrders,
		analysisResult.SellOrders,
		currentValuesUSD,
		prices,
		cfg.Settings,
		&actionLog,
	)

	snapshot := m.buildSnapshot(
		balances,
		prices,
		currentValuesUSD,
		totalPortfolioValueUSD,
		effectiveUsdTarget,
		cryptoScaleFactor,
		drawdownPct,
		fiatDeploymentPct,
		actionLog,
	)

	if err := m.tradeHistoryService.AddSnapshot(snapshot); err != nil {
		slog.Error("Failed to persist trade history snapshot", "error", err)
	}

	slog.Info("--- Cycle Complete ---")
	return nil
}

func (m *PortfolioManagerImpl) buildSnapshot(
	balances RawBalances,
	prices RawPrices,
	currentValuesUSD map[string]decimal.Decimal,
	totalPortfolioValueUSD decimal.Decimal,
	effectiveUsdTarget decimal.Decimal,
	cryptoScaleFactor decimal.Decimal,
	drawdownPct decimal.Decimal,
	fiatDeploymentPct decimal.Decimal,
	actionLog []string,
) model.PortfolioSnapshot {
	assetSnapshots := make(map[string]model.AssetSnapshot)
	cfg := m.configService.GetConfig()

	for _, alloc := range cfg.Allocations {
		symbol := alloc.Symbol.String()
		balance := m.analyzer.ResolveBalance(symbol, balances)
		balDec := decimal.NewFromFloat(balance)
		valUSD := currentValuesUSD[symbol]

		priceDec := decimal.NewFromFloat(1.0)
		if !alloc.Symbol.IsUSD() {
			if p, exists := prices[symbol]; exists {
				priceDec = decimal.NewFromFloat(p)
			}
		}

		baseTargetPct := alloc.TargetPercent
		snapshotTargetPct := baseTargetPct
		var calcTargetPct decimal.Decimal

		if alloc.Symbol.IsUSD() {
			calcTargetPct = effectiveUsdTarget
		} else {
			calcTargetPct = baseTargetPct.Mul(cryptoScaleFactor)
			snapshotTargetPct = calcTargetPct
		}

		currentPct := decimal.Zero
		if totalPortfolioValueUSD.GreaterThan(decimal.Zero) {
			currentPct = valUSD.DivRound(totalPortfolioValueUSD, 4).Mul(decimal.NewFromInt(100))
		}

		targetVal := totalPortfolioValueUSD.Mul(calcTargetPct).DivRound(decimal.NewFromInt(100), 4)
		deviationUSD := valUSD.Sub(targetVal)
		devPct := decimal.Zero

		if targetVal.GreaterThan(decimal.Zero) {
			devPct = deviationUSD.DivRound(targetVal, 4).Mul(decimal.NewFromInt(100))
		}

		assetSnapshots[symbol] = model.AssetSnapshot{
			Symbol:           alloc.Symbol,
			Balance:          balDec,
			Price:            priceDec,
			ValueUSD:         valUSD,
			TargetPercent:    snapshotTargetPct,
			CurrentPercent:   currentPct,
			DeviationPercent: devPct,
			DeviationUSD:     deviationUSD,
		}
	}

	return model.PortfolioSnapshot{
		Timestamp:                 time.Now(),
		TotalValueUSD:             totalPortfolioValueUSD,
		Assets:                    assetSnapshots,
		Actions:                   actionLog,
		DrawdownPercent:           drawdownPct,
		FiatDeploymentPercent:     fiatDeploymentPct,
		EffectiveUsdTargetPercent: effectiveUsdTarget,
	}
}
