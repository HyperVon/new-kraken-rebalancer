# Continuous quality backlog

Source of truth for what continuous-quality (QA) cycles have found, shipped, or
deferred. Agents update this file each cycle per
[continuous-quality](skills/continuous-quality/SKILL.md).

**Status values:** `open` · `in_progress` · `done` · `deferred` · `dropped`

**Size:** `S` / `M` / `L` (see the skill). Anything that can change live orders,
`dryRun` / `simulation` semantics, or credentials is **L**.

**Kind:** `gap` (missing coverage) · `bug` · `flake` · `ui` · `harness`

**GitHub issues:** Create/link an issue for every **L** item and for any item
explicitly deferred across cycles. Small S items that ship in the same cycle PR
need only this file (no issue spam).

Product polish discovered during QA belongs in
[improvement-backlog.md](improvement-backlog.md), not here.

## Open / deferred

| ID | Size | Kind | Status | Area | Summary | Cycle | Issue | Notes |
| :--- | :---: | :--- | :--- | :--- | :--- | :---: | :--- | :--- |
| CQ-9-6 | M | gap | open | eval | Scenario 33 — E2E drawdown deployment changes order sizes (not math-only) | 9 | — | Defer; Scenario 2 is analyzer-only |
| CQ-9-7 | S | gap | open | history | `getLatestTradeTime()` ignores newer dry-run rows | 9 | — | Backlog only |
| CQ-9-8 | S | gap | open | model | `isMatchingApiTrade`: volume within 1% but USD >1% → no match | 9 | — | Backlog only |
| CQ-9-9 | S | gap | open | frontend-js | `HistoryViewPrefs` legacy localStorage migration | 9 | — | Backlog only |
| CQ-9-10 | S | gap | open | orders | Partial multi-sell: failed sell must not bump `projectedCash` | 9 | — | Backlog only |

## Done (recent)

| ID | Size | Kind | Status | Area | Summary | Cycle | PR |
| :--- | :---: | :--- | :--- | :--- | :--- | :---: | :--- |
| CQ-8-3 | S | gap | done | history | Seam edges: multi-match reconcile first-in-range (DESC=newest), migration save failure leaves JSON, findClosest equidistant | 9 | TBD |
| CQ-9-1 | S | gap | done | drawdown | Conservative exponent `2.0` ALGORITHM MaxDD=30% table | 9 | TBD |
| CQ-9-2 | M | gap | done | orders | Multi-leg fills same `orderTxid` summed for buy budget (+ filter legs) | 9 | TBD |
| CQ-9-3 | S | gap | done | algorithm | Underweight exact `−trigger%` enqueues BUY | 9 | TBD |
| CQ-9-4 | S | gap | done | algorithm | Zero-target 100% deviation but `\|devUSD\| < dust` not significant | 9 | TBD |
| CQ-9-5 | S | gap | done | modes | Live `simulation=false` + `dryRun=true` routes to live + forwards dryRun | 9 | TBD |
| CQ-8-L1 | L | bug | done | history/sync | `isMatchingApiTrade` ignores dry-run locals (no promote to live `API_FILL`) | 8 | #100 |
| CQ-8-M1 | M | bug | done | history/sync | Cross-page duplicate API fill fingerprint skip within one sync | 8 | #100 |
| CQ-8-M2 | M | perf | done | history/sync | Persist `sync_watermark_epoch_sec` so dry-run-only accounts stay incremental | 8 | #100 |
| CQ-8-1 | M | gap | done | frontend-js | `HistoryJsonParsingEdgeTest`: missing price/fee→"0", JSON.parse bool + numeric/string id, absent success/dryRun, null/empty inputs, count coercion | 8 | #100 |
| CQ-8-2 | S | gap | done | api/serialization | `SerializationParityTest`: null-optional `TradeRecord` + null-offset `SyncProgressResponse` round-trips | 8 | #100 |
| CQ-7-L1 | L | bug | done | rate-limit | RateLimiter holds Mutex across `delay` (HOL blocking) | 7 | #93 |
| CQ-7-L2 | L | bug | done | modes | DynamicKraken unpinned reads outside `withStableBackend` | 7 | #93 |
| CQ-7-1 | M | gap | done | algorithm | USD+crypto both trigger → no fiat-correction path | 7 | #90 |
| CQ-7-2 | S | gap | done | algorithm | Pct at trigger + USD below dust → no orders | 7 | #90 |
| CQ-7-3 | S | gap | done | modes | simulation=true + placeholder keys still syncs | 7 | #90 |
| CQ-7-4 | M | bug | done | history | Dry-run excluded from reconstruction; case-insensitive reverse-apply side | 7 | #90 |
| CQ-7-5 | S | gap | done | dedupe | Pair-alias volume Δ >1% must not dedupe | 7 | #90 |
| CQ-7-6 | S | gap | done | frontend-js | `dynamicNumber` ISO/`Date` parse branch | 7 | #90 |
| CQ-7-7 | S | gap | done | frontend-js | Allocation total tolerance edges + invalid symbol alert | 7 | #90 |
| CQ-5-1 | M | harness | done | build | Filtered `--tests` runs no longer fail project-wide JaCoCo gates; full runs still verify | 6 | [#79](https://github.com/HyperVon/new-kraken-rebalancer/pull/79) |
| CQ-5-2 | S | harness | done | skills | One Gradle build per clone (worktree or parent-owns-build); concurrent builds cause `EOFException` / false `UP-TO-DATE` | 6 | [#79](https://github.com/HyperVon/new-kraken-rebalancer/pull/79) |
| CQ-5-3 | S | harness | done | skills | Final verification must force re-execution (`--rerun-tasks`) and check JUnit XML counts | 6 | [#79](https://github.com/HyperVon/new-kraken-rebalancer/pull/79) |
| CQ-5-4 | S | gap | done | history | Remove redundant double-lookup Elvis in snapshot seeding; other defensive branches kept by design | 6 | [#79](https://github.com/HyperVon/new-kraken-rebalancer/pull/79) |
| CQ-5-5 | S | harness | done | docs | improvement-backlog: 35 completed rows moved out of the Open section | 6 | [#79](https://github.com/HyperVon/new-kraken-rebalancer/pull/79) |
| CQ-3-14 | M | gap | done | history/repo | Lift `TradeHistoryServiceImpl` + `repository.impl` branch coverage (overall ~95%) | 5 | [#78](https://github.com/HyperVon/new-kraken-rebalancer/pull/78) |
| CQ-3-26 | L | bug | done | fiat | Skip `$0.00` fiat-correction shares; cap sum ≤ `\|usdDev\|` via truncated budget | 5 | [#78](https://github.com/HyperVon/new-kraken-rebalancer/pull/78) |
| CQ-3-28 | M | gap | done | eval | Scenario 32 — multi-cycle convergence with fill feedback, zero orders by cycle 3 | 5 | [#78](https://github.com/HyperVon/new-kraken-rebalancer/pull/78) |
| CQ-3-9 | S | gap | done | history | Reconstruct failure is best-effort: throttle window still opens, no extra Kraken calls | 5 | [#77](https://github.com/HyperVon/new-kraken-rebalancer/pull/77) |
| CQ-3-17 | M | gap | done | eval | Scenario 31 — USD refresh ≥95% early-accept + fail-closed buys | 5 | [#77](https://github.com/HyperVon/new-kraken-rebalancer/pull/77) |
| CQ-3-18 | S | gap | done | drawdown | Aggressive exponent `0.5` ALGORITHM table points | 5 | [#77](https://github.com/HyperVon/new-kraken-rebalancer/pull/77) |
| CQ-3-20 | M | gap | done | history/SSE | Real `snapshotFlow` multi-subscriber + `DROP_OLDEST` non-blocking producer | 5 | [#77](https://github.com/HyperVon/new-kraken-rebalancer/pull/77) |
| CQ-3-22 | M | gap | done | rate-limit | Public ticker/OHLC never `acquire`; private heavy paths cost `2.0` (injectable limiter) | 5 | [#77](https://github.com/HyperVon/new-kraken-rebalancer/pull/77) |
| CQ-3-5 | M | gap | done | flows | `collectLatest` config emit mid-`delay` cancels and restarts loop with new settings | 4 | [#75](https://github.com/HyperVon/new-kraken-rebalancer/pull/75) |
| CQ-3-6 | S | gap | done | lockout | 9 consecutive `Temporary lockout` exhausts `maxLockoutAttempts` and throws | 4 | [#75](https://github.com/HyperVon/new-kraken-rebalancer/pull/75) |
| CQ-3-7 | S | gap | done | modes | `simulation=true` + `dryRun=true`: DynamicKraken → sim; dry-run does not mutate balances | 4 | [#75](https://github.com/HyperVon/new-kraken-rebalancer/pull/75) |
| CQ-3-8 | S | gap | done | dedupe | API_FILL then LOCAL_ESTIMATE deletes local; same fee-rate pair kept | 4 | [#75](https://github.com/HyperVon/new-kraken-rebalancer/pull/75) |
| CQ-3-10 | S | gap | done | DynamicKraken | Nested/reentrant `withStableBackend` (pinDepth > 0) | 4 | [#75](https://github.com/HyperVon/new-kraken-rebalancer/pull/75) |
| CQ-3-11 | S | gap | done | DashboardController | History stats with no `range` → no-arg `getHistoryStats()` | 4 | [#75](https://github.com/HyperVon/new-kraken-rebalancer/pull/75) |
| CQ-3-12 | S | gap | done | dedupe | Null-id skip / null `idToDelete` when deleting unsettled | 4 | [#75](https://github.com/HyperVon/new-kraken-rebalancer/pull/75) |
| CQ-3-13 | S | gap | done | ConfigService | Reject invalid allocation symbol (`SYMBOL_PATTERN`) | 4 | [#75](https://github.com/HyperVon/new-kraken-rebalancer/pull/75) |
| CQ-3-15 | S | gap | done | orders | Failed buy must not reduce cycle 99% budget for subsequent buys | 4 | [#75](https://github.com/HyperVon/new-kraken-rebalancer/pull/75) |
| CQ-3-16 | S | gap | done | math | Underweight exact dust `\|dev\|==threshold` significant; just-below not | 4 | [#75](https://github.com/HyperVon/new-kraken-rebalancer/pull/75) |
| CQ-3-19 | S | bug | done | flows | Rethrow `CancellationException` in cycle/sync `catch (Exception)` | 4 | [#75](https://github.com/HyperVon/new-kraken-rebalancer/pull/75) |
| CQ-3-21 | S | gap | done | dedupe | Fee-rate Δ exactly `0.001`; local-estimate window `10_000` vs `10_001` ms | 4 | [#75](https://github.com/HyperVon/new-kraken-rebalancer/pull/75) |
| CQ-3-24 | S | gap | done | orders | Buy trimmed by remaining budget below dust → skip; budget never negative | 4 | [#75](https://github.com/HyperVon/new-kraken-rebalancer/pull/75) |
| CQ-3-25 | M | gap | done | manager | Post-trade snapshot fallback: `Result.Failure` + thrown branch | 4 | [#75](https://github.com/HyperVon/new-kraken-rebalancer/pull/75) |
| CQ-3-1 | S | gap | done | orders | USD refresh early-accept at ≥95% of projected stops polling | 3 | [#73](https://github.com/HyperVon/new-kraken-rebalancer/pull/73) |
| CQ-3-2 | S | gap | done | orders | Below 95% keeps polling; later ≥95% accepts early | 3 | [#73](https://github.com/HyperVon/new-kraken-rebalancer/pull/73) |
| CQ-3-3 | S | gap | done | dedupe | 5min window: `diff == 300_000` still duplicates; `> 300_000` does not | 3 | [#73](https://github.com/HyperVon/new-kraken-rebalancer/pull/73) |
| CQ-3-4 | S | gap | done | analyzer | Explicit zero ticker price aborts (not only missing key) | 3 | [#73](https://github.com/HyperVon/new-kraken-rebalancer/pull/73) |
| CQ-3-23 | L | bug | done | orders | Skip zero/negative-value orders (`dustThresholdUSD=0` / budget-trimmed `$0`) | 3 | [#73](https://github.com/HyperVon/new-kraken-rebalancer/pull/73) |
| CQ-1-10 | L | bug | done | simulation | Pin live/sim backend across `executeOrders` via `withStableBackend` | 2 | [#71](https://github.com/HyperVon/new-kraken-rebalancer/pull/71) |
| CQ-1-11 | L | bug | done | analyzer | Exact USD pair-alias ticker match (no substring `contains`) | 2 | [#71](https://github.com/HyperVon/new-kraken-rebalancer/pull/71) |
| CQ-1-9 | M | harness | done | rate-limit | Injectable `RateLimiter` clock + deterministic decay test | 2 | [#71](https://github.com/HyperVon/new-kraken-rebalancer/pull/71) |
| CQ-1-4 | S | gap | done | orders | Dry-run buy budget uses projected cash (no USD refresh) | 2 | [#71](https://github.com/HyperVon/new-kraken-rebalancer/pull/71) |
| CQ-1-8 | S | bug | done | fiat | Fiat-correction shares use `toUsdScale()` | 2 | [#71](https://github.com/HyperVon/new-kraken-rebalancer/pull/71) |
| CQ-1-1 | S | gap | done | rate-limit | Assert throttle path leaves counter ≈ `safeLimit`; subsequent acquire behavior | 1 | [#70](https://github.com/HyperVon/new-kraken-rebalancer/pull/70) |
| CQ-1-2 | M | gap | done | analyzer | Document `contains()` first-match collision for ticker fallback (test only) | 1 | [#70](https://github.com/HyperVon/new-kraken-rebalancer/pull/70) |
| CQ-1-3 | S | gap | done | ATH | New-ATH path when `save` throws still returns drawdown without crashing | 1 | [#70](https://github.com/HyperVon/new-kraken-rebalancer/pull/70) |
| CQ-1-5 | S | gap | done | dedupe | Pair-alias fee mismatch; both API fills → later id; side mismatch non-dup | 1 | [#70](https://github.com/HyperVon/new-kraken-rebalancer/pull/70) |
| CQ-1-6 | S | gap | done | drawdown | MaxDD saturation / over-MaxDD coerce → 100% fiat deploy (exponents 1 & 2) | 1 | [#70](https://github.com/HyperVon/new-kraken-rebalancer/pull/70) |
| CQ-1-7 | S | gap | done | docs | ALGORITHM / AGENTS / skill: USD refresh backoff is 250→500→1000ms (not 32s cap) | 1 | [#70](https://github.com/HyperVon/new-kraken-rebalancer/pull/70) |
| CQ-1-12 | S | gap | done | dust | Exact dust `>=` boundary significant; just below not | 1 | [#70](https://github.com/HyperVon/new-kraken-rebalancer/pull/70) |
| CQ-1-13 | S | gap | done | orders | Executor dust: sell `==` threshold executes; `threshold - ε` skips | 1 | [#70](https://github.com/HyperVon/new-kraken-rebalancer/pull/70) |
| CQ-1-14 | M | gap | done | history | SELL reverse-apply + OHLC closest price + negative/missing balance clamp | 1 | [#70](https://github.com/HyperVon/new-kraken-rebalancer/pull/70) |
| CQ-1-15 | M | gap | done | trigger | Deviation **exactly** at trigger fires; just below does not | 1 | [#70](https://github.com/HyperVon/new-kraken-rebalancer/pull/70) |

## How to update

1. After discovery: append new rows (`Status=open`, IDs like `CQ-1-1`).
2. When starting work: set `in_progress` and note the branch.
3. When shipping: move to **Done** with PR number; close linked GitHub issues.
4. When gating L: keep `deferred` until user approves; create/link Issue column.
5. Do not delete historical deferred L rows unless the user drops them.
6. Do not re-add items already `done` / `deferred` unless verifying they still apply.
