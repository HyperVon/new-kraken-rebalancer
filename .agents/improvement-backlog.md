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

## Dropped

Items evaluated and deliberately not pursued (never actioned; the recorded reason is why).

| ID | Size | Status | Area | Summary | Cycle | Notes |
| :--- | :---: | :--- | :--- | :--- | :---: | :--- |
| CI-22-Q06 | S | dropped | frontend | Replace wildcard `org.w3c.dom.*` imports in frontend-js main/test (17 files) | 22 | ktlint `no-wildcard-imports` rule disabled (build.gradle.kts:17) → not enforced, low-value churn |
| CI-27-C01 | S | dropped | code | Exposed `createMissingTablesAndColumns` still used | 27 | already migrated in `DatabaseConfig.kt` (createStatements + addMissingColumnsStatements + deprecation comment) |
| CI-27-C02 | S | dropped | code | Replace `java.math.BigDecimal` FQN with Kotlin alias | 27 | false — `java.math.BigDecimal` is the actual money type; no Kotlin alias exists |
| CI-27-DEP3 | S | dropped | ci | Add `setup-node` to CI | 27 | false premise — Kotlin/JS Gradle plugin manages its own Node; no npm needed |
| CI-27-DEP4 | S | dropped | ci | Pin Node via package.json `engines` | 27 | would not pin Gradle-managed Node; marginal noise |
| CI-27-U02 | S | dropped | css | Remove dead DOM imports in `DomExtensions.kt` | 27 | false — `CssClass`/`HtmlAttrs`/`org.w3c.dom.*` all used |
| CI-27-D03 | S | dropped | docs | "Go 1.26"/"Spring Boot 4" in Technology Journey | 27 | intentional narrative flavor, not drift |
| CI-27-D04 | S | dropped | docs | dryRun ambiguity | 27 | README:735 already states "Required in JSON; template `true`" |
| CI-27-D05 | S | dropped | docs | README `.kilo/` tree incomplete | 27 | already covered by CI-26-D02 |
| CI-27-U03 | S | deferred | ui | Scrubber sync after zoom-reset | 27 | plausible but needs manual QA; deferred (no blind edit) |
| CI-27-U04 | S | dropped | docs | Stale README screenshots | 27 | no CSS changed this cycle; no refresh needed |

## Open

| ID | Size | Status | Area | Summary | Cycle | Notes |
| :--- | :---: | :--- | :--- | :--- | :---: | :--- |
| CI-27-DP1 | S | in_progress | deps | Bound unpinned `js-yaml` Gradle resolution to `<5.0.0` (build.gradle.kts:272) | 27 | branch improve/cycle-20260807-0458 |
| CI-27-DP2 | S | in_progress | ci | Remove redundant `jacocoTestCoverageVerification` from CI `build` step (`tasks.check` already covers it) | 27 | branch improve/cycle-20260807-0458 |
| CI-27-DP3 | S | in_progress | ci | Add `cache: gradle` to dependency-submission setup-java step | 27 | branch improve/cycle-20260807-0458 |
| CI-27-D01 | S | in_progress | docs | De-date "couple of weeks" AI-Assisted Development claim in README | 27 | branch improve/cycle-20260807-0458 |
| CI-27-D02 | S | in_progress | docs | Warn that `rebalancer-config.json` is gitignored and must never be committed (README Getting Started) | 27 | branch improve/cycle-20260807-0458 |
| CI-27-U01 | S | in_progress | css | Add `:focus-visible` ring to the visually-hidden custom checkbox (FormStyles.kt) | 27 | branch improve/cycle-20260807-0458 |

## Done (recent)

| ID | Size | Status | Area | Summary | Cycle | PR |
| :--- | :---: | :--- | :--- | :--- | :---: | :--- |
| CI-25-C01 | S | done | trading | Guard `TradeCalculator.calculateSlippage` against non-positive expected prices (`!expectedPrice.isPositive`) | 25 | — |
| CI-25-C02 | S | done | history | Normalize trade side via `OrderSide.normalize` in `TradeHistorySyncService.legacyApiFillFingerprint` | 25 | — |
| CI-25-C03 | S | done | config | Remove unused import and use primitive `ALLOCATION_TOLERANCE_DELTA` in `ConfigServiceImpl` | 25 | — |
| CI-25-C04 | S | done | common | Move `OrderExecutorImpl` string constants into `:common` `ViewText` catalog | 25 | — |
| CI-25-C05 | M | done | math | Guard floating-point exponentiation in `RebalancerEngine.calculateFiatDeployment` with `.takeIf { it.isFinite() } ?: 0.0` | 25 | — |
| CI-25-D01 | S | done | docs | Clarify `SECURITY.md` permission scope regarding `Query Open Orders & Trades` for manual REST reconciliation | 25 | — |
| CI-25-D02 | S | done | docs | Document `STALE_THRESHOLD_SECONDS` and automatic SSE reconnection behavior in `docs/USER_GUIDE.md` | 25 | — |
| CI-26-C01 | S | done | code | Remove dead generated `DataProps` catalog (`StringConstantSchemas.kt` + `data-props.yaml`); drop from `:common` skill table | 26 | — |
| CI-26-C02 | M | done | repository | Extract shared `Database.readSyncMetadata` / `writeSyncMetadata` extensions in `RepositoryUtils.kt`; both ledger + trade repos delegate | 26 | — |
| CI-26-C04 | S | done | common | Remove unused `Result.exceptionOrNull()` accessor; update tests to `fold` | 26 | — |
| CI-26-CM1 | S | done | comments | Convert bare section-header labels in `ChartProps.kt` to KDoc group comments | 26 | — |
| CI-26-D01 | S | done | docs | Fix README `:codegen` description (JVM-only, not KMP) in tech table + project tree | 26 | — |
| CI-26-D02 | S | done | docs | Refresh README `.kilo/` project-structure tree (setup-script, run-script, agent-manager.json, model-router, command, agent) | 26 | — |
| CI-26-D03 | S | done | docs | Document `kraken.server.port` JVM property override in README Getting Started | 26 | — |
| CI-26-U01 | S | done | css | Add `flex-wrap: wrap` + `max-width: 100%` to `.time-range-selector` in `NavigationStyles` | 26 | — |
| CI-26-U02 | S | done | css | Tokenize 22 literal radii into `CssTheme` (`radiusXs/Sm/Md/Lg/Xl`) and reference across CSS files | 26 | — |
| CI-26-U03 | M | done | css | Promote repeated shadow scrims (`shadowScrim`, `shadowScrimSoft`) into `CssTheme` | 26 | — |
| CI-26-U04 | S | done | css/frontend | Toggle `CssClass.Utility.Hidden` instead of inline `style.display` for the sync banner in `HistoryLoading.kt`; add global `.hidden` rule | 26 | — |
| CI-26-U05 | S | done | css | Add `flex-wrap: wrap` to `.history-chart-tools` in `NavigationStyles` | 26 | — |
| CI-26-C03 | M | done | css | Add typed `CssBuilder` `*Raw` extensions in `CssBuilderExtensions.kt` for every raw `put("<prop>", …)` escape-hatch across `view/css/*` (~168 calls) so CSS property-name typos fail at compile time | 26 | 209 |
| CI-24-D01 | M | done | docs | Policy update across `docs/AGENTIC_DEVELOPMENT.md`, `.agents/OPERATING.md`, `.agents/AGENTS.md`, skills, and rules: Antigravity sessions launch subagents natively via `invoke_subagent` instead of calling Kilo CLI `route-subagents` / `subagents.py` scripts | 24 | — |
| CI-24-Q01 | S | done | frontend | Replace unsafe dynamic cast on `ctx.dataIndex` with safe numeric parsing and `snapshots.getOrNull()` in `HistoryCharts.kt` | 24 | — |
| CI-24-U01 | S | done | css | Deduplicate `.hero-tile-bar-track` background linear-gradient property in `ComponentStyles.kt` | 24 | — |
| CI-24-U04 | M | done | css | Add `flex-wrap: wrap` to `.history-views-actions` in `NavigationStyles.kt` for mobile viewports (<375px) | 24 | — |
| CI-24-U05 | M | done | css | Centralize button primary background gradient & shadow glow tokens in `CssTheme.kt` and update `FormStyles.kt` | 24 | — |
| CI-24-T02 | S | done | tests | Replace arbitrary wall-clock `delay(10.milliseconds)` with virtual scheduler `runCurrent()` in `PortfolioManagerLoopTest.kt` | 24 | — |
| CI-23-L01 | M | done | history | Wire `pruneLedgersOlderThan` into a ledger retention policy mirroring the trade prune — `LedgersSyncService.finalizeSync` now prunes ledger entries older than `HISTORICAL_DAYS_BACK` (90 days) after each completed sync | 23 | 193 |
| CI-23-L02 | M | done | ledger | Normalize Kraken Earn-migration asset suffixes (`.S`/`.M`/`.F`/`.B`, e.g. `DOT.S`) and legacy `X`/`Z` codes (`XXBT`, `ZUSD`) to the base symbol via new `Asset.normalizeLedgerAsset`, applied in `KrakenServiceImpl.getLedgers` | 23 | 193 |
| CI-23-L03 | S | done | history | Rewards silently skip assets missing from the snapshot universe/prices — rewards caption now states assets without a snapshot price in the range are excluded | 23 | 193 |
| CI-23-L04 | S | done | kraken | Pass the pinned config into `KrakenServiceImpl.getLedgers` — resolved by documenting the execution-session invariant: `ConfigServiceImpl` sessions pin `getConfig()`, so the re-read is safe (matches `getTradeHistory` convention); no interface churn | 23 | 193 |
| CI-23-L05 | S | done | docs | Permission naming: SECURITY.md now dual-cites "Query Ledgers (Kraken UI: *Data - Query ledger entries*)" | 23 | 193 |
| CI-23-L06 | S | done | history | `TradeHistoryQueryService.getRewardsOverTime` cumulative loop O(n·m) — replaced with a single pointer walk over time-sorted staking events (O(n+m)) | 23 | 193 |
| CI-23-L07 | S | done | history | Dividend ledger entries — user decision: keep persisting them, exclude from rewards chart/comparison math, and document as external USD-equivalent inflows (Kraken 'dividend' type = staking-reward payouts for assets like DOT outside the tracked universe); documented in `LedgerEvent` KDoc + README | 23 | 193 |
| CI-22-Q11 | M | done | config | ConfigServiceImpl fail-loudly on unknown JSON fields — decision: keep default (Jackson `FAIL_ON_UNKNOWN_PROPERTIES=true` throws `UnrecognizedPropertyException` at `ConfigServiceImpl.parseConfig`); no code change | 22 | — |
| CI-22-Q14 | S | done | code | Remove dead `Icons.BACK_ARROW` and its `icons/back_arrow.svg` asset | 22 | — |
| CI-22-Q05 | M | done | tests | Direct `HistoryChartStateTest.kt` unit tests for `historyCurrentRange` / `historyCaptureVisibility` / `historyRollbackPresetVisibility` (DOM + mock Chart.js harness) | 22 | — |
| CI-22-Q10 | M | done | frontend | Dedupe allocation-editor row into shared `:common` `AllocationEditor.editRow` HTML template, used by both SettingsFormComponent (SSR) and Settings.kt (JS) | 22 | — |
| CI-21-T01 | M | done | tests | Extract duplicated PortfolioManager wiring to shared fixture (last exact duplicate migrated; remaining call sites legitimately scoped) | 22 | — |
| CI-21-T02 | M | done | tests | De-flake `DynamicKrakenServiceTest.concurrent withStableBackend` via CompletableDeferred sequencing | 22 | — |
| CI-22-Q01 | M | done | frontend | Consolidate duplicated Chart.js currency-format builders (`usdOptionsToLocale` / `usdCellOrDash`) | 22 | — |
| CI-22-Q02 | M | done | css | Dedupe hero tile bar + allocation chart bar CSS (computed styles identical) | 22 | — |
| CI-22-Q03 | M | done | css | Centralize focus-ring / glass-surface / glow colors in `CssTheme` | 22 | — |
| CI-22-Q04 | M | done | tests | Direct `PortfolioAnalyzerImplTest` ATH/drawdown + buildSnapshot coverage | 22 | — |
| CI-22-Q07 | S | done | frontend | Use `CssClass.Utility.Positive/Negative/Neutral/Visible/Hidden` in HistoryComparisonChart + NavigationStyles; fix positive delta border (C6) | 22 | — |
| CI-22-C7 | S | done | frontend | Extract `queryChartScrubber(canvasId)` helper; dedupe scrubber query in HistoryChartState/HistoryZoom | 22 | — |
| CI-22-Q08 | S | done | frontend | Allocation input bounds from `:common` `PrecisionConstants` (SSR form + JS editor) | 22 | — |
| CI-22-Q09 | S | done | docs | README "exceeded" → `>=` trigger wording; add RebalancerComparisonEnums to tree + AGENTS RebalancerComparison | 22 | — |
| CI-22-Q12 | S | done | tests | `SseMultiSubscriberTest`: bounded subscription-count poll replaces fixed settle delay | 22 | — |
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
