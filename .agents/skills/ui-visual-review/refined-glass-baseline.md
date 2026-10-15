# Refined Glass baseline (current UI)

Review these shipped patterns for execution quality. Treat a missing or broken
baseline item as a finding; do not re-propose retired retrospective surfaces.

| Area | What ships today |
| :--- | :--- |
| Global header | `brandWithMode()` — brand mark + persistent **mode plate** (`SIMULATION` / `DRY RUN` / `LIVE TRADING`, with tooltip) on Dashboard, Settings, and History |
| Stream health | Dashboard-only chip reading **STREAM** / **STALE** with relative age + timestamp (`header-status`) — SSE freshness, never trading mode |
| Dashboard | Latest completed current-cycle snapshot, bounded same-source hero trend, configured allocation values, Cash / Crypto summary tiles, current-cycle actions |
| Settings safety | Simulation / Dry Run as toggle cards — icon, consequence prose, `ON`/`OFF` state pill, section subtitle |
| History evidence | Direct cycle-value observations, account-sync state, and separately displayed exchange trade, ledger, and balance-checkpoint evidence |

The Dashboard hero may show a bounded in-memory trend from the current portfolio
source. History values are direct recorded observations; missing observations
remain gaps and exchange events do not reconstruct a historical portfolio
value. Simulation values are process-scoped and do not establish account trade
or ledger evidence.
