# Refined Glass baseline (current UI)

Review these shipped patterns for execution quality. Treat a missing or broken
baseline item as a finding; do not re-propose retired retrospective surfaces.

| Area | What ships today |
| :--- | :--- |
| Global header | `brandWithMode()` — brand mark + persistent **mode plate** (`SIMULATION` / `DRY RUN` / `LIVE TRADING`, with tooltip) on Dashboard, Settings, and Actual |
| Stream health | Dashboard-only chip reading **STREAM** / **STALE** with relative age + timestamp (`header-status`) — SSE freshness, never trading mode |
| Dashboard | Latest completed current-cycle snapshot, configured allocation values, Cash / Crypto summary tiles, current-cycle actions |
| Settings safety | Simulation / Dry Run as toggle cards — icon, consequence prose, `ON`/`OFF` state pill, section subtitle |
| Actual evidence | Forward-only raw observations, incomplete evidence and coverage gaps, configured asset evidence, optional continuity-verified prospective Buy & Hold |

Do not expect retrospective portfolio trends, report timelines, or historical
comparison controls. Buy & Hold is prospective from an eligible verified
baseline.
