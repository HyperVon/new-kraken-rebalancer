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
| `portfolio-value-chart` / `asset-holdings-chart` / `allocation-drift-chart` / `cumulative-net-cash-flow-chart` | Chart canvases |
| `trade-table-body` | Trade log rows |
| `history-stats` / `stat-*` | Six summary cards (ATH, trades, volume, fees, avg fee, avg slippage) |
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
| GLOBAL-3 | Click **Dashboard** nav | URL `/`; hero / summary visible |
| GLOBAL-4 | Confirm brand wordmark | Header shows **Kraken** + **Rebalancer** on each page |
| GLOBAL-5 | Dashboard status cluster | Chip reads **STREAM** or **STALE** (never **LIVE** / **DELAYED**) plus a relative age (e.g. `12s`) and clock time |
| GLOBAL-6 | Resize viewport to **~1280–1440px** width; reload `/` | Header + mode plate + stream/age stay readable (see REGRESSION-1) |
| GLOBAL-7 | Visit `/`, `/history`, `/settings` | Brand-adjacent mode plate on **every** page; sim QA shows **SIMULATION** |
| GLOBAL-8 | Hover / inspect mode-plate `title` on Dashboard | Tooltip explains the active mode (simulation / dry-run / live consequence copy) |

Mode-plate precedence (assert when mode is known): **SIMULATION** if
simulation is on (even if dry-run is also on); else **DRY RUN**; else
**LIVE TRADING**.

---

## Production regression guardrails (`REGRESSION-*`)

Explicit checks for recent production UI regressions. Run on **desktop/laptop**
width (~1280–1440px), not only narrow mobile.

| ID | Steps | Expected |
| :--- | :--- | :--- |
| REGRESSION-1 | Dashboard at ~1280–1440px (`GLOBAL-6`) | Mode plate + **STREAM**/**STALE** + relative age/time not vertically squished, clipped, or stacked illegibly |
| REGRESSION-2 | Dashboard → Asset Performance deviation legend | **Over target** and **Under target** each with amber/blue dot; labels spaced — not concatenated run-on text |
| REGRESSION-3 | History chart legends (Portfolio Value, Asset Holdings, Allocation Deviation, Cumulative Net Cash Flow) | Legend swatches use line/point markers; not heavy bordered box chips around every label |

---

## Dashboard (`/`)

| ID | Steps | Expected |
| :--- | :--- | :--- |
| DASH-1 | Load `/`; wait for seed | **Total Portfolio** hero shows value, **24H** delta (or empty when history too short), and sparkline when ≥2 points |
| DASH-2 | Inspect Cash / Crypto side tiles | **Cash (USD)** and **Crypto Assets** show value, progress bar, and target/meta (Cash includes Dev % when USD exists) |
| DASH-3 | Inspect allocation section | Bars for major holdings; symbols readable |
| DASH-4 | Inspect Asset Performance | Table rows with Dev %; over/under legend present |
| DASH-5 | Inspect Recent Activity | Cycles grouped (Cycle badge + action count or “No trades — portfolio within tolerance”); **View all history** links to `/history` |
| DASH-6 | Wait ≥ one SSE/HTMX refresh cycle (or ~loopDelay) | Relative age/time updates or fragment refreshes without full blank flash |

---

## Settings (`/settings`)

Mutate only inside the throwaway sim config. **Restore** changed fields before
leaving when later cases depend on defaults.

| ID | Steps | Expected |
| :--- | :--- | :--- |
| SETT-1 | Open Settings | Global parameters + Safety Modes + allocations visible |
| SETT-2 | Note current Loop Interval; change by +1; **Save Configuration** | Save succeeds (no error); value persists after reload of `/settings` |
| SETT-3 | Restore Loop Interval; save again | Prior value restored |
| SETT-4 | Toggle **Dry Run Mode** card on then off (save each time if required) | Card shows visible **ON** / **OFF** pill; state persists after save+reload |
| SETT-5 | Confirm **Simulation Mode** stays on in this QA run | Simulation card remains **ON** (do not turn off) |
| SETT-6 | Read Total allocation badge | Shows **100.00%** (or valid total) with success styling when sum is 100 |
| SETT-7 | **Add Asset**: enter a disposable symbol (e.g. `SOL`), add row, set % so total ≠ 100 | Total badge leaves 100% / warns; Save should not silently accept invalid total if UI validates |
| SETT-8 | Remove the disposable row (or fix percents to 100%); save if needed | Back to valid 100% universe; no stuck empty rows |
| SETT-9 | Safety Modes block | Simulation + Dry Run are rich cards (title, consequence copy, ON/OFF), grouped under **Safety Modes**, not bare checkboxes in the numeric grid |

---

## History — range & stats (`/history`)

Wait until sync banner completes (or is absent) before chart assertions.

| ID | Steps | Expected |
| :--- | :--- | :--- |
| HIST-1 | Open `/history` | **Six** summary cards (ATH, Total Trades, Volume, Fees, Avg Fee Rate, Avg Slippage) + charts or explicit empty state |
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
| HIST-VIEW-4 | Select **Month · Net Cash Flow** | 30d; dry-run filter matches preset intent |
| HIST-VIEW-5 | Manually change time range while a named view is selected | Select shows **Custom (unsaved)** (or equivalent); Set default / Delete disabled as designed |
| HIST-VIEW-6 | **Save view…** with name `qa-temp`; confirm it appears in select | Custom option listed |
| HIST-VIEW-7 | **Set as default** on `qa-temp`; reload `/history` | `qa-temp` (or its settings) applied on load |
| HIST-VIEW-8 | **Delete** `qa-temp` | Removed; built-ins still present; cannot delete Overview |

---

## History — charts, zoom, dry-run filter

| ID | Steps | Expected |
| :--- | :--- | :--- |
| HIST-CHART-1 | Portfolio Value chart has drawn series | Canvas non-empty after seed; header row has title + Zoom − / + / Reset |
| HIST-CHART-2 | Asset Holdings chart drawn | Same consolidated header contract |
| HIST-CHART-3 | Scroll to Allocation Deviation from Target | Signed %-style series around 0%; positive = overweight, negative = underweight; Y-axis is not fixed to 0–100% |
| HIST-CHART-4 | Cumulative Net Cash Flow chart drawn | Axis ticks readable; negatives use `-$` if present; caption explains dry-run estimated fees / not accounting P&L |
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
| HIST-LOG-1 | Scroll to trade table | Headers include Time, Pair, Side, Volume, USD, Status (and price/fee columns when present) |
| HIST-LOG-2 | If successful (non–dry-run) rows exist | Status is a subtle success **dot** (tooltip SUCCESS), not a loud SUCCESS badge |
| HIST-LOG-3 | If price or fee is zero / absent | Cell shows em dash (**—**), not `0.00` / `0.00000000` |
| HIST-LOG-4 | If failed or dry-run rows exist | **FAILED** / **DRY RUN** badges remain readable; BUY/SELL badges still present |
| HIST-LOG-5 | If no rows | Empty state is understandable (not a broken table) |

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
