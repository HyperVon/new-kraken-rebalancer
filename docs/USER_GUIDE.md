# User Guide — Kraken Rebalancer

This guide walks through the live dashboard: what each screen is for, how to
configure the bot, and how to interpret charts and trade logs. Screenshots below
were captured in **simulation mode** so values are illustrative, not live
exchange balances.

For install and first launch, see the [README Getting Started](../README.md#getting-started)
section. For rebalancing math internals, see [ALGORITHM.md](ALGORITHM.md).

---

## What problem does it solve?

Market moves, deposits, and withdrawals cause a portfolio to drift away from its
target allocation. Correcting that drift by hand requires repeated monitoring,
calculations, and order entry. Kraken Rebalancer automates those rules so a
Kraken user can follow a chosen allocation consistently, optionally configure a
cash reserve to deploy progressively during drawdowns, and review what the
strategy did from one local dashboard.

This is an execution and monitoring tool, not a source of trading signals. You
choose the assets, targets, and risk settings; the application does not predict
prices or guarantee returns. Rebalancing may create fees and tax consequences.
The simulator and dry-run mode let you decide whether the workflow fits your
strategy before any real order is placed.

For a fuller explanation of the use cases and intended audience, see
[Why Use Kraken Rebalancer?](../README.md#why-use-kraken-rebalancer).

---

## Safety modes (read this first)

Two independent settings control how “real” trading is. Change them from
**Settings** or in `rebalancer-config.json`.

| Mode | What it does | When to use |
| :--- | :--- | :--- |
| **Simulation Mode** | Routes all exchange calls to an offline Kraken emulator (random-walk prices, seeded history). No API keys required. | Learning the UI, demos, documentation screenshots |
| **Dry Run Mode** | Calculates intended orders on the active backend (live Kraken or the emulator) but never places them. Server logs may show `[DRY RUN]` or `[EMULATOR DRY RUN]`; Recent Activity always prefixes `[DRY RUN]`. | Rehearsing strategies against live or simulated markets |

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
**Only the Dashboard** shows a stream-health chip in the header next to the mode
plate: **STREAM** (green) when data is flowing, or **STALE** when the last
snapshot is older than **90 seconds** (governed by
`PrecisionConstants.STALE_THRESHOLD_SECONDS`). Automatic SSE reconnection
resumes feed status without requiring a page reload. Beside it are the relative
age of the last update (e.g. `12s`) and its clock time.

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
  labelled by its USD value and current %. Each symbol uses its configured color
  from Settings (BTC/ETH/USD start with amber/violet/slate defaults; other
  assets get an auto-assigned color), shared with History charts.
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
| **Loop Interval (Seconds)** | How often the rebalancer wakes up to snapshot and potentially trade. Minimum **1**. |
| **Deviation Trigger (%)** | Minimum absolute deviation from target before an asset can trigger trades. Minimum **0**. |
| **Minimum Order Size ($)** | Dual role: absolute USD deviation must meet this for an asset to trigger, and orders below this notional are skipped at execution. **Minimum `2` (enforced).** |
| **Fiat Max Drawdown (%)** | Drawdown at which cash is fully eligible for deployment into crypto. Bounded **0–100**. |
| **Fiat Deployment Exponent** | Shape of the cash→crypto deployment curve as drawdown grows (1.0 ≈ linear). Minimum **0.1** (must be positive). |

### Safety modes

Simulation and Dry Run live in their own **Safety Modes** block (not mixed into
the numeric parameter grid), as two toggle cards each with an **ON** / **OFF**
state pill:

| Card | Purpose |
| :--- | :--- |
| **Simulation Mode** | Runs the whole strategy against an offline Kraken emulator — no real funds are ever touched. |
| **Dry Run Mode** | Calculates intended orders on the active backend (live Kraken or the emulator) but never places them. |

**Simulation Mode** is listed first. **Save Configuration** lives in the page
header next to the nav (not at the bottom of the form). Saving hot-reloads the
running loop — no process restart required. If a rebalance is actively
executing, the file is saved immediately but the new runtime settings take
effect only after that cycle finishes.

Saving fails closed when any numeric field is missing, malformed, or non-finite,
or when allocation rows are incomplete. The current configuration remains
active and the Settings form shows the validation error.

### Target allocations

- Every allocation row is a symbol + target percent + optional color swatch,
  bounded to **0–100%** by the percent input itself.
- Colors persist in `rebalancer-config.json`. The Settings form accepts a blank
  swatch for automatic assignment but rejects malformed nonblank colors.
  Config-file load/save normalization still assigns known defaults for
  BTC/ETH/USD and HSL-derived colors for other missing or invalid values.
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

### Sync progress banner

Immediately below the header, a banner appears while trade and ledger history is
being synchronized: a spinner with **Synchronizing Kraken Trade History…** and an
inline progress indicator (e.g. **0 / 0 (0%)**) plus a progress bar. It auto-
dismisses once the sync session completes.

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

### Summary cards

| Card | Meaning |
| :--- | :--- |
| **All-Time High** / **Period High** | Label switches with the selected range (“All” → ATH; finite ranges → period high). |
| **Total Trades** | Successful, non-dry-run executions in the selected window. |
| **Total Volume Traded** | Sum of USD amounts for those trades. |
| **Total Fees Paid** | Fees attributed to those trades. |
| **Avg Fee Rate** | `(SUM(fee) / SUM(usdAmount)) × 100` (%) for successful, non-dry-run trades in the window. |
| **Avg Slippage** | Mean signed slippage % for successful, non-dry-run trades with slippage data. |

### Zoom

Each chart has **Zoom − / Zoom + / Reset**. You can also wheel, pinch, or
drag-to-zoom on the **x-axis** only. Changing the time range rebuilds charts and
clears zoom. Reset restores the full selected window.

After you zoom in (via **Zoom +**, wheel, pinch, or drag), a horizontal **pan
scrubber** below the chart becomes enabled. Use it to slide the visible window
across the full selected time range. Dragging on the chart zooms; it does not
pan. **Reset** returns to the full window and disables the scrubber again.

### Rebalancer vs Buy & Hold

![History — rebalancer vs buy & hold comparison](images/history.png)

The first chart below the summary cards compares what the rebalancer actually
achieved against a **hypothetical buy-and-hold** strategy:

- **Buy & Hold** starts from the first snapshot in the selected window and
  holds those asset quantities constant. Each subsequent point values that
  fixed basket at that day's prices.
- **Rebalancer** is the actual portfolio value at each snapshot.
- The **delta badge** next to the chart title shows the cumulative
  outperformance or underperformance (e.g. `+$5,000.00 (+4.76%)`).

A caption below the chart reads: *Based on stored snapshots and recorded trades.
Starting quantities are frozen at the first snapshot in the selected range.*

The comparison cannot be computed when:

| Reason | Meaning |
| :--- | :--- |
| Insufficient snapshots | Fewer than 2 snapshots in range. |
| Non-positive baseline | First snapshot total value is $0 (no baseline to scale from). |
| Baseline mismatch | First snapshot's total value doesn't match the sum of its priced assets (stale data). |
| Missing price | An asset lacks a price in a snapshot. |
| Asset universe changed | An asset was added or removed during the window. |
| Unsupported trade | A trade with a side other than BUY or SELL. |
| Unexplained balance change | A deposit, withdrawal, transfer, or incomplete trade history may exist. |

When an unavailability reason applies, the chart hides and a message explains why.
Where the comparison *is* rendered but the tracked balance changes could not be
fully reconciled (for example, external deposits or withdrawals), the chart
still renders with an **Estimated (external balance changes may affect
precision)** badge — treat those ranges as approximate. Fully reconciled ranges
show no badge.

### Staking Rewards

A dedicated chart below the comparison shows the cumulative USD value of
`staking` ledger entries in the selected range, with one series per asset and a
total shown beside the title. Values are aligned to portfolio snapshots and use
each snapshot's asset price; the chart is empty until ledger data has been
synchronized. A caption below the chart reads: *Cumulative staking reward value
accrued during the selected range. Assets without a snapshot price in the range
are excluded.*

### Portfolio Value & Asset Holdings

![History — portfolio value and asset holdings](images/history-portfolio-charts.png)

- **Portfolio Value Over Time** — Total portfolio (blue) plus per-asset USD
  values using the same configured per-asset colors as Settings / Dashboard.
- **Asset Holdings Over Time** — Relative change in holdings (percent), same
  per-asset colors.

Point markers scale with density: full size at ≤24 points, half size through 48,
then line-only (markers hidden) while hover hit areas stay large enough for
tooltips.

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
   Snapshots and trades older than 90 days are pruned automatically.
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
4. If logs report an uncertain live submission, stop changing modes or
   retrying manually. Verify Kraken open orders, closed orders, and fills before
   resolving the durable pending intent; the bot blocks further live orders to
   avoid a duplicate submission.

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
