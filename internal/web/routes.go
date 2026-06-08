package web

import (
	"encoding/json"
	"fmt"
	"log"
	"net/http"
	"strconv"
	"time"

	"github.com/HyperVon/new-kraken-rebalancer/internal/config"
	"github.com/HyperVon/new-kraken-rebalancer/internal/model"
	"github.com/HyperVon/new-kraken-rebalancer/internal/service"
)

// RegisterHandlers registers all web endpoints on the multiplexer.
func RegisterHandlers(
	mux *http.ServeMux,
	configService config.ConfigService,
	tradeHistoryService service.TradeHistoryService,
) {
	// Serve static files
	mux.Handle("GET /static/", http.FileServer(http.FS(StaticFS)))

	// Main page shell
	mux.HandleFunc("GET /", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "text/html; charset=utf-8")
		if err := Templates.ExecuteTemplate(w, "shell.html", nil); err != nil {
			log.Printf("Error executing template shell.html: %v", err)
			http.Error(w, "Internal Server Error", http.StatusInternalServerError)
		}
	})

	// Settings Page
	mux.HandleFunc("GET /settings", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "text/html; charset=utf-8")
		data := map[string]interface{}{
			"Config":       configService.GetConfig(),
			"ErrorMessage": "",
		}
		if err := Templates.ExecuteTemplate(w, "settings.html", data); err != nil {
			log.Printf("Error executing template settings.html: %v", err)
			http.Error(w, "Internal Server Error", http.StatusInternalServerError)
		}
	})

	// Post Settings
	mux.HandleFunc("POST /settings", func(w http.ResponseWriter, r *http.Request) {
		if err := r.ParseForm(); err != nil {
			http.Error(w, "Invalid form data", http.StatusBadRequest)
			return
		}

		loopDelaySecs, _ := strconv.ParseInt(r.FormValue("loopDelaySeconds"), 10, 64)
		if loopDelaySecs <= 0 {
			loopDelaySecs = 60
		}
		deviationTriggerPct, _ := strconv.ParseFloat(r.FormValue("deviationTriggerPercent"), 64)
		dustThresholdUSD, _ := strconv.ParseFloat(r.FormValue("dustThresholdUSD"), 64)
		dryRun := r.FormValue("dryRun") == "on"
		fiatMaxDrawdown, _ := strconv.ParseFloat(r.FormValue("fiatMaxDrawdown"), 64)
		fiatDeploymentExp, _ := strconv.ParseFloat(r.FormValue("fiatDeploymentExponent"), 64)
		if fiatDeploymentExp <= 0 {
			fiatDeploymentExp = 1.0
		}

		symbols := r.Form["symbols"]
		targets := r.Form["targets"]

		var allocations []config.Allocation
		for i, sym := range symbols {
			var targetPercent float64
			if i < len(targets) {
				targetPercent, _ = strconv.ParseFloat(targets[i], 64)
			}
			allocations = append(allocations, config.Allocation{
				Symbol:        model.Asset(sym),
				TargetPercent: targetPercent,
			})
		}

		currentConfig := configService.GetConfig()
		updatedConfig := config.AppConfig{
			Kraken: currentConfig.Kraken,
			Settings: config.Settings{
				LoopDelaySeconds:        loopDelaySecs,
				DeviationTriggerPercent: deviationTriggerPct,
				DustThresholdUSD:        dustThresholdUSD,
				DryRun:                  dryRun,
				FiatMaxDrawdown:         fiatMaxDrawdown,
				FiatDeploymentExponent:  fiatDeploymentExp,
			},
			Allocations: allocations,
		}

		err := configService.UpdateConfig(updatedConfig)
		if err == nil {
			// HTMX redirect
			w.Header().Set("HX-Redirect", "/")
			w.WriteHeader(http.StatusOK)
			return
		}

		// Validation error: render form inline with error message
		w.Header().Set("Content-Type", "text/html; charset=utf-8")
		data := map[string]interface{}{
			"Config":       updatedConfig,
			"ErrorMessage": err.Error(),
		}
		if err := Templates.ExecuteTemplate(w, "settings.html", data); err != nil {
			log.Printf("Error executing template settings.html on validation failure: %v", err)
			http.Error(w, "Internal Server Error", http.StatusInternalServerError)
		}
	})

	// Fragment dashboard
	mux.HandleFunc("GET /fragments/dashboard", func(w http.ResponseWriter, r *http.Request) {
		latest, ok := tradeHistoryService.GetLatestSnapshot()
		history := tradeHistoryService.GetHistory()

		w.Header().Set("Content-Type", "text/html; charset=utf-8")

		if !ok {
			// Render waiting spinner
			waitingHTML := `
				<div class="spinner-container">
					<h2 style="font-family: var(--font-heading); font-size: 1.5rem; font-weight: 700; color: white;">Waiting for the first cycle...</h2>
					<p style="color: var(--font-muted); font-size: 0.875rem;">The rebalancer is running its initial evaluation loop.</p>
				</div>`
			w.Write([]byte(waitingHTML))
			return
		}

		timeSinceUpdate := time.Now().Unix() - latest.Timestamp.Unix()
		if timeSinceUpdate < 0 {
			timeSinceUpdate = 0
		}
		isStale := timeSinceUpdate > 90
		formattedTime := latest.Timestamp.Local().Format("03:04:05 PM")

		data := map[string]interface{}{
			"Latest":        latest,
			"History":       history,
			"TimeSince":     timeSinceUpdate,
			"IsStale":       isStale,
			"FormattedTime": formattedTime,
		}

		if err := Templates.ExecuteTemplate(w, "dashboard_fragment.html", data); err != nil {
			log.Printf("Error executing template dashboard_fragment.html: %v", err)
			http.Error(w, "Internal Server Error", http.StatusInternalServerError)
		}
	})

	// SSE Status Stream
	mux.HandleFunc("GET /api/status/stream", func(w http.ResponseWriter, r *http.Request) {
		flusher, ok := w.(http.Flusher)
		if !ok {
			http.Error(w, "Streaming unsupported", http.StatusInternalServerError)
			return
		}

		w.Header().Set("Content-Type", "text/event-stream")
		w.Header().Set("Cache-Control", "no-cache")
		w.Header().Set("Connection", "keep-alive")
		w.Header().Set("X-Accel-Buffering", "no")

		// Broadcast initial snap if available
		latest, ok := tradeHistoryService.GetLatestSnapshot()
		if ok {
			jsonBytes, err := json.Marshal(latest)
			if err == nil {
				fmt.Fprintf(w, "data: %s\n\n", string(jsonBytes))
				flusher.Flush()
			}
		}

		subCh := tradeHistoryService.Subscribe()
		defer tradeHistoryService.Unsubscribe(subCh)

		for {
			select {
			case <-r.Context().Done():
				return
			case snap, isOpen := <-subCh:
				if !isOpen {
					return
				}
				jsonBytes, err := json.Marshal(snap)
				if err == nil {
					fmt.Fprintf(w, "data: %s\n\n", string(jsonBytes))
					flusher.Flush()
				}
			}
		}
	})
}
