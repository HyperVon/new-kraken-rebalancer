# Refined Glass baseline (current UI)

The UI ships the **Refined Glass** redesign (CHANGELOG `6.13.0`). Review these
for execution quality — do **not** re-propose them as new ideas, and treat a
missing or broken one as **P0/P1**.

| Area | What ships today |
| :--- | :--- |
| Global header | `brandWithMode()` — brand mark + persistent **mode plate** (`SIMULATION` / `DRY RUN` / `LIVE TRADING`, with tooltip) on Dashboard, Settings, **and** History |
| Stream health | Dashboard-only one-line chip reading **STREAM** / **STALE** with relative age + timestamp (`header-status`) — SSE health, never trading mode |
| Dashboard hero | Large total value, **24H** delta chip (up/down), inline sparkline, plus Cash / Crypto tiles with progress bars, target, and deviation |
| Activity | Cycle-grouped feed (≤6 cycles) — relative time, per-cycle action count, `No trades — portfolio within tolerance` for quiet cycles, **View all history** link |
| Settings safety | Simulation / Dry Run as toggle **cards** — icon, consequence prose, `ON`/`OFF` state pill, section subtitle |
| History charts | One header row per chart (title + zoom) above the legend, taller canvas, muted caption under cumulative net cash flow |
| Trade table | Tabular USD figures, **em dash** for zero price/fee, quiet status **dot** for plain success (badges only for dry-run / failed) |

If you genuinely think one of these is wrong, file it as an explicit `redesign`
finding with migration scope — do not quietly recommend reverting to pre-6.13
status cards, the table-based activity log, or a `LIVE` stream chip.
