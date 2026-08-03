---
name: frontend-js-development
description: >-
  Client Kotlin/JS — HTMX hook lifecycle, stream-chip STREAM/STALE timing,
  Chart.js deep-clone, History timeframe updating all 6 summary cards,
  trade-table formatting, zoom/scrubber via chart.zoomScale, and Karma
  coverage thresholds. Use when editing frontend-js/src.
---

# Kotlin/JS Client Development (`:frontend-js`)

Compiles via Kotlin JS IR to `/static/rebalancer.js`.

## Responsibilities

1. **SSE timing (not transport)** — HTMX SSE extension owns
   `/api/status/stream`; `:frontend-js` updates stream-chip age after fragment
   swaps, not EventSource parsing.
2. **HTMX hooks** — `htmx:afterSwap` / `htmx:configRequest` to rebind charts after
   fragment swaps.
3. **Chart.js** — deep-clone options before each render; re-attach functions after
   clone.
4. **History timeframe** — when user selects 24h / 7d / 30d / 90d / All, update
   **all six** summary cards (**All-Time High** / **Period High**, **Total
   Trades**, **Total Volume Traded**, **Total Fees Paid**, **Avg Fee Rate**,
   **Avg Slippage**) plus charts and trade table.
5. **History JSON parsing** — `HistoryJsonParsing.kt` maps `/api/history/*`
   payloads onto typed `:common` `api/` DTOs (`PortfolioSnapshot`, `TradeRecord`,
   `HistoryStats`, `SyncProgressResponse`). Do not reintroduce removed
   `JsModels.kt` external interfaces.

## SSE (correct ownership)

**Server → browser:** the HTMX SSE extension connects to `/api/status/stream`
and triggers a dashboard fragment refresh on `sse:message`
(`DashboardShellComponent`: `hx-ext="sse"`, `sse-connect`, `hx-trigger="load, sse:message"`).

**`:frontend-js` does not use `EventSource`.** Client responsibilities after a swap:

- `htmx:afterSwap` → `updateAge()` + `reapplySort()`
- `setInterval(updateAge, 1000)` so STREAM → STALE flips without new SSE events
- Chart re-init via HTMX hooks / page detectors in `initOnLoad()` — not SSE parsing

## Status chip = stream health, not trading mode

`updateAge()` owns the dashboard header chip. It toggles
`CssClass.Utility.Live` / `CssClass.Utility.Delayed` (styling only) and writes
**`ViewText.STREAM`** when fresh, **`ViewText.STREAM_STALE`** when past
`PrecisionConstants.STALE_THRESHOLD_SECONDS` from `:common` — never duplicate
magic seconds in frontend-only constants.

Settings allocation total uses **`CssClass.Form.AllocationTotalOk`** /
**`AllocationTotalBad`** (not the stream Live/Delayed utilities).

- The chip reports **SSE freshness**. It must never read `LIVE` / `DELAYED` or
  otherwise imply that real orders are executing.
- Trading mode is server-rendered in the settings-backed mode plate
  (`SIMULATION` / `DRY RUN` / `LIVE TRADING`) — see
  [ktor-html-views](../ktor-html-views/SKILL.md). Do not infer or re-render mode
  from client state.

## Trade table rendering (History)

- Trade **Price** formats at crypto precision (**4–8** decimal places);
  **Fee** formats at **2–4** dp. Both render `ViewText.EM_DASH` for
  zero/absent values (never `0.00000000`).
- A plain success row (`success && !dryRun`) renders a quiet
  `CssClass.Table.StatusDot` span with a `SUCCESS` tooltip; **dry-run and failed
  rows keep their labelled badge** so risky rows stay scannable.
- Keep the estimated-value tooltip on price / fee / slippage cells.

## Chart.js integrity

```kotlin
val chartOptions = JSON.parse<dynamic>(JSON.stringify(DEFAULT_CHART_OPTIONS))
```

Never pass a shared options object by reference.

**Functions are stripped by `JSON.stringify`.** Re-attach callbacks after clone
(e.g. `plugins.zoom.zoom.onZoomComplete` to re-sync the History pan scrubber).

### Zoom / pan (History)

- **Drag / wheel / pinch** → zoom only (`pan.enabled = false`).
- **Pan** → bottom range scrubber when zoomed.
- After **any** zoom gesture, scrubber must be re-enabled via `onZoomComplete`
  (not only after toolbar Zoom + / − / Reset).
- To set absolute x bounds, call **`chart.zoomScale('x', {min, max}, 'none')`**.
  Writing `options.scales.x.min/max` + `update()` is **ignored** once
  chartjs-plugin-zoom owns the axis — that bug looks like “scrubber moves but
  chart doesn’t.”
- Cover both paths in Karma: `zoomScale` present + fallback without it.
- Manual QA: `HIST-ZOOM-5` / `HIST-ZOOM-6` / `HIST-ZOOM-7` in
  [ui-manual-qa/checklist.md](../ui-manual-qa/checklist.md).

## DOM lifecycle

### Lifecycle (main.kt contract)

1. `main()` registers globals once (dashboard, settings, history).
2. A single `htmx:afterSwap` listener rebinds age display + table sort.
3. `initOnLoad()` gates page-specific init on `HtmlIds` presence.
4. On History chart rebuild: deep-clone Chart.js options, re-attach
   `onZoomComplete`, enable the scrubber after any zoom path.
5. Clear intervals/listeners when adding History-only timers.

- Clear intervals/timeouts and `removeEventListener` on detach.
- Null-guard: `document.getElementById(...) ?: return`.

## Shared `:common` constants

```kotlin
import com.gemini.krakenbot.view.util.CssClass
import com.gemini.krakenbot.view.util.HtmlIds
```

Production JS consumes the generated shared catalogs for IDs, attributes, HTML
tags, CSS classes, routes, and `HtmlQueries` selectors. Do not redefine those
contracts in production JS. Keep `CssClass` focused on classes and
`HtmlQueries` focused on selectors.

When a JS test verifies an emitted DOM/HTML contract, use an independent raw
expected literal for the ID, class, tag, attribute, event, or selector. Typed
catalog values are still fine for internal setup that is not itself the
contract assertion; see [write-kotest](../write-kotest/SKILL.md).

Do not introduce ARIA attributes, ARIA roles, or accessibility-only DOM text
unless the user explicitly requests accessibility work. Preserve unrelated
existing metadata rather than broadening a frontend task into remediation.

## Coverage (Karma / Istanbul)

Thresholds in `frontend-js/karma.config.d/coverage.js`:

| Metric | Minimum |
| :--- | ---: |
| Statements | 90% |
| Functions | 90% |
| Lines | 90% |
| Branches | 75% |

```bash
./gradlew :frontend-js:jsBrowserTest
```

## Docs screenshots

Chart / History / SSE-driven UI changes that alter README visuals should refresh
`docs/images/*.png` via
[docs-screenshot-refresh](../docs-screenshot-refresh/SKILL.md) and keep
[docs/USER_GUIDE.md](../../../docs/USER_GUIDE.md) aligned
([user-guide](../user-guide/SKILL.md)).

Visual redesign / polish passes:
[ui-visual-review](../ui-visual-review/SKILL.md) →
[ui-visual-implement](../ui-visual-implement/SKILL.md). After chart/DOM
behavior changes, run [ui-manual-qa](../ui-manual-qa/SKILL.md) (History
views, zoom, scrubber pan, dry-run filter, legend toggles).

## Checklist

- [ ] HTMX owns SSE transport; client only runs `updateAge` / chart re-init after swap
- [ ] Status chip reads `STREAM` / `STALE` and never implies live trading
- [ ] Timeframe change updates **all 6** summary cards
- [ ] Trade rows: 2dp USD, em-dash for zero, dot for plain success, badge for
      dry-run/failed
- [ ] Chart options deep-cloned; zoom callbacks re-attached after clone
- [ ] Zoomed History charts pan via `zoomScale` + scrubber (not options.scales only)
- [ ] `:common` IDs/classes used; Karma thresholds still met
- [ ] Visual changes → run docs-screenshot-refresh when shipping docs
