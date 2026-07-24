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

![Settings — Safety modes and targets](images/settings.png)

---

## Navigation

The app has three primary pages:

| Page | Route | Purpose |
| :--- | :--- | :--- |
| **Dashboard** | `/` | Live portfolio snapshot, allocation bars, performance table, recent activity |
| **History** | `/history` | Time-range charts, summary cards, full trade log |
| **Settings** | `/settings` | Loop timing, triggers, fiat deployment, dry run / simulation, allocations |

The dashboard pushes live updates over Server-Sent Events (`/api/status/stream`).
You should see a green **LIVE** badge and a recent **Data Age** when the loop is
healthy.

---

## Dashboard

![Dashboard overview](images/dashboard.png)

### Summary cards

| Card | Meaning |
| :--- | :--- |
| **Total Portfolio** | Mark-to-market value of all tracked assets. **Drawdown** is how far you are below the recorded all-time high. |
| **Cash (USD)** | Fiat balance vs its **effective** target (after drawdown-based fiat deployment). Shows current %, target %, and deviation. |
| **Crypto Assets** | Combined crypto value, share of the portfolio, and how many crypto symbols you hold. |

### Allocation & performance

![Dashboard allocation, table, and activity](images/dashboard-bottom.png)

- **Portfolio Allocation** — Horizontal bars for the largest holdings by USD
  value (and cash).
- **Asset Performance** — Sortable table of price, value, target %, current %,
  and **Dev %** (how far each asset is from target, with dollar impact).
  Color cues highlight overweight vs underweight positions.
- **Recent Activity** — Chronological log for the latest cycle:
  - Blue **INFO** — deviation checks and decisions
  - Red **SELL** / green **BUY** — orders (or dry-run intents)
  - Grey status lines when a cycle finishes with no trade

Use this page as your “is the bot healthy right now?” view.

---

## Settings

Open **Settings** (gear) from the dashboard, or go to `/settings`.

![Settings page](images/settings.png)

### Global parameters

| Field | Purpose |
| :--- | :--- |
| **Loop Interval (Seconds)** | How often the rebalancer wakes up to snapshot and potentially trade. |
| **Deviation Trigger (%)** | Minimum absolute deviation from target before an asset can trigger trades. |
| **Dust Threshold ($)** | Skip orders smaller than this USD amount (avoids exchange min-size noise). |
| **Fiat Max Drawdown (%)** | Drawdown at which cash is fully eligible for deployment into crypto. |
| **Fiat Deployment Exponent** | Shape of the cash→crypto deployment curve as drawdown grows (1.0 ≈ linear). |
| **Dry Run Mode (Safe)** | Log intents without placing orders. |
| **Simulation Mode (Kraken Emulator)** | Use the offline emulator instead of the live Kraken API. |

Click **Save Configuration** to apply. The running loop hot-reloads — no process
restart required.

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
all four summary cards and the charts update together.

### Summary cards & primary charts

![History — summary cards and value charts](images/history.png)

| Card | Meaning |
| :--- | :--- |
| **All-Time High** / **Period High** | Label switches with the selected range (“All” → ATH; finite ranges → period high). |
| **Total Trades** | Executions in the selected window. |
| **Total Volume Traded** | Sum of USD amounts for those trades. |
| **Total Fees Paid** | Fees attributed to trades in the window. |

Charts on this view:

- **Portfolio Value Over Time** — Total portfolio plus per-asset USD values.
- **Asset Holdings Over Time** — Relative change in holdings (percent).

### Allocation drift & P&L

![History — allocation drift and cumulative P&L](images/history-charts.png)

- **Allocation Drift Over Time** — How each asset’s share of the book moved
  versus time (useful for seeing whether rebalances held the targets).
- **Cumulative Realized P&L** — Net cash-flow style P&L over the window
  (includes dry-run trades when that filter is enabled).

### Trade log

![History — trade log](images/history-bottom.png)

| Column | Meaning |
| :--- | :--- |
| **Time** | When the trade was recorded. |
| **Pair** | Exchange pair (e.g. `BTC/USD`). |
| **Side** | **BUY** (green) or **SELL** (red). |
| **Volume** | Asset quantity. |
| **USD Amount** | Notional in USD. |
| **Status** | **SUCCESS**, **FAILED**, or **DRY RUN**. |

Toggle **Show Dry Run Trades** to include or hide rehearsal rows.

In **simulation** with dry run off, successful emulator fills show as
**SUCCESS** (as in the screenshot above). With dry run on, expect **DRY RUN**
badges instead.

---

## Suggested workflows

### 1. Learn the UI offline

1. Enable **Simulation Mode** (dry run optional).
2. Start with an empty database so the emulator seeds ~15 days of history.
3. Explore Dashboard → History charts → Settings, then save a small allocation
   change and watch the next cycle’s activity log.

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
