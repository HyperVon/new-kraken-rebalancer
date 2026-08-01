# Continuous improvement backlog

Source of truth for what continuous-improvement cycles have found, shipped, or
deferred. Agents update this file each cycle per
[continuous-improvement](skills/continuous-improvement/SKILL.md).

**Status values:** `open` · `in_progress` · `done` · `deferred` · `dropped`

**Size:** `S` / `M` / `L` (see the skill). Anything that can change live orders,
`dryRun` / `simulation` semantics, or credentials is **L**.

**GitHub issues:** Create/link an issue for every **L** item and for any item
explicitly deferred across cycles. Small S items that ship in the same cycle PR
need only this file (no issue spam).

## Open / deferred

| ID | Size | Status | Area | Summary | Cycle | Issue | Notes |
| :--- | :---: | :--- | :--- | :--- | :---: | :--- | :--- |
| CI-21-T01 | M | open | tests | Extract duplicated PortfolioManager wiring (35+ call sites) to shared fixture | 21 | — | — |
| CI-21-T02 | M | open | tests | Fix flaky `DynamicKrakenServiceTest.concurrent withStableBackend` timing | 21 | — | — |

## Done (recent)

| ID | Size | Status | Area | Summary | Cycle | PR |
| :--- | :---: | :--- | :--- | :--- | :---: | :--- |
| CI-23-Q01 | M | done | tests | Add direct unit tests for `PortfolioAnalyzerImpl` (ATH/drawdown + buildSnapshot) | 23 | — |
| CI-21-Q01 | S | done | imports | Replace wildcard imports in 14 server view files (`kotlinx.html.*`, `kotlinx.css.*`) | 21 | — |
| CI-21-Q02 | S | done | imports | Replace wildcard imports in 18 test files (`io.mockk.*`, `io.ktor.*`) | 21 | — |
| CI-21-Q03 | S | done | imports | Replace wildcard imports in 13 frontend files (`org.w3c.dom.*`) | 21 | — |
| CI-21-Q04 | S | done | code | Remove blanket `@Suppress("unused")` from 15 test classes | 21 | — |
| CI-21-D01 | M | done | docs | Update README project structure tree with `RebalancerComparisonCalculator.kt` | 21 | — |
| CI-21-D02 | M | done | docs | Update README architecture diagram with RebalancerComparisonCalculator | 21 | — |
| CI-21-D03 | S | done | docs | Fix `@Suppress("unused")` contradiction in README (line 752 vs 15 test files) | 21 | — |
| CI-21-D04 | S | done | docs | Verify and fix CHANGELOG duplicate entry (v6.15.14/15 chart palette) | 21 | — |
| CI-21-L01 | L | done | complexity | Refactor `syncTradesFromKrakenPinned` (137 lines) in TradeHistorySyncService ([#153](https://github.com/HyperVon/new-kraken-rebalancer/issues/153)) | 21 | #158 |
| CI-21-L02 | L | done | complexity | Refactor `seedHistoricalData` (136 lines) in TradeHistorySnapshotStore ([#154](https://github.com/HyperVon/new-kraken-rebalancer/issues/154)) | 21 | #158 |
| CI-21-L03 | L | done | complexity | Decompose `isRenderable()` (25-line && chain) in History.kt ([#155](https://github.com/HyperVon/new-kraken-rebalancer/issues/155)) | 21 | #158 |
| CI-21-L04 | L | done | coverage | Add unit tests for HistoryApiMapper (DTO serialization layer) ([#156](https://github.com/HyperVon/new-kraken-rebalancer/issues/156)) | 21 | #158 |
| CI-21-L05 | L | done | coverage | Add unit tests for ErrorHandlingConfig (HTTP error handling paths) ([#157](https://github.com/HyperVon/new-kraken-rebalancer/issues/157)) | 21 | #158 |
| CI-16-C1 | S | done | code | Replace `TradeRecord` FQNs in `OrderExecutorImpl` with an import | 16 | #140 |
| CI-16-C2 | S | done | frontend | Replace dashboard sort-kind magic strings with a boolean | 16 | #140 |
| CI-16-D01 | S | done | docs | Correct stale scenario count from 33 to 34 | 16 | #140 |
| CI-16-UI1 | M | done | accessibility | Add keyboard sorting, announced sort state, and visible focus styling | 16 | #140 |
| CI-16-UI2 | S | done | accessibility | Respect the user's reduced-motion preference | 16 | #140 |
| CI-16-UI3 | M | done | accessibility | Associate global-settings labels with their inputs | 16 | #140 |
| CI-16-UI4 | S | done | accessibility | Name allocation-color pickers for assistive technology | 16 | #140 |
| CI-15-1 | S | done | agent workflow | Prefer the least expensive capable model and reasoning effort | 15 | #121 |
| CI-14-1 | M | done | history | Enforce the 300-point snapshot cap and preserve range endpoints | 14 | #113 |
| CI-14-2 | M | done | history | Scope latest snapshot time to the requested summary range | 14 | #113 |
| CI-14-3 | S | done | history | Make persisted snapshot action ordering deterministic | 14 | #113 |
| CI-14-4 | S | done | comments | Correct stale reconstruction-state wording | 14 | #113 |
| CI-14-5 | S | done | docs | Describe the fee estimate as fixed rather than configurable | 14 | #113 |
| CI-14-6 | S | done | deps | Patch webpack, kotlin-css, and Spotless; sync stack docs | 14 | #113 |
| CI-14-7 | S | done | comments | Remove brittle numbered narration from frontend startup | 14 | #113 |
| CI-11-UI6 | M | done | history | Tighten 9-col trade table density at ~1280 | 11 | #93 |
| CI-13-1..3 | S | done | css/tests | Dead ChartLegend/NoopSummary; ItemTrade CSS selector; view tests use CdnUrls/HtmlIds | 13 | #91 |
| CI-12-Q1..4 | S | done | common/views | CdnUrls (charts/fonts), TradeSourceKeys, HISTORY_SEEDED SyncMetadataKeys | 12 | #91 |
| CI-12-UI1 | M | done | css | Remove orphaned pre-DASH-3 activity + Offline badge CSS | 12 | #91 |
| CI-11-Q01..08 | S/M | done | code/docs/ui | RateLimiter KDoc, Settings POST 5.0 fallbacks, shared constants, Routes/CDN/SyncMetadataKeys DRY, History dynamicNumber, docs drift, allocation-total pill, dead CSS, stream placeholder | 11 | #87 |
| CI-11-D01..05 | S | done | docs | EVALUATION/README/skill trigger & stream wording | 11 | #87 |
| CI-11-UI1..05 | S/M | done | ui/css | Allocation total pill, StatusCluster/scrollbar cleanup, stream time slot, HeaderActions margin | 11 | #87 |
| CI-10-Q01..05 | S/M | done | frontend/service/docs | Settings HTML5 input bounds (allocations + global params), RateLimiter cleanup, tests, USER_GUIDE | 10 | #84 |
| — | — | done | docs | Cut Unreleased → 6.12.17 | 7 | #49 |
| — | — | done | css/tests | CssClass.Offline, TestDomBuilders, write-kotest | 7 | #48 |
| — | — | done | build/tests | Spotless frontend-js, IsolationMode, 6.12.15/16 | 6 | #47 |

## Done (earlier cycles)

| ID | Size | Status | Area | Summary | Cycle | Issue | Notes |
| :--- | :---: | :--- | :--- | :--- | :---: | :--- | :--- |
| CI-L1 | L | done | build | Spotless/ktlint for `**/view/**` | — | [#50](https://github.com/HyperVon/new-kraken-rebalancer/issues/50) | Shipped with ktlint 1.7.1 |
| CI-L2 | L | done | test | Spotless for `EvaluationScenariosTest` | — | [#51](https://github.com/HyperVon/new-kraken-rebalancer/issues/51) | Shipped; max_line_length off via .editorconfig |
| CI-L3 | L | done | frontend | Internal `CUMULATIVE_PL_*` rename | — | [#52](https://github.com/HyperVon/new-kraken-rebalancer/issues/52) | Shipped WIP |
| CI-L4 | L | done | trading | Trading-math / dryRun·simulation umbrella gate | — | [#53](https://github.com/HyperVon/new-kraken-rebalancer/issues/53) | Closed — standing policy in skill |
| CI-8-T01 | L | done | orders | USD refresh fails open → buys on unconfirmed cash | 8 | [#54](https://github.com/HyperVon/new-kraken-rebalancer/issues/54) | Shipped WIP |
| CI-8-T02 | L | done | credentials | Env credentials materialized to disk on save | 8 | [#55](https://github.com/HyperVon/new-kraken-rebalancer/issues/55) | Shipped WIP |
| CI-8-T03 | L | done | credentials | `hasValidCredentials` blank/placeholder only | 8 | [#56](https://github.com/HyperVon/new-kraken-rebalancer/issues/56) | Shipped WIP |
| CI-8-T04 | L | done | dryRun | Dry-run intents inflate success stats/fees | 8 | [#57](https://github.com/HyperVon/new-kraken-rebalancer/issues/57) | Shipped WIP |
| CI-8-D09 | L | done | deps | Major npm pins: diff/fast-uri/uuid/webpack-dev-server | 8 | [#58](https://github.com/HyperVon/new-kraken-rebalancer/issues/58) | Shipped WIP |
| CI-9-UI1 | M | done | history | History fees/slippage/price UI (#63) | 9 | [#63](https://github.com/HyperVon/new-kraken-rebalancer/issues/63) | Shipped on `feature/trade-economics-ui-20260724` |
| CI-8-SEC1 | S | done | security | Dependabot #102: `brace-expansion` high (DoS) in `kotlin-js-store/yarn.lock` — regenerate lock / pin patched version | 8 | [dependabot #102](https://github.com/HyperVon/new-kraken-rebalancer/security/dependabot/102) | Yarn `resolution("brace-expansion", "5.0.8")`; shipped with 6.12.19 cut |
| CI-9-B01 | S | done | build | Parallel Gradle execution/cache + bounded JVM test forks | 9 | — | Shipped WIP; override with `testForks` / `testMaxHeap` |
| CI-8-T05 | M | done | history | Deduper deletes later row; may drop settled API fill | 8 | — | Shipped WIP |
| CI-8-T06 | M | done | history | Pair-alias dedupe ignores USD/fee/provenance | 8 | — | Shipped WIP |
| CI-8-T07 | M | done | history | Reconstruct-from-live uses empty map → zero balances | 8 | — | Shipped WIP |
| CI-8-T08 | M | done | simulation | SimulatedKraken history page has no 50-row limit | 8 | — | Shipped WIP |
| CI-8-T09 | M | done | history | Sync throttle stamped before creds/network succeed | 8 | — | Shipped WIP |
| CI-8-T10 | M | done | simulation | Sim init skipped once any balance exists | 8 | — | Shipped WIP |
| CI-8-Q01 | S | done | common/js | Dead `CssClass.plus(String)`, unused HtmlTags/DOM helpers | 8 | — | Shipped WIP |
| CI-8-Q02 | S | done | common/js | Compose selectors from `HtmlTags` | 8 | — | Shipped WIP |
| CI-8-Q03 | S | done | tests | Zoom/range TestDomBuilders use shared constants | 8 | — | Shipped WIP |
| CI-8-Q04 | M | done | orders | Pass `OrderSide` through ActionLog/OrderExecutor | 8 | — | Ship (type-only; avoid T01 files if gated) |
| CI-8-Q05 | M | done | tests | Shared PortfolioManager test fixture | 8 | — | Shipped WIP |
| CI-8-Q06 | S | done | docs | KDoc/README ServiceUtils + fiat helper drift | 8 | — | Shipped WIP |
| CI-8-Q07 | S | done | skills | Drop stale `@Suppress("unused")` mandate | 8 | — | Shipped WIP |
| CI-8-Q08 | S | done | build | Remove redundant `DashboardViewTest` Spotless exclude | 8 | — | Shipped WIP |
| CI-8-Q09 | M | done | build | Narrow broad JaCoCo `config/**` / `view/util/**` excludes | 8 | — | Class-specific bootstrap/DSL exclusions; gates green |
| CI-8-D01 | S | done | docs | Rate-limit decay is linear, not exponential | 8 | — | Shipped WIP |
| CI-8-D02 | S | done | docs | README invents `RateLimitEvent` flow | 8 | — | Shipped WIP |
| CI-8-D03 | S | done | docs | AGENTS/skill say dryRun defaults false; template is true | 8 | — | Shipped WIP |
| CI-8-D04 | S | done | docs | “Best observed” USD wording vs last-positive/fallback | 8 | — | Shipped WIP |
| CI-8-D05 | S | done | docs | README still says ServiceUtils has retry helpers | 8 | — | Shipped WIP |
| CI-8-D06 | S | done | docs | API permission lists README vs SECURITY mismatch | 8 | — | Shipped WIP |
| CI-8-D07 | S | done | docs | ALGORITHM “exceed” vs inclusive `>=` | 8 | — | Shipped WIP |
| CI-8-D08 | M | done | skills | pre_commit_check: lint SECURITY/CONTRIBUTING; align build gate | 8 | — | Shipped WIP |

## How to update

1. After discovery: append new rows (`Status=open`).
2. When starting work: set `in_progress` and note the branch.
3. When shipping: move to **Done** with PR number; close linked GitHub issues.
4. When gating L: keep `deferred` until user approves; create/link Issue column.
5. Do not delete historical deferred L rows unless the user drops them.
