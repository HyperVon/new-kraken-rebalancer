# UI Manual QA — Interaction Checklist

Read this from [SKILL.md](SKILL.md) Step 3. Mark each case `pass` / `fail` /
`blocked` in `$QA_DIR`. Prefer `HtmlIds` / visible labels from `:common`.

Stable ids (see `HtmlAttrs.HtmlIds`):

| Id | Control |
| :--- | :--- |
| `save-button` | Settings save |
| `total-allocated-display` | Allocation total badge |
| `allocations-container` | Allocation rows |
| `new-symbol-input` | Add-asset symbol field |
| `show-dry-run-checkbox` | History dry-run trade filter |
| `history-views-select` | Views dropdown |
| `history-save-view-btn` / `history-set-default-btn` / `history-delete-view-btn` | View actions |
| `portfolio-value-chart` / `asset-holdings-chart` / `allocation-drift-chart` / `cumulative-pl-chart` | Chart canvases |
| `trade-table-body` | Trade log rows |
| `history-stats` / `stat-*` | Summary cards |
| `history-zoom-btn` / `history-views-select` | Zoom + view toolbar (glass styling) |
| `history-chart-scrubber-input` | Pan scrubber under each History chart |

---

## Post-deploy / stylesheet (`STYLE-*`)

Run **before** style-sensitive History/Dashboard cases. Stylesheet responses are
cached **24h**; stale CSS makes new controls look like default browser buttons.

| ID | Steps | Expected |
| :--- | :--- | :--- |
| STYLE-1 | Hard-refresh `/` (bypass cache) **or** inspect page `<link rel="stylesheet">` | Href is `/static/style.css?v=` with a non-empty version query (cache-bust after deploy) |
| STYLE-2 | Open `/history`; inspect Views select, Save / Set default / Delete, Zoom − / + / Reset | Dark-theme glass controls (`history-zoom-btn`, view toolbar); **not** white native OS `<button>` / `<select>` chrome |

---

## Global

| ID | Steps | Expected |
| :--- | :--- | :--- |
| GLOBAL-1 | From `/`, click **History** nav | URL `/history`; History title; active tab highlight |
| GLOBAL-2 | Click **Settings** nav | URL `/settings`; Settings form; active tab |
| GLOBAL-3 | Click **Dashboard** nav | URL `/`; summary cards visible |
| GLOBAL-4 | Confirm brand wordmark | Header shows **Kraken** + **Rebalancer** on each page |
| GLOBAL-5 | Dashboard only: status cluster | **LIVE** or **DELAYED** + **Data Age** visible near nav |
| GLOBAL-6 | Resize viewport to **~1280–1440px** width; reload `/` | Header + status cluster remain readable (see REGRESSION-1) |

---

## Production regression guardrails (`REGRESSION-*`)

Explicit checks for recent production UI regressions. Run on **desktop/laptop**
width (~1280–1440px), not only narrow mobile.

| ID | Steps | Expected |
| :--- | :--- | :--- |
| REGRESSION-1 | Dashboard at ~1280–1440px (`GLOBAL-6`) | Status cluster **LIVE**/**DELAYED** + **Data Age** not vertically squished, clipped, or stacked illegibly |
| REGRESSION-2 | Dashboard → Asset Performance deviation legend | **Over target** and **Under target** each with amber/blue dot; labels spaced — not concatenated run-on text |
| REGRESSION-3 | History chart legends (Portfolio Value, Asset Holdings, Allocation Drift, Cumulative P&L) | Legend swatches use line/point markers; not heavy bordered box chips around every label |

---

## Dashboard (`/`)

| ID | Steps | Expected |
| :--- | :--- | :--- |
| DASH-1 | Load `/`; wait for seed | Total Portfolio / Cash / Crypto cards show non-placeholder values |
| DASH-2 | Inspect allocation section | Bars for major holdings; symbols readable |
| DASH-3 | Inspect Asset Performance | Table rows with Dev %; over/under legend present |
| DASH-4 | Inspect Recent Activity | At least empty-cycle or trade/info rows; badges readable |
| DASH-5 | Wait ≥ one SSE/HTMX refresh cycle (or ~loopDelay) | Data Age updates or fragment refreshes without full blank flash |

---

## Settings (`/settings`)

Mutate only inside the throwaway sim config. **Restore** changed fields before
leaving when later cases depend on defaults.

| ID | Steps | Expected |
| :--- | :--- | :--- |
| SETT-1 | Open Settings | Global parameters + Safety Modes + allocations visible |
| SETT-2 | Note current Loop Interval; change by +1; **Save Configuration** | Save succeeds (no error); value persists after reload of `/settings` |
| SETT-3 | Restore Loop Interval; save again | Prior value restored |
| SETT-4 | Toggle **Dry Run Mode** on then off (save each time if required) | Checkbox state persists after save+reload; labels remain clear |
| SETT-5 | Confirm **Simulation Mode** stays checked in this QA run | Simulation remains on (do not turn off) |
| SETT-6 | Read Total allocation badge | Shows **100.00%** (or valid total) with success styling when sum is 100 |
| SETT-7 | **Add Asset**: enter a disposable symbol (e.g. `SOL`), add row, set % so total ≠ 100 | Total badge leaves 100% / warns; Save should not silently accept invalid total if UI validates |
| SETT-8 | Remove the disposable row (or fix percents to 100%); save if needed | Back to valid 100% universe; no stuck empty rows |
| SETT-9 | Safety Modes block | Dry Run + Simulation are grouped under **Safety Modes**, not mixed into numeric grid |

---

## History — range & stats (`/history`)

Wait until sync banner completes (or is absent) before chart assertions.

| ID | Steps | Expected |
| :--- | :--- | :--- |
| HIST-1 | Open `/history` | Four summary cards + charts or explicit empty state |
| HIST-2 | Click **24h** | Cards + charts update; ATH card title may become period high |
| HIST-3 | Click **7d**, **30d**, **90d**, **All** in turn | Each changes series/window; no blank stuck spinner |
| HIST-4 | Note Total Trades / Volume / Fees on **30d** vs **24h** | Finite range stats differ or stay coherent (not NaN / blank) |

---

## History — views / presets

| ID | Steps | Expected |
| :--- | :--- | :--- |
| HIST-VIEW-1 | Select **Overview** | 30d (or preset range); series visible |
| HIST-VIEW-2 | Select **Day · Total only** | **24h** applied; Portfolio Value legend lists **Total** only (Cash/Crypto/per-asset labels absent); canvas draws **Total** line only — other series hidden, not merely dimmed |
| HIST-VIEW-3 | Select **Week · Allocation** | 7d applied |
| HIST-VIEW-4 | Select **Month · P&L** | 30d; dry-run filter matches preset intent |
| HIST-VIEW-5 | Manually change time range while a named view is selected | Select shows **Custom (unsaved)** (or equivalent); Set default / Delete disabled as designed |
| HIST-VIEW-6 | **Save view…** with name `qa-temp`; confirm it appears in select | Custom option listed |
| HIST-VIEW-7 | **Set as default** on `qa-temp`; reload `/history` | `qa-temp` (or its settings) applied on load |
| HIST-VIEW-8 | **Delete** `qa-temp` | Removed; built-ins still present; cannot delete Overview |

---

## History — charts, zoom, dry-run filter

| ID | Steps | Expected |
| :--- | :--- | :--- |
| HIST-CHART-1 | Portfolio Value chart has drawn series | Canvas non-empty after seed |
| HIST-CHART-2 | Asset Holdings chart drawn | Same |
| HIST-CHART-3 | Scroll to Allocation Drift | Unstacked %-style series (0–100 domain intent) |
| HIST-CHART-4 | Cumulative P&L chart drawn | Axis ticks readable; negatives use `-$` if present |
| HIST-CHART-5 | Click chart legend item to hide a series (if interactive) | Series hides; click again restores |
| HIST-ZOOM-1 | On Portfolio Value: **Zoom +** twice | X-axis time window narrows (fewer ticks / shorter span visible); bottom scrubber becomes **enabled** (not stuck disabled) |
| HIST-ZOOM-2 | **Zoom −** | Window widens toward prior span |
| HIST-ZOOM-3 | **Reset** | Full selected time range restored; scrubber **disabled** again |
| HIST-ZOOM-4 | Change time range after zoom | Charts rebuild; zoom + scrubber state cleared |
| HIST-ZOOM-5 | After HIST-ZOOM-1: drag-select a region on Portfolio Value canvas | Selection **zooms** the x-axis; drag does **not** pan the chart sideways; scrubber becomes **enabled** after drag-zoom (same as Zoom +) |
| HIST-ZOOM-6 | After zoom (buttons **or** drag/wheel): drag bottom scrubber (`history-chart-scrubber-input`) left↔right | Scrubber is **draggable**; **chart x-window actually pans** (time ticks / series shift). Fail if thumb moves but the chart view is unchanged |
| HIST-ZOOM-7 | Wheel-zoom on Portfolio Value (if supported) then scrubber | Scrubber enables after wheel zoom; sliding pans the window (same contract as HIST-ZOOM-6) |
| HIST-DRY-1 | Toggle **Show Dry Run Trades** | Trade table row set changes (or stays empty consistently); charts that include dry-run P&L update if applicable |

---

## History — trade log

| ID | Steps | Expected |
| :--- | :--- | :--- |
| HIST-LOG-1 | Scroll to trade table | Headers: Time, Pair, Side, Volume, USD, Status |
| HIST-LOG-2 | If rows exist | BUY/SELL badges + SUCCESS/FAILED/DRY RUN readable |
| HIST-LOG-3 | If no rows | Empty state is understandable (not a broken table) |

---

## Optional / opportunistic

Run when easy to trigger; otherwise mark `blocked` with reason.

| ID | Steps | Expected |
| :--- | :--- | :--- |
| OPT-1 | Narrow viewport (~375px) or note desktop-only risk | Nav/forms usable or known limitation documented |
| OPT-2 | Hard refresh during History sync (if banner visible) | Recovers without permanent error page |
| OPT-3 | Open `/api/health` | JSON `status` UP |

---

## Maintenance

When UI gains controls (new buttons, tabs, filters), **add cases here** in the
same ID style and mention them in CHANGELOG / skill description triggers if
needed. Prefer ids from `:common` over copying brittle class names.
