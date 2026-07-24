---
name: frontend-js-development
description: >-
  Client Kotlin/JS — EventSource SSE, SharedFlow payload consumption, HTMX
  hooks, Chart.js deep-clone, History timeframe updating all 4 summary cards,
  and Karma coverage thresholds. Use when editing frontend-js/src.
---

# Kotlin/JS Client Development (`:frontend-js`)

Compiles via Kotlin JS IR to `/static/rebalancer.js`.

## Responsibilities

1. **SSE** — `EventSource` on `Routes` status stream (`/api/status/stream`);
   parse JSON snapshot payloads broadcast from server `SharedFlow`.
2. **HTMX hooks** — `htmx:afterSwap` / `htmx:configRequest` to rebind charts after
   fragment swaps.
3. **Chart.js** — deep-clone options before each render.
4. **History timeframe** — when user selects 24h / 7d / 30d / 90d / All, update
   **all four** summary cards (**All-Time High** / **Period High**, **Total
   Trades**, **Total Volume Traded**, **Total Fees Paid**) plus charts and trade
   table.

## Chart.js integrity

```kotlin
val chartOptions = JSON.parse<dynamic>(JSON.stringify(DEFAULT_CHART_OPTIONS))
```

Never pass a shared options object by reference.

## DOM lifecycle

- Clear intervals/timeouts and `removeEventListener` on detach.
- Null-guard: `document.getElementById(...) ?: return`.

## Shared `:common` constants

```kotlin
import com.gemini.krakenbot.view.util.CssClass
import com.gemini.krakenbot.view.util.HtmlIds
```

Do not redefine HTML IDs or CSS class name strings in JS.

## Coverage (Karma / Istanbul)

Thresholds in `frontend-js/karma.config.d/coverage.js`:

| Metric | Minimum |
| :--- | ---: |
| Statements | 90% |
| Functions | 90% |
| Lines | 90% |
| Branches | 75% |

```bash
./gradlew :frontend-js:jsTest
```

## Checklist

- [ ] SSE consumes `/api/status/stream` payloads correctly
- [ ] Timeframe change updates **all 4** summary cards
- [ ] Chart options deep-cloned; listeners cleaned up
- [ ] `:common` IDs/classes used; Karma thresholds still met
