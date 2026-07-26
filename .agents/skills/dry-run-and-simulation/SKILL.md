---
name: dry-run-and-simulation
description: >-
  Documents that Settings.dryRun and Settings.simulation are DISTINCT flags —
  DynamicKrakenService routing, SimulatedKrakenService seeding, and live-trading
  caution. Use when changing trading modes, examples, tests, or Kraken service
  selection.
---

# Dry Run vs Simulation

These flags are **independent**. Do not treat them as synonyms.

## How this differs from nearby skills

| Skill | Role |
| :--- | :--- |
| **dry-run-and-simulation** (this) | Flag semantics + DynamicKrakenService routing + live caution |
| [koin-di-and-config](../koin-di-and-config/SKILL.md) | Persisting `Settings` / `rebalancer-config.json` |
| [kraken-api-integration](../kraken-api-integration/SKILL.md) | Live REST signing / rate limits (not mode routing) |
| [portfolio-rebalancing-math](../portfolio-rebalancing-math/SKILL.md) | How `dryRun` affects order placement / settle skips |
| [ktor-html-views](../ktor-html-views/SKILL.md) | Mode plate UI reflecting these flags |

| Flag | Shipped template / Kotlin model | Effect |
| :--- | :--- | :--- |
| `simulation` | `false` / defaults to `false` | `DynamicKrakenService` delegates to **`SimulatedKrakenService`** (offline emulator). When `false`, uses **`KrakenServiceImpl`** (live API). |
| `dryRun` | `true` / required constructor value (no Kotlin default) | Within the **active** backend, order placement is suppressed. **Server logs:** `[DRY RUN]` (live) / `[EMULATOR DRY RUN]` (sim). **Dashboard activity log** always uses `[DRY RUN]` (`ActionLogFormatter` / `ViewText.DRY_RUN_PREFIX`). Calculations still run. |

## Routing

```text
Settings.simulation == true  → SimulatedKrakenService
Settings.simulation == false → KrakenServiceImpl
         └─ dryRun controls whether executeOrder hits the network / mutates sim balances
```

DI: `AppModule` binds `KrakenService` → `DynamicKrakenService(live, simulated, configService)`.

`PortfolioManagerImpl.performRebalanceCycle` and `TradeHistoryServiceImpl.syncTradesFromKraken`
wrap their full bodies in `DynamicKrakenService.withStableBackend` when the injected
`KrakenService` is Dynamic. That installs a **coroutine-context pin** so all reads
and writes in the cycle/sync use one backend.

`OrderExecutor.executeOrders` also wraps sell→buy in `withStableBackend`. Nested
calls **reuse the outer pin** (they do not re-resolve), so OrderExecutor cannot
shadow a full cycle/sync pin. Concurrent top-level invocations each capture their
own entry-time backend (no process-global pin). `OrderExecutor` also passes the
cycle’s `settings.dryRun` into each `executeOrder` so a mid-cycle dry-run flip
cannot change placement mode. Outside a stable block, each call still re-reads
`settings.simulation` (and `dryRun` when the override is omitted).

### When the backend is (not) pinned

- **Pinned:** `performRebalanceCycle`, `syncTradesFromKraken`,
  `OrderExecutor.executeOrders` (nested pins reuse the outer pin).
- **Unpinned:** dashboard balance/price reads, health checks — each call
  re-resolves from config at invocation time.
- Anti-patterns: assuming a mid-cycle config flip affects an already-pinned
  cycle (it does not); assuming a multi-step unpinned handler sees one stable
  backend (it does not).
- Tests that assert **mid-sequence** backend stability should wrap the scenario
  in `withStableBackend`. Mode-routing probes that intentionally call
  `executeOrder` outside a pin may omit it.

## dryRun order semantics

- Live backend dry-run: log `[DRY RUN]`, return
  `OrderResult(success = true, dryRun = true)` — no AddOrder POST.
- Emulator dry-run: `[EMULATOR DRY RUN]`, same success semantics, no balance
  mutation, usually no `orderTxid`.
- The activity log always prefixes `[DRY RUN]` regardless of backend.
- Cycle math and snapshots still run; only placement/settle differ.

## SimulatedKrakenService

- `SimulatedKrakenService`: MARKET orders only; synchronized lazy balance/price
  init; random-walk prices per ticker fetch.
- Seeding: historical snapshots (~15 days) and simulated trades when DB empty +
  simulation enabled (see trade-history-sync).
- Still honors `dryRun` (no balance mutation on orders).
- Dry-run emulator orders omit `orderTxid`, so sell settle uses the balance-poll
  fallback by design.

## Safety rules

- **Never** casually set `dryRun = false` in docs, snippets, or tests that might
  be copied into a live credentialed environment.
- Prefer `simulation = true` and/or `dryRun = true` for local demos.
- Live trading (`simulation=false`, `dryRun=false`) moves **real funds** —
  require explicit user intent before enabling.
- `TestFixtures.DEFAULT_TEST_SETTINGS` typically uses `simulation = true` with
  `dryRun = false` against Fake/sim paths — keep evaluation offline.
- README screenshot / user-guide capture requires **`simulation = true`**;
  `dryRun` is optional in that context (emulator orders are still offline) —
  see [docs-screenshot-refresh](../docs-screenshot-refresh/SKILL.md).

## Checklist

- [ ] Flags documented/handled as distinct
- [ ] DynamicKrakenService routing unchanged unless intentional
- [ ] Examples default to safe modes; live caution called out
