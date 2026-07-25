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
| — | — | — | — | — | _(none)_ | — | — | — |

## Done (recent)

| ID | Size | Kind | Status | Area | Summary | Cycle | PR |
| :--- | :---: | :--- | :--- | :--- | :--- | :---: | :--- |
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
