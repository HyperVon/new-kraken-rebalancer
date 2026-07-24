---
name: dry-run-and-simulation
description: >-
  Explains that Settings.dryRun and Settings.simulation are DISTINCT flags —
  DynamicKrakenService routing, SimulatedKrakenService seeding, and live-trading
  caution. Use when changing trading modes, examples, tests, or Kraken service
  selection.
---

# Dry Run vs Simulation

These flags are **independent**. Do not treat them as synonyms.

| Flag | Shipped template / Kotlin model | Effect |
| :--- | :--- | :--- |
| `simulation` | `false` / defaults to `false` | `DynamicKrakenService` delegates to **`SimulatedKrakenService`** (offline emulator). When `false`, uses **`KrakenServiceImpl`** (live API). |
| `dryRun` | `true` / required constructor value (no Kotlin default) | Within the **active** backend, order placement is suppressed and logged (`[DRY RUN]` live / `[EMULATOR DRY RUN]` sim). Calculations still run. |

## Routing

```text
Settings.simulation == true  → SimulatedKrakenService
Settings.simulation == false → KrakenServiceImpl
         └─ dryRun controls whether executeOrder hits the network / mutates sim balances
```

DI: `AppModule` binds `KrakenService` → `DynamicKrakenService(live, simulated, configService)`.

## SimulatedKrakenService

- Random-walk prices/balances for offline demos.
- Seeding: historical snapshots (~15 days) and simulated trades when DB empty +
  simulation enabled (see trade-history-sync).
- Still honors `dryRun` (no balance mutation on orders).

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
