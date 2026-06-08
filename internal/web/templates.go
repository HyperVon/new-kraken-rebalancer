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

//go:embed templates/*.tmpl
var templatesFS embed.FS

//go:embed static/*
var StaticFS embed.FS

//go:embed icons/*.svg
var iconsFS embed.FS

// Compiled templates
var Templates *template.Template
var FuncMap template.FuncMap

func InitTemplates() {
	FuncMap = template.FuncMap{
		"icon": func(name string) template.HTML {
			data, err := iconsFS.ReadFile("icons/" + name + ".svg")
			if err != nil {
				return template.HTML("")
			}
			return template.HTML(data)
		},
		"currency": func(d decimal.Decimal) string {
			return formatCurrency(d)
		},
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
		"devClass": func(d decimal.Decimal) string {
			if d.GreaterThan(decimal.Zero) {
				return "text-danger" // Overweight
			} else if d.LessThan(decimal.Zero) {
				return "text-success" // Underweight
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
		"usdTargetAdjusted": func(base, effective decimal.Decimal) bool {
			return base.Sub(effective).Abs().GreaterThan(decimal.NewFromFloat(0.01))
		},
		"cryptoValue": func(snap model.PortfolioSnapshot) decimal.Decimal {
			val := snap.TotalValueUSD
			if usd, exists := snap.Assets["USD"]; exists {
				val = val.Sub(usd.ValueUSD)
			}
			return val
		},
		"cryptoPct": func(snap model.PortfolioSnapshot) decimal.Decimal {
			sum := decimal.Zero
			for _, asset := range snap.Assets {
				if !asset.Symbol.IsUSD() {
					sum = sum.Add(asset.CurrentPercent)
				}
			}
			return sum
		},
		"cryptoTargetPct": func(snap model.PortfolioSnapshot) decimal.Decimal {
			sum := decimal.Zero
			for _, asset := range snap.Assets {
				if !asset.Symbol.IsUSD() {
					sum = sum.Add(asset.TargetPercent)
				}
			}
			return sum
		},
		"cryptoCount": func(snap model.PortfolioSnapshot) int {
			count := 0
			for _, asset := range snap.Assets {
				if !asset.Symbol.IsUSD() {
					count++
				}
			}
			return count
		},
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
		"maxUSD": func(snap model.PortfolioSnapshot) decimal.Decimal {
			max := decimal.NewFromFloat(1.0)
			for _, asset := range snap.Assets {
				if asset.ValueUSD.GreaterThan(max) {
					max = asset.ValueUSD
				}
			}
			return max
		},
		"fillPercent": func(val, max decimal.Decimal) int {
			if max.IsZero() {
				return 0
			}
			valF, _ := val.Float64()
			maxF, _ := max.Float64()
			pct := int(math.Round((valF / maxF) * 100))
			return pct
		},
		"dateTime": func(t time.Time) string {
			return t.Local().Format("2006-01-02 03:04:05 PM")
		},
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

	subFS, err := fs.Sub(templatesFS, "templates")
	if err != nil {
		panic(err)
	}
	Templates = template.Must(template.New("").Funcs(FuncMap).ParseFS(subFS, "*.tmpl"))
}

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
