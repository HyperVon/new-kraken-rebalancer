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
| CQ-3-9 | S | gap | open | history | Sync OK but reconstruct throws still sets `lastSyncTime` (throttle starts) | 3 | — | next cycle |
| CQ-3-14 | M | gap | open | history/repo | Lift `TradeHistoryServiceImpl` + `repository.impl` branch coverage | 3 | — | overall branch ~91%; next cycle |
| CQ-3-17 | M | gap | open | eval | Eval scenario for USD refresh ≥95% early-accept / fail-closed | 3 | — | unit coverage shipped CQ-3-1/2; eval optional |
| CQ-3-18 | S | gap | open | drawdown | Aggressive exponent `0.5` ALGORITHM table points (e.g. 7.5% DD → 50%) | 3 | — | exponents 1.0 & 2.0 covered |
| CQ-3-20 | M | gap | open | history/SSE | Real `snapshotFlow`: ≥2 subscribers receive; `DROP_OLDEST` keeps `addSnapshot`/`tryEmit` non-blocking | 3 | — | tests use single collector / mocked `flowOf` |
| CQ-3-22 | M | gap | open | rate-limit | Public ticker/OHLC never `acquire`; private TradesHistory/Ledgers/ClosedOrders cost **2.0** | 3 | — | throttle test is Balance×8 only; needs injectable limiter like CQ-1-9 |
| CQ-3-26 | S | gap | open | fiat | Fiat-correction share rounding to `$0.00` must not enqueue zero orders; shares ≤ `\|usdDev\|` | 3 | — | analyzer companion to CQ-3-23 |
| CQ-3-27 | M | gap | open | eval | Multi-cycle convergence (Scenario 31): fills fed back, zero orders by cycle 3 | 3 | — | needs docs/EVALUATION row |

## Done (recent)

| ID | Size | Kind | Status | Area | Summary | Cycle | PR |
| :--- | :---: | :--- | :--- | :--- | :---: | :--- | :--- |
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
