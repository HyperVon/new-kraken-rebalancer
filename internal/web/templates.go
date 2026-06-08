package web

import (
	"embed"
	"html/template"
	"io/fs"
	"math"
	"sort"
	"strings"
	"time"

	"github.com/HyperVon/new-kraken-rebalancer/internal/model"
	"github.com/shopspring/decimal"
)

// //go:embed templates/*.tmpl is a compiler directive in Go.
// During compilation, Go reads the specified files and builds their raw contents
// directly into the compiled binary. This variable holds the read-only embedded files.
//
//go:embed templates/*.tmpl
var templatesFS embed.FS

//go:embed static/*
var StaticFS embed.FS

//go:embed icons/*.svg
var iconsFS embed.FS

// Templates holds the parsed HTML templates which are cached in memory.
var Templates *template.Template

// FuncMap defines a list of helper functions that can be called directly
// from inside the HTML template definitions (similar to custom tags or filters).
var FuncMap template.FuncMap

// InitTemplates compiles all HTML templates in templatesFS and registers FuncMap.
func InitTemplates() {
	FuncMap = template.FuncMap{
		// "icon" reads the raw SVG contents from the embedded icons FS and returns it
		// as raw template.HTML so that Go's template engine does not escape the XML tags.
		"icon": func(name string) template.HTML {
			data, err := iconsFS.ReadFile("icons/" + name + ".svg")
			if err != nil {
				return template.HTML("")
			}
			return template.HTML(data)
		},
		// "currency" formats exact numbers with commas and decimals, e.g. 1000.5 -> "1,000.50"
		"currency": func(d decimal.Decimal) string {
			return formatCurrency(d)
		},
		// "percent" accepts either Decimal or float64 via an empty interface type switch,
		// and outputs a formatted string with exactly 2 decimal places.
		"percent": func(v interface{}) string {
			switch val := v.(type) {
			case decimal.Decimal:
				return val.Round(2).StringFixed(2)
			case float64:
				return decimal.NewFromFloat(val).Round(2).StringFixed(2)
			default:
				return "0.00"
			}
		},
		"abs": func(d decimal.Decimal) decimal.Decimal {
			return d.Abs()
		},
		// "devClass" maps positive/negative values to CSS color classes.
		"devClass": func(d decimal.Decimal) string {
			if d.GreaterThan(decimal.Zero) {
				return "text-danger" // Overweight (red)
			} else if d.LessThan(decimal.Zero) {
				return "text-success" // Underweight (green)
			}
			return ""
		},
		"devSign": func(d decimal.Decimal) string {
			if d.GreaterThan(decimal.Zero) {
				return "+"
			}
			return ""
		},
		"usdSign": func(d decimal.Decimal) string {
			if d.GreaterThanOrEqual(decimal.Zero) {
				return "+"
			}
			return ""
		},
		// "usdTargetAdjusted" returns true if the effective target has drifted from base allocation.
		"usdTargetAdjusted": func(base, effective decimal.Decimal) bool {
			return base.Sub(effective).Abs().GreaterThan(decimal.NewFromFloat(0.01))
		},
		// "cryptoValue" returns total portfolio value minus the value of USD.
		"cryptoValue": func(snap model.PortfolioSnapshot) decimal.Decimal {
			val := snap.TotalValueUSD
			if usd, exists := snap.Assets["USD"]; exists {
				val = val.Sub(usd.ValueUSD)
			}
			return val
		},
		// "cryptoPct" sums the current portfolio percentage weights of all non-USD assets.
		"cryptoPct": func(snap model.PortfolioSnapshot) decimal.Decimal {
			sum := decimal.Zero
			for _, asset := range snap.Assets {
				if !asset.Symbol.IsUSD() {
					sum = sum.Add(asset.CurrentPercent)
				}
			}
			return sum
		},
		// "cryptoTargetPct" sums target percentage weights of all non-USD assets.
		"cryptoTargetPct": func(snap model.PortfolioSnapshot) decimal.Decimal {
			sum := decimal.Zero
			for _, asset := range snap.Assets {
				if !asset.Symbol.IsUSD() {
					sum = sum.Add(asset.TargetPercent)
				}
			}
			return sum
		},
		// "cryptoCount" returns the number of non-USD assets tracked in this cycle snapshot.
		"cryptoCount": func(snap model.PortfolioSnapshot) int {
			count := 0
			for _, asset := range snap.Assets {
				if !asset.Symbol.IsUSD() {
					count++
				}
			}
			return count
		},
		// "sortedAssets" returns the snapshots sorted descending by USD value for dashboard charts.
		"sortedAssets": func(snap model.PortfolioSnapshot) []model.AssetSnapshot {
			var list []model.AssetSnapshot
			for _, asset := range snap.Assets {
				list = append(list, asset)
			}
			sort.Slice(list, func(i, j int) bool {
				return list[i].ValueUSD.GreaterThan(list[j].ValueUSD)
			})
			if len(list) > 15 {
				list = list[:15]
			}
			return list
		},
		// "performanceAssets" returns the crypto snapshots sorted ascending by deviation percent.
		"performanceAssets": func(snap model.PortfolioSnapshot) []model.AssetSnapshot {
			var list []model.AssetSnapshot
			for _, asset := range snap.Assets {
				if !asset.Symbol.IsUSD() {
					list = append(list, asset)
				}
			}
			sort.Slice(list, func(i, j int) bool {
				return list[i].DeviationPercent.LessThan(list[j].DeviationPercent)
			})
			return list
		},
		// "maxUSD" returns the value of the largest asset in the snapshot, used for normalization.
		"maxUSD": func(snap model.PortfolioSnapshot) decimal.Decimal {
			max := decimal.NewFromFloat(1.0)
			for _, asset := range snap.Assets {
				if asset.ValueUSD.GreaterThan(max) {
					max = asset.ValueUSD
				}
			}
			return max
		},
		// "fillPercent" computes the bar width percent (0-100) relative to the max value asset.
		"fillPercent": func(val, max decimal.Decimal) int {
			if max.IsZero() {
				return 0
			}
			valF, _ := val.Float64()
			maxF, _ := max.Float64()
			pct := int(math.Round((valF / maxF) * 100))
			return pct
		},
		// "dateTime" formats Go time values to human-friendly local timestamps.
		"dateTime": func(t time.Time) string {
			return t.Local().Format("2006-01-02 03:04:05 PM")
		},
		// "badgeClass" returns css classes for action logs (buy = green/badge-buy, sell = red/badge-sell, info = gray).
		"badgeClass": func(action string) string {
			actionUpper := strings.ToUpper(action)
			if strings.HasPrefix(actionUpper, "BUY") {
				return "badge-buy"
			} else if strings.HasPrefix(actionUpper, "SELL") {
				return "badge-sell"
			}
			return "badge-info"
		},
		"badgeLabel": func(action string) string {
			actionUpper := strings.ToUpper(action)
			if strings.HasPrefix(actionUpper, "BUY") {
				return "BUY"
			} else if strings.HasPrefix(actionUpper, "SELL") {
				return "SELL"
			}
			return "INFO"
		},
	}

	// fs.Sub extracts a subdirectory namespace from an embedded FS to create a virtual filesystem.
	// This lets us compile files from "templatesFS" without including the prefix folder path in names.
	subFS, err := fs.Sub(templatesFS, "templates")
	if err != nil {
		panic(err)
	}

	// template.Must wraps compilation. If compiling files in subFS fails,
	// the application will panic and exit immediately during initialization rather than failing silently.
	Templates = template.Must(template.New("").Funcs(FuncMap).ParseFS(subFS, "*.tmpl"))
}

// formatCurrency formats a decimal value into a currency format string (e.g. 1234567.89 -> "1,234,567.89")
func formatCurrency(d decimal.Decimal) string {
	parts := strings.Split(d.Round(2).StringFixed(2), ".")
	integer := parts[0]
	fraction := parts[1]

	var result []string
	length := len(integer)
	startIndex := 0
	if strings.HasPrefix(integer, "-") {
		startIndex = 1
	}

	// Loop backwards through the string, grouping digits in chunks of three
	for i := length; i > startIndex; i -= 3 {
		start := i - 3
		if start < startIndex {
			start = startIndex
		}
		result = append([]string{integer[start:i]}, result...)
	}

	prefix := ""
	if startIndex == 1 {
		prefix = "-"
	}

	return prefix + strings.Join(result, ",") + "." + fraction
}
