package main

import (
	"context"
	"errors"
	"flag"
	"log/slog"
	"net/http"
	"os"
	"os/signal"
	"strings"
	"syscall"
	"time"

	"github.com/HyperVon/new-kraken-rebalancer/internal/config"
	"github.com/HyperVon/new-kraken-rebalancer/internal/repository"
	"github.com/HyperVon/new-kraken-rebalancer/internal/service"
	"github.com/HyperVon/new-kraken-rebalancer/internal/web"
)

const defaultPort = ":8080"

func main() {
	// Define command-line flags
	configFilePath := flag.String("config", "rebalancer-config.json", "Path to the configuration JSON file")
	historyFilePath := flag.String("history", "trade-history.json", "Path to the trade history JSON file")
	statsFilePath := flag.String("stats", "portfolio-stats.json", "Path to the portfolio stats ATH JSON file")
	logLevelStr := flag.String("loglevel", "info", "Log level (debug, info, warn, error)")
	flag.Parse()

	// Initialize slog
	var level slog.Level
	switch strings.ToLower(*logLevelStr) {
	case "debug":
		level = slog.LevelDebug
	case "warn":
		level = slog.LevelWarn
	case "error":
		level = slog.LevelError
	default:
		level = slog.LevelInfo
	}
	h := slog.NewTextHandler(os.Stderr, &slog.HandlerOptions{Level: level})
	slog.SetDefault(slog.New(h))

	slog.Info("Starting Kraken Rebalancer Application...")

	// Initialize configuration
	configService, err := config.NewFileConfigService(*configFilePath)
	if err != nil {
		slog.Error("Critical error initializing config service", "error", err)
		os.Exit(1)
	}

	// Initialize repositories
	tradeRepo := repository.NewFileTradeRepository(*historyFilePath)
	statsRepo := repository.NewFilePortfolioStatsRepository(*statsFilePath)

	// Initialize services
	tradeHistoryService := service.NewTradeHistoryServiceImpl(tradeRepo)
	if err := tradeHistoryService.Init(); err != nil {
		slog.Error("Critical error loading trade history", "error", err)
		os.Exit(1)
	}

	krakenService := service.NewKrakenServiceImpl(configService, nil)
	analyzer := service.NewPortfolioAnalyzer(krakenService, configService, statsRepo)
	executor := service.NewOrderExecutor(krakenService, analyzer)
	portfolioManager := service.NewPortfolioManagerImpl(configService, tradeHistoryService, analyzer, executor)

	// Initialize templates
	web.InitTemplates()

	// Setup context for background loop shutdown
	loopCtx, cancelLoop := context.WithCancel(context.Background())
	defer cancelLoop()

	portfolioManager.StartRebalancingLoop()
	go portfolioManager.RunLoop(loopCtx)

	// Setup HTTP Server
	mux := http.NewServeMux()
	web.RegisterHandlers(mux, configService, tradeHistoryService)

	server := &http.Server{
		Addr:    defaultPort,
		Handler: mux,
	}

	// Handle Graceful Shutdown
	shutdownDone := make(chan struct{})
	go func() {
		sigChan := make(chan os.Signal, 1)
		signal.Notify(sigChan, os.Interrupt, syscall.SIGTERM)
		<-sigChan

		slog.Info("Shutdown signal received. Stopping services...")

		// Stop rebalancing loop and cancel context
		portfolioManager.StopRebalancingLoop()
		cancelLoop()

		// Shutdown HTTP server gracefully
		shutdownCtx, cancelShutdown := context.WithTimeout(context.Background(), 5*time.Second)
		defer cancelShutdown()

		if err := server.Shutdown(shutdownCtx); err != nil {
			slog.Error("HTTP server shutdown error", "error", err)
		} else {
			slog.Info("HTTP server stopped gracefully.")
		}

		close(shutdownDone)
	}()

	slog.Info("Web server starting...", "port", defaultPort)
	if err := server.ListenAndServe(); err != nil && !errors.Is(err, http.ErrServerClosed) {
		slog.Error("HTTP server failed", "error", err)
		os.Exit(1)
	}

	<-shutdownDone
	slog.Info("Kraken Rebalancer Application cleanly terminated.")
}
