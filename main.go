package main

import (
	"context"
	"errors"
	"flag"
	"log"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	"github.com/HyperVon/new-kraken-rebalancer/internal/config"
	"github.com/HyperVon/new-kraken-rebalancer/internal/repository"
	"github.com/HyperVon/new-kraken-rebalancer/internal/service"
	"github.com/HyperVon/new-kraken-rebalancer/internal/web"
)

func main() {
	log.Println("Starting Kraken Rebalancer Application...")

	// Define command-line flags
	configFilePath := flag.String("config", "rebalancer-config.json", "Path to the configuration JSON file")
	historyFilePath := flag.String("history", "trade-history.json", "Path to the trade history JSON file")
	statsFilePath := flag.String("stats", "portfolio-stats.json", "Path to the portfolio stats ATH JSON file")
	flag.Parse()

	// Initialize configuration
	configService, err := config.NewFileConfigService(*configFilePath)
	if err != nil {
		log.Fatalf("Critical error initializing config service: %v", err)
	}

	// Initialize repositories
	tradeRepo := repository.NewFileTradeRepository(*historyFilePath)
	statsRepo := repository.NewFilePortfolioStatsRepository(*statsFilePath)

	// Initialize services
	tradeHistoryService := service.NewTradeHistoryServiceImpl(tradeRepo)
	if err := tradeHistoryService.Init(); err != nil {
		log.Fatalf("Critical error loading trade history: %v", err)
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
		Addr:    ":8080",
		Handler: mux,
	}

	// Handle Graceful Shutdown
	shutdownDone := make(chan struct{})
	go func() {
		sigChan := make(chan os.Signal, 1)
		signal.Notify(sigChan, os.Interrupt, syscall.SIGTERM)
		<-sigChan

		log.Println("Shutdown signal received. Stopping services...")

		// Stop rebalancing loop and cancel context
		portfolioManager.StopRebalancingLoop()
		cancelLoop()

		// Shutdown HTTP server gracefully
		shutdownCtx, cancelShutdown := context.WithTimeout(context.Background(), 5*time.Second)
		defer cancelShutdown()

		if err := server.Shutdown(shutdownCtx); err != nil {
			log.Printf("HTTP server shutdown error: %v", err)
		} else {
			log.Println("HTTP server stopped gracefully.")
		}

		close(shutdownDone)
	}()

	log.Println("Web server starting on port 8080...")
	if err := server.ListenAndServe(); err != nil && !errors.Is(err, http.ErrServerClosed) {
		log.Fatalf("HTTP server failed: %v", err)
	}

	<-shutdownDone
	log.Println("Kraken Rebalancer Application cleanly terminated.")
}
