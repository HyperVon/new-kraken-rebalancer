package web

import (
	"encoding/json"
	"fmt"
	"log/slog"
	"net/http"
	"strconv"
	"time"

	"github.com/HyperVon/new-kraken-rebalancer/internal/config"
	"github.com/HyperVon/new-kraken-rebalancer/internal/model"
	"github.com/HyperVon/new-kraken-rebalancer/internal/service"
	"github.com/shopspring/decimal"
)

const waitingSpinnerHTML = `
<div class="spinner-container">
	<h2 style="font-family: var(--font-heading); font-size: 1.5rem; font-weight: 700; color: white;">Waiting for the first cycle...</h2>
	<p style="color: var(--font-muted); font-size: 0.875rem;">The rebalancer is running its initial evaluation loop.</p>
</div>`

// RegisterHandlers registers all web endpoints on the multiplexer.
func RegisterHandlers(
	mux *http.ServeMux,
	configService config.ConfigService,
	tradeHistoryService service.TradeHistoryService,
) {
	mux.Handle("GET /static/", http.FileServer(http.FS(StaticFS)))

	mux.HandleFunc("GET /", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "text/html; charset=utf-8")
		if err := Templates.ExecuteTemplate(w, "shell.tmpl", nil); err != nil {
			slog.Error("Error executing template shell.tmpl", "error", err)
			http.Error(w, "Internal Server Error", http.StatusInternalServerError)
		}
	})

	mux.HandleFunc("GET /settings", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "text/html; charset=utf-8")
		data := map[string]any{
			"Config":       configService.GetConfig(),
			"ErrorMessage": "",
		}
		if err := Templates.ExecuteTemplate(w, "settings.tmpl", data); err != nil {
			slog.Error("Error executing template settings.tmpl", "error", err)
			http.Error(w, "Internal Server Error", http.StatusInternalServerError)
		}
	})

	mux.HandleFunc("POST /settings", func(w http.ResponseWriter, r *http.Request) {
		if err := r.ParseForm(); err != nil {
			http.Error(w, "Invalid form data", http.StatusBadRequest)
			return
		}

		loopDelaySecs, _ := strconv.ParseInt(r.FormValue("loopDelaySeconds"), 10, 64)
		if loopDelaySecs <= 0 {
			loopDelaySecs = 60
		}
		deviationTriggerPct, _ := decimal.NewFromString(r.FormValue("deviationTriggerPercent"))
		dustThresholdUSD, _ := decimal.NewFromString(r.FormValue("dustThresholdUSD"))
		dryRun := r.FormValue("dryRun") == "on"
		fiatMaxDrawdown, _ := decimal.NewFromString(r.FormValue("fiatMaxDrawdown"))
		fiatDeploymentExp, _ := decimal.NewFromString(r.FormValue("fiatDeploymentExponent"))
		if fiatDeploymentExp.LessThanOrEqual(decimal.Zero) {
			fiatDeploymentExp = decimal.NewFromInt(1)
		}

		symbols := r.Form["symbols"]
		targets := r.Form["targets"]

		var allocations []config.Allocation
		for i, sym := range symbols {
			var targetPercent decimal.Decimal
			if i < len(targets) {
				targetPercent, _ = decimal.NewFromString(targets[i])
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
			w.Header().Set("HX-Redirect", "/")
			w.WriteHeader(http.StatusOK)
			return
		}

		w.Header().Set("Content-Type", "text/html; charset=utf-8")
		data := map[string]any{
			"Config":       updatedConfig,
			"ErrorMessage": err.Error(),
		}
		if err := Templates.ExecuteTemplate(w, "settings.tmpl", data); err != nil {
			slog.Error("Error executing template settings.tmpl on validation failure", "error", err)
			http.Error(w, "Internal Server Error", http.StatusInternalServerError)
		}
	})

	mux.HandleFunc("GET /fragments/dashboard", func(w http.ResponseWriter, r *http.Request) {
		latest, ok := tradeHistoryService.GetLatestSnapshot()
		history := tradeHistoryService.GetHistory()

		w.Header().Set("Content-Type", "text/html; charset=utf-8")

		if !ok {
			if _, err := w.Write([]byte(waitingSpinnerHTML)); err != nil {
				slog.Error("Error writing waiting spinner HTML", "error", err)
			}
			return
		}

		timeSinceUpdate := time.Now().Unix() - latest.Timestamp.Unix()
		if timeSinceUpdate < 0 {
			timeSinceUpdate = 0
		}
		isStale := timeSinceUpdate > 90
		formattedTime := latest.Timestamp.Local().Format("03:04:05 PM")

		data := map[string]any{
			"Latest":        latest,
			"History":       history,
			"TimeSince":     timeSinceUpdate,
			"IsStale":       isStale,
			"FormattedTime": formattedTime,
		}

		if err := Templates.ExecuteTemplate(w, "dashboard_fragment.tmpl", data); err != nil {
			slog.Error("Error executing template dashboard_fragment.tmpl", "error", err)
			http.Error(w, "Internal Server Error", http.StatusInternalServerError)
		}
	})

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
