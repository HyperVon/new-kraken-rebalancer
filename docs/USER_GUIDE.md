# User Guide — Kraken Rebalancer

This guide walks through the Dashboard, account History, and Settings. It
explains how to read the latest rebalance cycle, review portfolio values and
exchange activity, and configure the bot.

For install and first launch, see the [README Getting Started](../README.md#getting-started)
section. For rebalancing math internals, see [ALGORITHM.md](ALGORITHM.md).

---

## What problem does it solve?

Market moves, deposits, and withdrawals cause a portfolio to drift away from its
target allocation. Correcting that drift by hand requires repeated monitoring,
calculations, and order entry. Kraken Rebalancer automates those rules so a
Kraken user can follow a chosen allocation consistently, including a
percentage-based USD cash target that grows or shrinks with the portfolio, and
review what the strategy did from one local dashboard.

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
| **Simulation Mode** | Routes exchange calls to an offline Kraken emulator (random-walk prices). No API keys required. | Learning and demonstrating the UI |
| **Dry Run Mode** | Calculates intended orders on the active backend (live Kraken or the emulator) but never places them. Server logs may show `[DRY RUN]` or `[EMULATOR DRY RUN]`; Recent Activity always prefixes `[DRY RUN]`. | Rehearsing strategies against live or simulated markets |

**Live trading** is `simulation` off **and** `dryRun` off with real API keys —
that moves real funds. Prefer simulation (and/or dry run) until you understand
the screens below.

On **Settings**, these appear as two toggle cards — **Simulation Mode** first,
then **Dry Run Mode** — each with an **ON** / **OFF** state pill and a short line
of consequence prose (e.g. "No real funds are ever touched"). Keep at least one
safety on unless you intend to trade live.

---

## Navigation

Dashboard, History, and Settings share the same top nav tabs (active page is
highlighted and centered below the header identity on wider screens). The header
brand reads **Kraken** + **Rebalancer**. A **persistent mode plate** shows the
current trading mode on **every** page: **SIMULATION** (blue), **DRY RUN** (amber), or
**LIVE TRADING** (red).
Precedence is simulation first (even if dry run is also on), then dry run, then
live. The plate reflects your Settings, and hovering it reveals a tooltip
explaining the consequence (e.g. "Live trading — real orders execute with real
funds").
Dashboard updates also refresh the plate, including after Settings are changed
in another tab.

The header also includes a loop control on every page. Its state pill shows
**RUNNING** or **PAUSED**; choose the labeled **Pause** action to stop new rebalance
cycles or **Resume** to start them again. This is separate from the
trading-mode plate: pausing the loop does not change Simulation, Dry Run, or
Live Trading settings.

| Page | Route | Purpose |
| :--- | :--- | :--- |
| **Dashboard** | `/` | Latest completed cycle snapshot, allocation bars, performance table, current-cycle actions |
| **History** | `/history` | Historical account values, Kraken trades and transactions, and durable data progress |
| **Settings** | `/settings` | Loop timing, triggers, USD percentage target, safety modes, allocations |

The History page synchronizes Kraken trade and ledger records for the verified
account, then derives daily balances and values from those committed records.
It shows a complete account total only when every included nonzero balance has
valid same-day price evidence; missing dates and assets remain explicit gaps.
The chart reports account-value changes, not profit or investment returns.
Simulation keeps its process-scoped samples separate from authenticated account
history. Operator recovery for unresolved live orders remains available through
the separate safety workflow.

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

<a href="images/dashboard.png"><img src="images/dashboard.png" alt="Dashboard showing the latest current-cycle snapshot" width="960"></a>
*Dashboard overview — the latest completed cycle and its current allocation.*

### Portfolio overview

The Dashboard displays the latest completed rebalance cycle: a hero portfolio
value with a short trailing chart plus two tiles:

| Element | Meaning |
| :--- | :--- |
| **Total Portfolio** (hero) | Mark-to-market value of the tracked assets in the latest completed cycle. The adjacent chart shows up to 24 recent completed cycles captured during this application run. |
| **Cash (USD)** (tile) | Fiat balance with a progress bar for its current share, the configured USD target, and deviation. |
| **Crypto Assets** (tile) | Combined crypto value with a progress bar for its share, its target %, and how many crypto symbols you hold. |

The trend uses only in-memory snapshots from the current application run. It
appears after the second completed cycle, shows the cycle count and sample
times, and resets when the application restarts. It does not read older
database snapshots, which may predate the current account or include simulation
data.

### Loop control

The header's loop control is the quickest way to stop trading activity without
changing the saved configuration. When the state reads **RUNNING**, click
**Pause**; the active loop is cancelled safely and the state changes to
**PAUSED**. Click **Resume** when you want the configured cycle schedule to
start again. The control does not alter credentials, allocations, Simulation,
Dry Run, or Live Trading mode.

### Allocation & performance

<a href="images/dashboard-performance.png"><img src="images/dashboard-performance.png" alt="Current asset performance and target deviation legend" width="960"></a>
*Dashboard — current asset performance and over/under-target legend.*

- **Portfolio Allocation (Top Assets)** — Horizontal bars for the largest
  holdings by USD value (and cash), showing up to the **top 15**. Bar lengths are
  relative to the largest holding (the biggest fills the track), with each bar
  labelled by its USD value and current %. Each symbol uses its configured color
  from Settings (BTC/ETH/USD start with amber/violet/slate defaults; other
  assets get an auto-assigned color).
- **Asset Performance** — Sortable table of price, value, target %, current %,
  and **Dev %** (how far each asset is from target, with dollar impact), plus
  sortable **Quantity** and **Quality score** columns on larger screens. Asset
  symbols open History filtered to that asset. Quality metrics are grouped
  above the allocation bars.
  Amber = over target, blue = under target (not profit/loss green/red); a small
  legend sits above the table.
- **Recent Activity** — the newest 20 cycle outcomes with a short summary and
  timestamps. Use **Show older** to page through the in-memory history, which is
  capped at 120 cycles. Failed cycles without a portfolio snapshot remain
  visible:
  - Red **SELL** / green **BUY** — orders with USD to 2 decimals

<a href="images/dashboard-bottom.png"><img src="images/dashboard-bottom.png" alt="Recent cycle activity and outcomes" width="960"></a>
*Dashboard — recent cycle activity and outcomes.*

Use this page as your “is the bot healthy right now?” view.

### Operational status and readiness

The JSON endpoints are intentionally unauthenticated for this application's
single-operator/private-LAN deployment model. Treat every device that can reach
the server as trusted. `GET /api/health` is a liveness and diagnostic check; it
includes the active mode, loop state, last cycle timestamps/error, and unresolved
live-order counts. `GET /api/readiness` returns `200` only when the loop is
running, configuration is available, a current-cycle snapshot exists, the
latest cycle is not failed, and no ambiguous live-order intent is waiting for
review. It returns `503` with a `readinessReason` otherwise.

If an ambiguous live order occurs, the application halts live trading and displays an
**Action Required: Unresolved Live Order Intent** banner at the top of the Dashboard.
You can inspect the intent details (ID, symbol, side, volume, USD amount, error message)
and resolve it directly from the UI by selecting **CONFIRMED** or **REJECTED**,
entering optional Kraken order transaction IDs, and providing resolution evidence.
Alternatively, inspect `GET /api/order-intents` via REST. Only an `UNCERTAIN` intent is
eligible for resolution (`PENDING` indicates an AddOrder in flight). Follow
the [operator recovery runbook](../SECURITY.md#operator-recovery-runbook) for
the exchange-verification checklist, complete resolution options, and post-request
checks before resuming.

### Responsive layouts

The dashboard is checked at the same common viewport profiles used by the visual
review workflow. On phones, Asset Performance keeps Asset, Value, and Dev % in
view.

---

## Settings

Open **Settings** from the shared top nav, or go to `/settings`.

<a href="images/settings.png"><img src="images/settings.png" alt="Settings with active controls and safety modes" width="960"></a>
*Settings — active controls, allocations, and safety mode cards.*

### Global parameters

| Field | Purpose |
| :--- | :--- |
| **Loop Interval (Seconds)** | How often the rebalancer wakes up to snapshot and potentially trade. Minimum **1**. |
| **Deviation Trigger (%)** | Minimum absolute deviation from target before an asset can trigger trades. Minimum **0**. |
| **Minimum Order Size ($)** | Dual role: absolute USD deviation must meet this for an asset to trigger, and orders below this notional are skipped at execution. **Minimum `2` (enforced).** |

Older configuration files may contain retired history-only values such as
drawdown, deployment, inception, or comparison-start settings. These values are
discarded when the file is read and do not affect the active strategy, which
uses configured allocations directly. The next Settings save removes those
retired keys from the JSON file.

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

After a successful save, the page stays on Settings and reports whether the new
values are active or pending the current execution cycle. Validation failures
preserve the entered values so you can correct the form without re-entering it.

### Target allocations

<a href="images/settings-allocations.png"><img src="images/settings-allocations.png" alt="Target allocations and quality-score preview controls" width="960"></a>
*Settings — target allocations and optional quality-score preview.*

Each allocation row carries a symbol, a color swatch, an optional **Quality
score**, a **Target** percent, and **Remove**. On wider screens, rows sit in
auto-fitting columns sized for all controls. On phone screens, the symbol and
**Remove** action share the first row, with the swatch, score, and target below;
the two numeric fields stay labelled so a score is never mistaken for a target.
The score-preview and Add Asset controls wrap below their fields when space is
limited.

Before a rebalance cycle analyzes a configured non-USD asset, Kraken metadata
must explicitly classify it as `currency`. Missing or unknown metadata and
non-currency classes, including tokenized assets, stop the cycle before price
analysis. The application does not infer asset class from ticker names. This
live eligibility check is separate from historical ledger reporting, which
excludes share balances only when the imported ledger explicitly identifies
them as equities.

- Target percent is bounded to **0–100%** by the percent input itself.
- **Quality score** is optional and runs **0–10**. It is a preference input, not
  a target: it never changes what you hold until you press **Calculate from
  scores** and then save.
- Colors persist in `rebalancer-config.json`. The Settings form accepts a blank
  swatch for automatic assignment but rejects malformed nonblank colors.
  Config-file load/save normalization still assigns known defaults for
  BTC/ETH/USD and HSL-derived colors for other missing or invalid values.
- **Total** must read **100.00%** (green badge) before save succeeds.
- **USD is required** — cash is part of the strategy, not optional.
- **Add Asset** / **Remove** change the universe without restarting the app.

#### Calculating targets from quality scores

**What it is.** An optional helper that suggests target percentages from the
quality scores you assign, so you are not hand-tuning numbers you would rather
express as a preference. It only ever produces a *preview*: it rewrites the
target fields in the form and nothing reaches your configuration until you press
**Save Configuration**.

**What it does not do.** It never changes what you hold on its own, and it never
moves money. Until you save, the preview is a calculation you can discard by
pressing **Calculate from scores** with different inputs, or by reloading the
page.

**How to use it.**

1. Give one or more assets a **Quality score** between **0** and **10** in their
   row. Leave a row blank or enter **0** to clear its score; assets without a
   positive score are excluded from the scored sleeve. At least one score is required — with
   none, the form reports *"Enter a quality score for at least one asset before
   calculating"*.
2. Set **Emphasis** to control how hard a higher score wins. The form
   pre-selects **4**.
3. Optionally set **Scored sleeve**, the amount of the book to redistribute.
   Leave it on **Auto** to use the combined current target of the scored assets.
   If those targets sum to zero, the preview reports an error instead of taking
   the whole book as a fallback.
4. Press **Calculate from scores** and read the result.

| Control | Purpose |
| :--- | :--- |
| **Emphasis (1–8)** | Steepness of the split. Each asset's weight is its score raised to this power, so the ratio between two assets grows quickly with the setting. Leave blank for the flattest weighting (1). |
| **Scored sleeve (%)** | How much of the book the scored assets share. **Auto** = the combined current target of the scored assets, which normally leaves unscored assets untouched. |
| **Calculate from scores** | Runs the preview. Nothing is saved until you save the form. |

**How the split works.** Scored assets are reweighted *between themselves*, in
proportion to `score^emphasis`, and that share of the book is then divided among
them. Assets without a score keep the target you already set, which is why the
total still has to come to **100.00%**. The preview is returned only when the
scored sleeve plus the preserved unscored targets stays within the same
allocation tolerance used when saving the configuration.

Worked example — the default targets BTC **50%**, ETH **45%**, USD **5%**, with
BTC scored **9.5** and ETH scored **6.0**. The scored sleeve is 95%, and USD is
untouched either way:

| Emphasis | BTC | ETH | USD (untouched) |
| ---: | ---: | ---: | ---: |
| **1** (flattest) | 58.23% | 36.77% | 5% |
| **4** (pre-selected) | 81.96% | 13.04% | 5% |

**Reading the three figures.** They answer different questions, and each says
which one:

| Figure | What it answers | Scope |
| :--- | :--- | :--- |
| **Weighted quality score** | How strong the scored assets look overall. | **Scored assets only** — an unscored asset has no score to average in. |
| **Largest single position** | Am I concentrated? | The **whole book**, including the targets preserved for unscored assets. |
| **Effective independent bets** | How many genuinely separate positions do I hold? | The **whole book**. `1 / Σ(share²)`: equals the leg count when evenly weighted, and **1** when everything sits in one asset. Two assets at 86 / 14 behave like **1.3** bets, not 2. |

The configured USD percentage target, sell-then-buy sequence, and dust handling are documented in
[ALGORITHM.md](ALGORITHM.md).

---

## History

<a href="images/history.png"><img src="images/history.png" alt="Account value history and daily holdings" width="960"></a>
*History — daily account values derived from imported exchange records and completed historical closing prices.*

History restores account history from imported Kraken records. It keeps four views: **Account value**, **Trades**, **Transactions**, and **Data status**. The Account value range defaults to 30 days and offers 24h, 7d, 30d, 90d, 1y, All, and custom dates. Ranges from 7d onward use daily UTC closing-price values. The 24h range uses full-account balance and direct USD price samples captured during account cycles; selecting a chart point opens holdings from that exact sample. If no full-account samples are available in the last 24 hours, History shows an empty state instead of substituting a daily mark. In Simulation, process-scoped cycle samples stay separate and authenticated account history is unavailable.

The **Account value (USD)** chart includes spot balances, USD cash, and identifiable staking/Earn balance buckets represented in ledger history, including assets that are no longer configured or held. All ledger assets classified as equities are excluded from holdings and totals; all USD ledger balance changes remain included, including stock trade settlements and dividend credits. Separate derivatives and margin positions are outside this chart. The chart uses completed Kraken daily candles and labels the results as historical closing-price estimates. Missing balances or prices create visible gaps; a known subtotal is never shown as a total. Daily historical value is not an exchange-certified statement, tax valuation, profit, investment return, or strategy performance.

The chart reports the latest available value, the account-value change over the selected range, and the actual baseline date used. A change requires at least two complete daily values; a one-day range still reports its available value and extrema. Deposits and withdrawals affect the change. A missing or zero baseline does not produce a percentage. Select a daily point, or choose a date under **Holdings at [date]**, to inspect that day's asset, quantity, historical USD price, value, and availability. The daily-total and selected-date holdings CSV exports preserve missing-data status.

Drag to zoom, use **Reset zoom** to restore the selected period, and use the chart tooltips for exact daily values. The chart leaves a break at every incomplete date. Older ranges use daily marks and do not imply that all transaction history has a complete price series. **All** shows available account-value history; **Data status** reports the requested period and its actual value coverage.
When you change the holdings date, the previous rows clear while the new date
loads; only the response for the still-selected date and account scope is shown.

**Data status** separates the four kinds of work:

1. **Import exchange records** reports imported trade and transaction counts, covered dates, the active scan window, and saved progress. Kraken does not provide a stable total, so the page does not invent a completion percentage.
2. **Establish daily balances** reports evaluated days, valid days, and dates that lack balance evidence.
3. **Obtain prices and calculate values** reports required asset-days priced, complete account totals, and blocked dates.
4. **Routine refresh** is shown separately from the frozen historical import job. A recent check does not restart or erase the historical-import status.

The page shows the requested value period, available-value dates, last successful update, elapsed time, and last committed progress. A collapsed **Why is this incomplete?** section lists specific unavailable evidence and dates. **Retry failed work** resumes durable checkpoints; it does not delete or reimport intact records. Technical details contain cursors, source identifiers, coverage intervals, and sanitized errors. A cursor is not evidence that the account history is complete.

Trades and transactions retain their independent filters, pagination, and CSV exports. Exchange trades and ledger rows are account history; bot-cycle values remain separate and do not feed account-value history. Account identity is verified before synchronization, and the local account reference is stored as a digest. The sync and valuation workers do not participate in the portfolio trading calculation.

History and live-order state use separate SQLite files under the configured data directory. Initialize the data directory before first launch; details and backup requirements are in the [README storage notes](../README.md#configuration-reference) and [SECURITY.md](../SECURITY.md#execution-journal-storage-and-recovery).

---

## Suggested workflows

### 1. Learn the UI offline

1. Enable **Simulation Mode** (dry run optional).
2. Explore Dashboard and Settings, then save a small allocation change and
   watch the next completed cycle appear in **Recent Activity** and History.
3. The simulated History value series is separate from authenticated account
   trades, ledgers, and values.

### 2. Rehearse against live market data (no orders)

1. Provide an API key with **Query Funds** so the Dashboard can read account
   balances. Do not grant order permissions while rehearsing in Dry Run.
2. Leave **Simulation** off; enable **Dry Run**.
3. Review the latest Dashboard cycle and current allocation before considering
   live mode.

### 3. Go live

1. Confirm allocations sum to 100% and dust / deviation settings match your
   risk tolerance.
2. Disable **Simulation** and **Dry Run** only when you intend to trade.
3. Watch **Recent Activity** and the current-cycle snapshot on the first few
   cycles.
4. If logs report an uncertain live submission, stop changing modes or
   retrying manually. Verify Kraken open orders, closed orders, and fills before
   resolving the durable intent; the bot blocks further live orders to avoid a
   duplicate submission. Use the
   [operator recovery runbook](../SECURITY.md#operator-recovery-runbook), which
   includes the exact CSRF-protected request and verification steps.

---

## Where to go next

| Need | Doc |
| :--- | :--- |
| Install / build / config keys | [README.md](../README.md) |
| Rebalancing math & order sequence | [ALGORITHM.md](ALGORITHM.md) |
| Reactive Flows / SSE architecture | [FLOWS.md](FLOWS.md) |
| Security / CORS / live-order safety | [SECURITY.md](../SECURITY.md) |
