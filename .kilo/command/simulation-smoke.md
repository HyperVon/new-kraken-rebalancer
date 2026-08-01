---
description: "Boot an isolated offline simulation and verify its health endpoint"
---

# Simulation Smoke

Run a safe, isolated simulation smoke check and report whether the application starts.

- Read the `dry-run-and-simulation` and `docs-screenshot-refresh` skills first.
- Do not read or copy the existing `rebalancer-config.json`, `.env` files, database files, logs, home-directory files, or any external runtime data.
- Use the checked-in `.kilo/run-script` as the only launch implementation. Start `./.kilo/run-script` from the repository root through a tracked background process, waiting for its generic `Agent Manager simulation ready.` message; do not reproduce its shell, JSON, port, or process-management logic inline.
- The run hook builds the fat JAR, creates a disposable temporary directory, copies only the committed `rebalancer-config-template.json`, forces and verifies both `simulation=true` and `dryRun=true`, and starts the JAR with the required JVM flags. `simulation=true` must route all exchange calls to the offline emulator.
- The run hook selects an available local port and performs the bounded `/api/health` check itself. Do not add a fixed port or request private portfolio, trade, or configuration endpoints.
- Stop the tracked run-hook process after the health check, allowing its cleanup trap to remove only its temporary directory. Never delete repository files or leave a server running.
- Report only build status, startup status, health-check status, and a redacted failure summary. Do not include raw logs, credentials, account data, personal paths, or database contents.

If the application cannot be started safely without touching a real local config or database, stop and report that limitation instead of proceeding.
