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
| CQ-1-10 | L | bug | deferred | simulation | Mid-cycle `settings.simulation` flip can split sell/buy backends in one `executeOrders` | 1 | [#68](https://github.com/HyperVon/new-kraken-rebalancer/issues/68) | Needs approval before routing pin |
| CQ-1-11 | L | bug | deferred | analyzer | `resolvePriceFromTicker` substring `contains` can collide; hardening changes live sizing | 1 | [#69](https://github.com/HyperVon/new-kraken-rebalancer/issues/69) | Documenting collision test shipped as CQ-1-2; matcher change gated |
| CQ-1-9 | M | harness | deferred | rate-limit | `RateLimiter` uses wall-clock `System.currentTimeMillis` — decay hard to assert under `runTest` | 1 | — | Partial cover via CQ-1-1; clock injection is larger refactor |
| CQ-1-4 | S | gap | deferred | orders | Dry-run sells inflate `projectedCash` for buy sizing with no balance refresh — documenting test still pending | 1 | — | Out of cycle-1 ship slice; keep for cycle 2 |
| CQ-1-8 | S | bug | deferred | fiat | Fiat-correction `share` not `toUsdScale()` before USD enqueue | 1 | — | Test + tiny scale fix deferred (near trading path) |

## In progress (cycle 1 — `quality/cycle-20260724-2047`)

| ID | Size | Kind | Status | Area | Summary | Cycle | Issue | Notes |
| :--- | :---: | :--- | :--- | :--- | :--- | :---: | :--- | :--- |
| CQ-1-1 | S | gap | in_progress | rate-limit | Assert throttle path leaves counter ≈ `safeLimit`; subsequent acquire behavior | 1 | — | `RateLimiterTest` |
| CQ-1-2 | M | gap | in_progress | analyzer | Document `contains()` first-match collision for ticker fallback (test only) | 1 | [#69](https://github.com/HyperVon/new-kraken-rebalancer/issues/69) | `PortfolioManagerEdgeCasesTest` |
| CQ-1-3 | S | gap | in_progress | ATH | New-ATH path when `save` throws still returns drawdown without crashing | 1 | — | `PortfolioManagerEdgeCasesTest` |
| CQ-1-5 | S | gap | in_progress | dedupe | Pair-alias fee mismatch; both API fills → later id; side mismatch non-dup | 1 | — | `TradeDeduplicatorTest` |
| CQ-1-6 | S | gap | in_progress | drawdown | MaxDD saturation / over-MaxDD coerce → 100% fiat deploy (exponents 1 & 2) | 1 | — | `PortfolioManagerDrawdownTest` |
| CQ-1-7 | S | gap | in_progress | docs | ALGORITHM / AGENTS / skill: USD refresh backoff is 250→500→1000ms (not 32s cap) | 1 | — | Doc-only |
| CQ-1-12 | S | gap | in_progress | dust | Exact dust `>=` boundary significant; just below not | 1 | — | `PortfolioCalculationsTest` |
| CQ-1-13 | S | gap | in_progress | orders | Executor dust: sell `==` threshold executes; `threshold - ε` skips | 1 | — | `OrderExecutorCashCapTest` |
| CQ-1-14 | M | gap | in_progress | history | SELL reverse-apply + OHLC closest price + negative/missing balance clamp | 1 | — | `SnapshotHistoryCalculatorTest` |
| CQ-1-15 | M | gap | in_progress | trigger | Deviation **exactly** at trigger fires; just below does not | 1 | — | `PortfolioManagerEdgeCasesTest` |

## Done (recent)

| ID | Size | Kind | Status | Area | Summary | Cycle | PR |
| :--- | :---: | :--- | :--- | :--- | :--- | :---: | :--- |
| — | — | — | — | — | — | — | — |

## How to update

1. After discovery: append new rows (`Status=open`, IDs like `CQ-1-1`).
2. When starting work: set `in_progress` and note the branch.
3. When shipping: move to **Done** with PR number; close linked GitHub issues.
4. When gating L: keep `deferred` until user approves; create/link Issue column.
5. Do not delete historical deferred L rows unless the user drops them.
6. Do not re-add items already `done` / `deferred` unless verifying they still apply.
