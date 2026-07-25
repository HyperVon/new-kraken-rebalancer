# User Guide — Kraken Rebalancer

This guide walks through the live dashboard: what each screen is for, how to
configure the bot, and how to interpret charts and trade logs. Screenshots below
were captured in **simulation mode** so values are illustrative, not live
exchange balances.

For install and first launch, see the [README Getting Started](../README.md#getting-started)
section. For rebalancing math internals, see [ALGORITHM.md](ALGORITHM.md).

---

## Safety modes (read this first)

Two independent settings control how “real” trading is. Change them from
**Settings** or in `rebalancer-config.json`.

| Mode | What it does | When to use |
| :--- | :--- | :--- |
| **Simulation Mode** | Routes all exchange calls to an offline Kraken emulator (random-walk prices, seeded history). No API keys required. | Learning the UI, demos, documentation screenshots |
| **Dry Run Mode** | Calculates intended orders but does not place them (logs `[DRY RUN]` / `[EMULATOR DRY RUN]`). | Rehearsing strategies against live or simulated markets |

**Live trading** is `simulation` off **and** `dryRun` off with real API keys —
that moves real funds. Prefer simulation (and/or dry run) until you understand
the screens below.

On **Settings**, these appear as two toggle cards — **Simulation Mode** first,
then **Dry Run Mode** — each with an **ON** / **OFF** state pill and a short line
of consequence prose (e.g. "No real funds are ever touched"). Keep at least one
safety on unless you intend to trade live.

![Settings — Safety modes and targets](images/settings.png)

---

## Navigation

Dashboard, History, and Settings share the same top nav tabs (active page is
highlighted). The header brand reads **Kraken** + **Rebalancer**, and right next
to it a **persistent mode plate** shows the current trading mode on **every**
page: **SIMULATION** (blue), **DRY RUN** (amber), or **LIVE TRADING** (red).
Precedence is simulation first (even if dry run is also on), then dry run, then
live. The plate reflects your Settings, and hovering it reveals a tooltip
explaining the consequence (e.g. "Live trading — real orders execute with real
funds").

| Page | Route | Purpose |
| :--- | :--- | :--- |
| **Dashboard** | `/` | Live portfolio snapshot, allocation bars, performance table, recent activity |
| **History** | `/history` | Time-range charts, summary cards, full trade log |
| **Settings** | `/settings` | Loop timing, triggers, fiat deployment, safety modes, allocations |

The dashboard pushes live updates over Server-Sent Events (`/api/status/stream`).
**Only the Dashboard** shows a stream-health chip next to the tabs: **STREAM**
(green) when data is flowing, or **STALE** when the feed has gone quiet. Beside
it are the relative age of the last update (e.g. `12s`) and its clock time.

The stream chip describes **feed health, not trading mode** — a healthy
**STREAM** chip does not mean live trading is on. Always read the mode plate for
that.

---

## Dashboard

![Dashboard overview](images/dashboard.png)

### Portfolio overview

The top of the Dashboard is a hero card plus two tiles:

| Element | Meaning |
| :--- | :--- |
| **Total Portfolio** (hero) | Mark-to-market value of all tracked assets, with a signed **24H** delta when a true ≥24h baseline exists (green up / red down / muted flat), current **drawdown**, and an inline **sparkline** of recent retained snapshots. |
| **Cash (USD)** (tile) | Fiat balance with a progress bar for its current share, plus **effective** target % after drawdown-based fiat deployment (and the configured **Base** target when they differ) and deviation. |
| **Crypto Assets** (tile) | Combined crypto value with a progress bar for its share, its target %, and how many crypto symbols you hold. |

### Allocation & performance

![Dashboard allocation, table, and activity](images/dashboard-bottom.png)

- **Portfolio Allocation (Top Assets)** — Horizontal bars for the largest
  holdings by USD value (and cash), showing up to the **top 15**. Bar lengths are
  relative to the largest holding (the biggest fills the track), with each bar
  labelled by its USD value and current %. Each symbol uses a fixed color (BTC
  amber, ETH violet, USD slate) shared with History charts.
- **Asset Performance** — Sortable table of price, value, target %, current %,
  and **Dev %** (how far each asset is from target, with dollar impact).
  Amber = over target, blue = under target (not profit/loss green/red); a small
  legend sits above the table.
- **Recent Activity** — A **cycle-grouped feed** of the most recent rebalance
  cycles (up to 6). Each cycle is headed by a **Cycle** badge, an action summary
  (e.g. `3 actions`, or **No trades — portfolio within tolerance**), and both a
  relative and an absolute timestamp. Cycles with trades expand to the individual
  actions beneath:
  - Blue **INFO** — compact deviation notes (e.g. `Deviation: BTC 5.2%`)
  - Red **SELL** / green **BUY** — orders with USD to 2 decimals
  - A **View all history** link at the bottom jumps to `/history`.

Use this page as your “is the bot healthy right now?” view.

---

## Settings

Open **Settings** from the shared top nav, or go to `/settings`.

![Settings page](images/settings.png)

### Global parameters

| Field | Purpose |
| :--- | :--- |
| **Loop Interval (Seconds)** | How often the rebalancer wakes up to snapshot and potentially trade. |
| **Deviation Trigger (%)** | Minimum absolute deviation from target before an asset can trigger trades. |
| **Dust Threshold ($)** | Skip orders smaller than this USD amount (avoids exchange min-size noise). |
| **Fiat Max Drawdown (%)** | Drawdown at which cash is fully eligible for deployment into crypto. |
| **Fiat Deployment Exponent** | Shape of the cash→crypto deployment curve as drawdown grows (1.0 ≈ linear). |

### Safety modes

Simulation and Dry Run live in their own **Safety Modes** block (not mixed into
the numeric parameter grid), as two toggle cards each with an **ON** / **OFF**
state pill:

| Card | Purpose |
| :--- | :--- |
| **Simulation Mode** | Runs the whole strategy against an offline Kraken emulator — no real funds are ever touched. |
| **Dry Run Mode** | Validates conditions and builds real Kraken orders but never submits them. |

**Simulation Mode** is listed first. **Save Configuration** lives in the page
header next to the nav (not at the bottom of the form). Saving hot-reloads the
running loop — no process restart required.

### Target allocations

- Every allocation row is a symbol + target percent.
- **Total** must read **100.00%** (green badge) before save succeeds.
- **USD is required** — cash is part of the strategy, not optional.
- **Add Asset** / **Remove** change the universe without restarting the app.

Deeper behavior (drawdown deployment, sell-then-buy, dust) is documented in
[ALGORITHM.md](ALGORITHM.md).

---

## History

The History page is for longer-term review: performance charts and the full
trade log. Use the **24h / 7d / 30d / 90d / All** pills to change the window —
all six summary cards and the charts update together.

### Views

The **Views** control (next to the time-range pills) applies a preset across
range, series visibility, and **Show Dry Run Trades**:

| Preset | Window | Intent |
| :--- | :--- | :--- |
| **Overview** (default) | 30d | All series visible |
| **Day · Total only** | 24h | Portfolio Value shows **Total Portfolio** only |
| **Week · Allocation** | 7d | Full series for allocation review |
| **Month · Net Cash Flow** | 30d | Cumulative net cash flow focus; dry-run trades off |

**Save view…** stores the current range, legend visibility, and dry-run toggle
under a name in browser `localStorage` (`kraken.history.views`). **Set as
default** marks the selected view for the next visit. **Delete** removes
user-saved views only (built-ins stay locked).

### Summary cards & primary charts

![History — summary cards and value charts](images/history.png)

| Card | Meaning |
| :--- | :--- |
| **All-Time High** / **Period High** | Label switches with the selected range (“All” → ATH; finite ranges → period high). |
| **Total Trades** | Executions in the selected window. |
| **Total Volume Traded** | Sum of USD amounts for those trades. |
| **Total Fees Paid** | Fees attributed to trades in the window. |
| **Avg Fee Rate** | `SUM(fee) / SUM(usdAmount)` for successful, non-dry-run trades in the window. |
| **Avg Slippage** | Mean signed slippage % for successful, non-dry-run trades with slippage data. |

Charts on this view:

- **Portfolio Value Over Time** — Total portfolio (blue) plus per-asset USD
  values with fixed colors (BTC amber, ETH violet).
- **Asset Holdings Over Time** — Relative change in holdings (percent), same
  per-asset colors.

Point markers scale with density: full size at ≤24 points, half size through 48,
then line-only (markers hidden) while hover hit areas stay large enough for
tooltips.

### Zoom

Each chart has **Zoom − / Zoom + / Reset**. You can also wheel, pinch, or
drag-to-zoom on the **x-axis** only. Changing the time range rebuilds charts and
clears zoom. Reset restores the full selected window.

After you zoom in (via **Zoom +**, wheel, pinch, or drag), a horizontal **pan
scrubber** below the chart becomes enabled. Use it to slide the visible window
across the full selected time range. Dragging on the chart zooms; it does not
pan. **Reset** returns to the full window and disables the scrubber again.

### Allocation deviation & net cash flow

![History — allocation deviation and cumulative net cash flow](images/history-charts.png)

- **Allocation Deviation from Target** — Each series shows signed relative
  deviation from that asset’s target. **0%** means on target, positive values
  are overweight, and negative values are underweight. The Y-axis scales around
  the observed drift so small departures and post-rebalance corrections remain
  visible.
- **Cumulative Net Cash Flow** — Running net cash flow from trades over the
  window: sells add cash, buys subtract it (includes dry-run trades when that
  filter is enabled). A dashed **Net After Fees** series subtracts fees from the
  same signed cash-flow math (estimated fees when dry-run rows are included — not
  accounting P&L). Negative axis ticks use `-$…` formatting. An on-screen caption
  below the chart spells out the dry-run caveat, and the legend labels switch with
  the **Show Dry Run Trades** toggle — e.g. "Net Cash Flow (incl. dry run)" and
  "Net After Fees (est.)" when dry-run rows are shown, versus "Net Cash Flow
  (realized)" and "Net After Fees" when they're hidden.

### Trade log

![History — trade log](images/history-bottom.png)

| Column | Meaning |
| :--- | :--- |
| **Time** | When the trade was recorded. |
| **Pair** | Exchange pair (e.g. `BTC/USD`). |
| **Side** | **BUY** (green) or **SELL** (red). |
| **Volume** | Asset quantity. |
| **USD Amount** | Notional in USD. |
| **Price** | Executed price (USD per unit); shows an em dash (—) when zero/unknown. |
| **Fee** | Fee in USD; shows an em dash (—) when zero. Estimated for dry-run / local-estimate rows. |
| **Slippage** | Signed % vs expected quote at order time — favorable, adverse, or a neutral badge at exactly 0%; em dash when unknown. |
| **Status** | A quiet success **dot** for plain fills (hover shows a **SUCCESS** tooltip); **FAILED** (hover for the error message) and **DRY RUN** keep labelled badges. |

For dry-run and local-estimate rows, the Price, Fee, and Slippage cells carry an
"estimated at order time" tooltip so estimated economics aren't mistaken for
settled fills.

Toggle **Show Dry Run Trades** to include or hide rehearsal rows.

In **simulation** with dry run off, successful emulator fills show as the quiet
success **dot** (as in the screenshot above). With dry run on, expect **DRY RUN**
badges instead.

---

## Suggested workflows

### 1. Learn the UI offline

1. Enable **Simulation Mode** (dry run optional).
2. Start with an empty database so the emulator seeds ~15 days of history.
3. Explore Dashboard → History charts → Settings, then save a small allocation
   change and watch the next cycle appear in the **Recent Activity** feed.

### 2. Rehearse against live market data (no orders)

1. Provide Kraken API keys with appropriate permissions.
2. Leave **Simulation** off; enable **Dry Run**.
3. Confirm Dashboard prices and History sync look right before considering live
   mode.

### 3. Go live

1. Confirm allocations sum to 100% and dust / deviation settings match your
   risk tolerance.
2. Disable **Simulation** and **Dry Run** only when you intend to trade.
3. Watch **Recent Activity** on the first few cycles; keep History open to audit
   fills.

---

## Where to go next

| Need | Doc |
| :--- | :--- |
| Install / build / config keys | [README.md](../README.md) |
| Rebalancing math & order sequence | [ALGORITHM.md](ALGORITHM.md) |
| Reactive Flows / SSE architecture | [FLOWS.md](FLOWS.md) |
| Security / CORS / reporting | [SECURITY.md](../SECURITY.md) |

Screenshots in this guide are refreshed with the project’s documentation
screenshot workflow whenever the UI changes — see the agent skill
`docs-screenshot-refresh` if you maintain this repository.
