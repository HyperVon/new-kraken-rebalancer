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

_None — all cycle-14 items are done. See the Done table below for both #168
(cycle-14 production fixes) and #169 (cycle-15 test-only gap closures)._

## Done (recent)

| ID | Size | Kind | Status | Area | Summary | Cycle | PR | Issue |
| :--- | :---: | :--- | :--- | :--- | :--- | :---: | :--- | :---: |
| CQ-14-L1 | L | bug | done | config/orders/simulation | Canonicalize allocation symbols and reject Kraken alias collisions before valuation and execution | 14 | #168 | #163 |
| CQ-14-L2 | L | bug | done | orders/journal | A live success without an order transaction ID must remain blocking `UNCERTAIN` | 14 | #168 | #161 |
| CQ-14-L3 | L | bug | done | orders/dust | Apply the dust threshold to floored submitted buy notional | 14 | #168 | #166 |
| CQ-14-L4 | L | bug | done | history/dedupe | Preserve conflicting trade provenance during startup local/API cleanup | 14 | #168 | #165 |
| CQ-14-L5 | L | bug | done | history/sync | Resume interrupted initial pagination instead of skipping older Kraken fills | 14 | #168 | #162 |
| CQ-14-L6 | L | bug | done | security/config | Harden credential-bearing config permissions and temporary-file cleanup | 14 | #168 | #167 |
| CQ-14-L7 | L | bug | done | flows/manager/orders | Make the PortfolioManager worker restartable and single-owner | 14 | #168 | #160 |
| CQ-14-L8 | L | bug | done | lifecycle/orders | Join the rebalance worker before shutdown closes HTTP and Koin dependencies | 14 | #168 | #164 |
| CQ-14-M2 | M | gap | done | history/migration | Exercise a genuinely old SQLite schema and provenance/submission-column migration | 14 | #168 | — |
| CQ-14-M3 | M | gap | done | history/sync | Cover cancellation after sync progress or trade persistence begins | 14 | #168 | — |
| CQ-14-M5 | M | gap | done | config/validation | Directly cover NaN and positive/negative infinity allocation targets | 14 | #168 | — |
| CQ-14-M8 | M | gap | done | flows/manager | Cover PortfolioManager cancellation during active analysis, execution, settlement, or snapshot work | 14 | #168 | — |
| CQ-14-M10 | M | bug | done | frontend-js/dashboard | Fix numeric dashboard Price and Value sorting for comma-formatted currency | 14 | #168 | — |
| CQ-14-M11 | M | bug | done | frontend-js/settings | Reinitialize settings controls and allocation validation after an HTMX error fragment swap | 14 | #168 | — |
| CQ-14-M12 | M | bug | done | frontend-js/history | Do not relabel stale History data when a selected range request fails | 14 | #168 | — |
| CQ-14-M13 | M | gap | done | frontend-js/history | Assert all six History summary cards update across time ranges, including null slippage | 14 | #168 | — |
| CQ-14-M14 | M | gap | done | frontend-js/history | Exercise captured drag/wheel zoom callbacks and assert fallback min/max bounds | 14 | #168 | — |
| CQ-14-M15 | M | harness | done | eval/flows | Stop evaluation scenarios from overstating hot SSE/config reload coverage built on finite or cold flows | 14 | #168 | — |
| CQ-14-M16 | M | harness | done | eval/report | Record failed evaluation scenarios and verify report count from registered cases | 14 | #168 | — |
| CQ-14-M17 | M | flake | done | frontend-js/tests | Replace wall-clock JS test delays with explicit Promise/deferred readiness | 14 | #168 | — |
| CQ-14-M1 | M | gap | done | orders/journal | Cover the SQLite-backed `PENDING` to resolved/`UNCERTAIN` journal lifecycle end to end | 14 | #169 | — |
| CQ-14-M4 | M | gap | done | security/CORS | Exercise production Ktor CORS wiring for allowed private and rejected public origins | 14 | #169 | — |
| CQ-14-M6 | M | gap | done | Kraken/orders | Cover AddOrder rate-limit cost, signing headers, nonce, and one-attempt transport failure | 14 | #169 | — |
| CQ-14-M7 | M | gap | done | config/flows | Cover nested execution-session publication through the real config flow | 14 | #169 | — |
| CQ-14-M9 | M | gap | done | history/SSE | Exercise the real hot snapshot flow through multiple HTTP SSE subscribers | 14 | #169 | — |
| CQ-14-4 | S | gap | done | security/CSRF | Assert CSRF cookie attributes and reject duplicate matching form tokens | 14 | #169 | — |
| CQ-14-15 | S | harness | done | flows/manager | Add behavioral assertions to exception and cancellation loop tests | 14 | #169 | — |
| CQ-14-1 | S | gap | done | history/repository | Verify snapshot asset/action child-row pruning retains recent children and removes old ones | 14 | #168 | — |
| CQ-14-2 | S | gap | done | history/reconstruction | Cover cancellation and ordinary failure from reconstruction snapshot persistence | 14 | #168 | — |
| CQ-14-3 | S | gap | done | history/flows | Verify a late snapshot subscriber receives the replayed latest snapshot | 14 | #168 | — |
| CQ-14-5 | S | gap | done | modes/config | Verify simulation flag persistence through disk reload and dynamic routing | 14 | #168 | — |
| CQ-14-6 | S | gap | done | security/credentials | Assert API/private key and credentials string representations redact secrets | 14 | #168 | — |
| CQ-14-7 | S | gap | done | frontend-js/history | Cover malformed comparison predicates and the valid `ESTIMATED` badge branch | 14 | #168 | — |
| CQ-14-8 | S | gap | done | frontend-js/history | Add native JSON wire fixtures for snapshots, stats, comparison, and sync progress | 14 | #168 | — |
| CQ-14-9 | S | harness | done | eval/docs | Correct Scenario 10 evidence to test the actual stats database failure path | 14 | #168 | — |
| CQ-14-10 | S | harness | done | eval/docs | Make Scenario 20 test malformed JSON or rename its missing-file behavior claim | 14 | #168 | — |
| CQ-14-11 | S | harness | done | eval/docs | Sync README and EVALUATION simulation suite count from five to six | 14 | #168 | — |
| CQ-14-12 | S | harness | done | simulation/eval | Make the simulation trade-identity assertion deterministic instead of conditional on nonempty trades | 14 | #168 | — |
| CQ-14-13 | S | gap | done | orders/settlement | Cover fill-history exceptions and balance-poll fallback before a buy | 14 | #168 | — |
| CQ-14-14 | S | gap | done | orders/journal | Verify a failed `PENDING` persistence prevents any live exchange call | 14 | #168 | — |
| CQ-14-16 | S | flake | done | eval/tests | Use unique temporary stats paths and guaranteed cleanup in evaluation scenarios | 14 | #168 | — |
| CQ-14-17 | S | harness | done | eval | Require Scenario 25 to distinguish successful ETH logging from failure text | 14 | #168 | — |
| CQ-14-18 | S | harness | done | eval/math | Replace nullable Double equality in Scenario 30 with required BigDecimal comparisons | 14 | #168 | — |
| CQ-14-19 | S | harness | done | tests/math | Replace direct BigDecimal `shouldBe` with `shouldBeEqualComparingTo` | 14 | #168 | — |
| CQ-13-1 | S | gap | done | rate-limit | Enforce cost bounds (`cost > 0.0` and `cost <= safeLimit`) in `RateLimiter` | 13 | #139 | — |
| CQ-13-2 | S | gap | done | orders | Cover zero price/volume order execution guard paths | 13 | #139 | — |
| CQ-13-3 | S | gap | done | history/calc | Cover unknown order side handling during timeline reconstruction | 13 | #139 | — |
| CQ-13-4 | S | gap | done | history/dedupe | Cover zero delta and API-before-local trade ordering | 13 | #139 | — |
| CQ-13-5 | S | gap | done | frontend-js | Cover empty dynamic object payload parsing | 13 | #139 | — |
| CQ-12-L1 | L | bug | done | settings | Reject malformed or mismatched Settings form fields | 12 | #138 | — |
| CQ-12-L2 | L | bug | done | security | Harden no-auth dashboard CORS origin validation | 12 | #138 | — |
| CQ-12-L3 | L | bug | done | orders | Cap zero-target sell volume to available holdings | 12 | #138 | — |
| CQ-12-L4 | L | bug | done | orders | Deduplicate shifted Kraken fill pages during sell settlement | 12 | #138 | — |
| CQ-12-L5 | L | bug | done | history/sync | Preserve dry-run isolation during exact order-ID reconciliation | 12 | #138 | — |
| CQ-12-L6 | L | bug | done | history/sync | Serialize concurrent paginated trade-history syncs | 12 | #138 | — |
| CQ-12-L7 | L | bug | done | history/sync | Prevent clock rollback from suppressing history sync | 12 | #138 | — |
| CQ-12-1 | M | bug | done | Kraken/OHLC | Propagate cancellation from live OHLC requests | 12 | #138 | — |
| CQ-12-2 | M | gap | done | orders | Cover live submission exceptions, uncertain journaling, and later-cycle blocking | 12 | #138 | — |
| CQ-12-3 | M | bug | done | simulation | Persist actual non-live order exceptions instead of stale pending text | 12 | #138 | — |
| CQ-12-4 | S | harness | done | eval | Correct Scenario 5 evidence to match the cycle-boundary assertion | 12 | #138 | — |
| CQ-11-L1 | L | bug | done | algorithm | Preserve four-decimal percentage precision before live trigger comparison | 11 | #130 | — |
| CQ-11-L2 | L | bug | done | algorithm | Define safe fiat-deployment behavior for USD-only portfolios | 11 | #130 | — |
| CQ-11-L3 | L | bug | done | config/sync | Pin configuration and credentials across paginated history sync | 11 | #130 | — |
| CQ-11-L4 | L | bug | done | rate-limit | Prevent counter inflation after wall-clock rollback | 11 | #130 | — |
| CQ-11-L5 | L | bug | done | history/sync | Reconcile Kraken fills to authoritative order transaction IDs | 11 | #130 | — |
| CQ-11-L6 | L | bug | done | history/dedupe | Stop transitive trade dedupe beyond the five-minute window | 11 | #130 | — |
| CQ-11-1 | S | gap | done | algorithm | Lock raw-value accumulation and single-round portfolio-total invariant | 11 | #130 | — |
| CQ-11-2 | M | harness | done | eval | Replace Scenario 12 unconditional PASS with exact order and precision assertions | 11 | #130 | — |
| CQ-11-3 | M | bug | done | simulation | Serialize emulator balance mutations across concurrent orders | 11 | #130 | — |
| CQ-11-4 | S | gap | done | history/init | Cover duplicate-cleanup cancellation propagation and ordinary-error recovery | 11 | #130 | — |
| CQ-10-L1 | L | bug | done | history/sync | Failed non-dry-run attempts can advance the Kraken sync cursor | 10 | #121 | — |
| CQ-10-L2 | L | bug | done | history/sync | Persisted API fills can be overwritten by distinct nearby fills | 10 | #121 | — |
| CQ-10-L3 | L | bug | done | config | Failed config writes publish unpersisted runtime settings | 10 | #121 | — |
| CQ-10-L4 | L | bug | done | ATH | Stats read failures collapse ATH to zero and fail open | 10 | #121 | — |
| CQ-10-L5 | L | bug | done | config | Non-finite settings persist and later crash rebalance cycles | 10 | #121 | — |
| CQ-10-L6 | L | bug | done | history/sync | Rounded fill fingerprints can collapse distinct Kraken fill legs | 10 | #121 | — |
| CQ-10-L7 | L | bug | done | history/migration | Ambiguous legacy source-less rows need an explicit provenance policy | 10 | #121 | — |
| CQ-10-1 | S | gap | done | history/sync | Preserve local orderTxid when a matching API fill omits it | 10 | #121 | — |
| CQ-10-2 | M | gap | done | history | Propagate cancellation from reconstruction balance/ticker/OHLC calls | 10 | #121 | — |
| CQ-10-3 | M | bug | done | frontend-js | Ignore stale out-of-order History range responses | 10 | #121 | — |
| CQ-10-4 | M | bug | done | frontend-js | Clear populated History charts when a selected range is empty | 10 | #121 | — |
| CQ-10-5 | S | bug | done | frontend-js | Reject NaN and infinite numeric inputs | 10 | #121 | — |
| CQ-10-6 | S | bug | done | frontend-js | Preserve valid saved views when one preset entry is malformed | 10 | #121 | — |
| CQ-10-7 | M | bug | done | security | Return generic 500 bodies without internal exception details | 10 | #121 | — |
| CQ-9-6 | M | gap | done | eval | Scenario 33 — E2E drawdown deployment changes order sizes (not math-only) | 9 | #104 | — |
| CQ-9-7 | S | gap | done | history | `getLatestTradeTime()` ignores newer dry-run rows | 9 | #104 | — |
| CQ-9-8 | S | gap | done | model | `isMatchingApiTrade`: volume within 1% but USD >1% → no match | 9 | #104 | — |
| CQ-9-9 | S | gap | done | frontend-js | `HistoryViewPrefs` legacy localStorage migration | 9 | #104 | — |
| CQ-9-10 | S | gap | done | orders | Partial multi-sell: failed sell must not bump `projectedCash` | 9 | #104 | — |
| CQ-8-3 | S | gap | done | history | Seam edges: multi-match reconcile first-in-range (DESC=newest), migration save failure leaves JSON, findClosest equidistant | 9 | #104 | — |
| CQ-9-1 | S | gap | done | drawdown | Conservative exponent `2.0` ALGORITHM MaxDD=30% table | 9 | #104 | — |
| CQ-9-2 | M | gap | done | orders | Multi-leg fills same `orderTxid` summed for buy budget (+ filter legs) | 9 | #104 | — |
| CQ-9-3 | S | gap | done | algorithm | Underweight exact `−trigger%` enqueues BUY | 9 | #104 | — |
| CQ-9-4 | S | gap | done | algorithm | Zero-target 100% deviation but `\|devUSD\| < dust` not significant | 9 | #104 | — |
| CQ-9-5 | S | gap | done | modes | Live `simulation=false` + `dryRun=true` routes to live + forwards dryRun | 9 | #104 | — |
| CQ-8-L1 | L | bug | done | history/sync | `isMatchingApiTrade` ignores dry-run locals (no promote to live `API_FILL`) | 8 | #100 | — |
| CQ-8-M1 | M | bug | done | history/sync | Cross-page duplicate API fill fingerprint skip within one sync | 8 | #100 | — |
| CQ-8-M2 | M | perf | done | history/sync | Persist `sync_watermark_epoch_sec` so dry-run-only accounts stay incremental | 8 | #100 | — |
| CQ-8-1 | M | gap | done | frontend-js | `HistoryJsonParsingEdgeTest`: missing price/fee→"0", JSON.parse bool + numeric/string id, absent success/dryRun, null/empty inputs, count coercion | 8 | #100 | — |
| CQ-8-2 | S | gap | done | api/serialization | `SerializationParityTest`: null-optional `TradeRecord` + null-offset `SyncProgressResponse` round-trips | 8 | #100 | — |
| CQ-7-L1 | L | bug | done | rate-limit | RateLimiter holds Mutex across `delay` (HOL blocking) | 7 | #93 | — |
| CQ-7-L2 | L | bug | done | modes | DynamicKraken unpinned reads outside `withStableBackend` | 7 | #93 | — |
| CQ-7-1 | M | gap | done | algorithm | USD+crypto both trigger → no fiat-correction path | 7 | #90 | — |
| CQ-7-2 | S | gap | done | algorithm | Pct at trigger + USD below dust → no orders | 7 | #90 | — |
| CQ-7-3 | S | gap | done | modes | simulation=true + placeholder keys still syncs | 7 | #90 | — |
| CQ-7-4 | M | bug | done | history | Dry-run excluded from reconstruction; case-insensitive reverse-apply side | 7 | #90 | — |
| CQ-7-5 | S | gap | done | dedupe | Pair-alias volume Δ >1% must not dedupe | 7 | #90 | — |
| CQ-7-6 | S | gap | done | frontend-js | `dynamicNumber` ISO/`Date` parse branch | 7 | #90 | — |
| CQ-7-7 | S | gap | done | frontend-js | Allocation total tolerance edges + invalid symbol alert | 7 | #90 | — |
| CQ-5-1 | M | harness | done | build | Filtered `--tests` runs no longer fail project-wide JaCoCo gates; full runs still verify | 6 | [#79](https://github.com/HyperVon/new-kraken-rebalancer/pull/79) | — |
| CQ-5-2 | S | harness | done | skills | One Gradle build per clone (worktree or parent-owns-build); concurrent builds cause `EOFException` / false `UP-TO-DATE` | 6 | [#79](https://github.com/HyperVon/new-kraken-rebalancer/pull/79) | — |
| CQ-5-3 | S | harness | done | skills | Final verification must force re-execution (`--rerun-tasks`) and check JUnit XML counts | 6 | [#79](https://github.com/HyperVon/new-kraken-rebalancer/pull/79) | — |
| CQ-5-4 | S | gap | done | history | Remove redundant double-lookup Elvis in snapshot seeding; other defensive branches kept by design | 6 | [#79](https://github.com/HyperVon/new-kraken-rebalancer/pull/79) | — |
| CQ-5-5 | S | harness | done | docs | improvement-backlog: 35 completed rows moved out of the Open section | 6 | [#79](https://github.com/HyperVon/new-kraken-rebalancer/pull/79) | — |
| CQ-3-14 | M | gap | done | history/repo | Lift `TradeHistoryServiceImpl` + `repository.impl` branch coverage (overall ~95%) | 5 | [#78](https://github.com/HyperVon/new-kraken-rebalancer/pull/78) | — |
| CQ-3-26 | L | bug | done | fiat | Skip `$0.00` fiat-correction shares; cap sum ≤ `\|usdDev\|` via truncated budget | 5 | #78 | — |
| CQ-3-28 | M | gap | done | eval | Scenario 32 — multi-cycle convergence with fill feedback, zero orders by cycle 3 | 5 | [#78](https://github.com/HyperVon/new-kraken-rebalancer/pull/78) | — |
| CQ-3-9 | S | gap | done | history | Reconstruct failure is best-effort: throttle window still opens, no extra Kraken calls | 5 | [#77](https://github.com/HyperVon/new-kraken-rebalancer/pull/77) | — |
| CQ-3-17 | M | gap | done | eval | Scenario 31 — USD refresh ≥95% early-accept + fail-closed buys | 5 | [#77](https://github.com/HyperVon/new-kraken-rebalancer/pull/77) | — |
| CQ-3-18 | S | gap | done | drawdown | Aggressive exponent `0.5` ALGORITHM table points | 5 | [#77](https://github.com/HyperVon/new-kraken-rebalancer/pull/77) | — |
| CQ-3-20 | M | gap | done | history/SSE | Real `snapshotFlow` multi-subscriber + `DROP_OLDEST` non-blocking producer | 5 | [#77](https://github.com/HyperVon/new-kraken-rebalancer/pull/77) | — |
| CQ-3-22 | M | gap | done | rate-limit | Public ticker/OHLC never `acquire`; private heavy paths cost `2.0` (injectable limiter) | 5 | [#77](https://github.com/HyperVon/new-kraken-rebalancer/pull/77) | — |
| CQ-3-5 | M | gap | done | flows | `collectLatest` config emit mid-`delay` cancels and restarts loop with new settings | 4 | [#75](https://github.com/HyperVon/new-kraken-rebalancer/pull/75) | — |
| CQ-3-6 | S | gap | done | lockout | 9 consecutive `Temporary lockout` exhausts `maxLockoutAttempts` and throws | 4 | [#75](https://github.com/HyperVon/new-kraken-rebalancer/pull/75) | — |
| CQ-3-7 | S | gap | done | modes | `simulation=true` + `dryRun=true`: DynamicKraken → sim; dry-run does not mutate balances | 4 | [#75](https://github.com/HyperVon/new-kraken-rebalancer/pull/75) | — |
| CQ-3-8 | S | gap | done | dedupe | API_FILL then LOCAL_ESTIMATE deletes local; same fee-rate pair kept | 4 | [#75](https://github.com/HyperVon/new-kraken-rebalancer/pull/75) | — |
| CQ-3-10 | S | gap | done | DynamicKraken | Nested/reentrant `withStableBackend` (pinDepth > 0) | 4 | [#75](https://github.com/HyperVon/new-kraken-rebalancer/pull/75) | — |
| CQ-3-11 | S | gap | done | DashboardController | History stats with no `range` → no-arg `getHistoryStats()` | 4 | [#75](https://github.com/HyperVon/new-kraken-rebalancer/pull/75) | — |
| CQ-3-12 | S | gap | done | dedupe | Null-id skip / null `idToDelete` when deleting unsettled | 4 | [#75](https://github.com/HyperVon/new-kraken-rebalancer/pull/75) | — |
| CQ-3-13 | S | gap | done | ConfigService | Reject invalid allocation symbol (`SYMBOL_PATTERN`) | 4 | [#75](https://github.com/HyperVon/new-kraken-rebalancer/pull/75) | — |
| CQ-3-15 | S | gap | done | orders | Failed buy must not reduce cycle 99% budget for subsequent buys | 4 | [#75](https://github.com/HyperVon/new-kraken-rebalancer/pull/75) | — |
| CQ-3-16 | S | gap | done | math | Underweight exact dust `\|dev\|==threshold` significant; just-below not | 4 | #75 | — |
| CQ-3-19 | S | bug | done | flows | Rethrow `CancellationException` in cycle/sync `catch (Exception)` | 4 | [#75](https://github.com/HyperVon/new-kraken-rebalancer/pull/75) | — |
| CQ-3-21 | S | gap | done | dedupe | Fee-rate Δ exactly `0.001`; local-estimate window `10_000` vs `10_001` ms | 4 | [#75](https://github.com/HyperVon/new-kraken-rebalancer/pull/75) | — |
| CQ-3-24 | S | gap | done | orders | Buy trimmed by remaining budget below dust → skip; budget never negative | 4 | [#75](https://github.com/HyperVon/new-kraken-rebalancer/pull/75) | — |
| CQ-3-25 | M | gap | done | manager | Post-trade snapshot fallback: `Result.Failure` + thrown branch | 4 | [#75](https://github.com/HyperVon/new-kraken-rebalancer/pull/75) | — |
| CQ-3-1 | S | gap | done | orders | USD refresh early-accept at ≥95% of projected stops polling | 3 | [#73](https://github.com/HyperVon/new-kraken-rebalancer/pull/73) | — |
| CQ-3-2 | S | gap | done | orders | Below 95% keeps polling; later ≥95% accepts early | 3 | [#73](https://github.com/HyperVon/new-kraken-rebalancer/pull/73) | — |
| CQ-3-3 | S | gap | done | dedupe | 5min window: `diff == 300_000` still duplicates; `> 300_000` does not | 3 | [#73](https://github.com/HyperVon/new-kraken-rebalancer/pull/73) | — |
| CQ-3-4 | S | gap | done | analyzer | Explicit zero ticker price aborts (not only missing key) | 3 | [#73](https://github.com/HyperVon/new-kraken-rebalancer/pull/73) | — |
| CQ-3-23 | L | bug | done | orders | Skip zero/negative-value orders (`dustThresholdUSD=0` / budget-trimmed `$0`) | 3 | [#73](https://github.com/HyperVon/new-kraken-rebalancer/pull/73) | — |
| CQ-1-10 | L | bug | done | simulation | Pin live/sim backend across `executeOrders` via `withStableBackend` | 2 | [#71](https://github.com/HyperVon/new-kraken-rebalancer/pull/71) | — |
| CQ-1-11 | L | bug | done | analyzer | Exact USD pair-alias ticker match (no substring `contains`) | 2 | [#71](https://github.com/HyperVon/new-kraken-rebalancer/pull/71) | — |
| CQ-1-9 | M | harness | done | rate-limit | Injectable `RateLimiter` clock + deterministic decay test | 2 | [#71](https://github.com/HyperVon/new-kraken-rebalancer/pull/71) | — |
| CQ-1-4 | S | gap | done | orders | Dry-run buy budget uses projected cash (no USD refresh) | 2 | [#71](https://github.com/HyperVon/new-kraken-rebalancer/pull/71) | — |
| CQ-1-8 | S | bug | done | fiat | Fiat-correction shares use `toUsdScale()` | 2 | [#71](https://github.com/HyperVon/new-kraken-rebalancer/pull/71) | — |
| CQ-1-1 | S | gap | done | rate-limit | Assert throttle path leaves counter ≈ `safeLimit`; subsequent acquire behavior | 1 | [#70](https://github.com/HyperVon/new-kraken-rebalancer/pull/70) | — |
| CQ-1-2 | M | gap | done | analyzer | Document `contains()` first-match collision for ticker fallback (test only) | 1 | [#70](https://github.com/HyperVon/new-kraken-rebalancer/pull/70) | — |
| CQ-1-3 | S | gap | done | ATH | New-ATH path when `save` throws still returns drawdown without crashing | 1 | [#70](https://github.com/HyperVon/new-kraken-rebalancer/pull/70) | — |
| CQ-1-5 | S | gap | done | dedupe | Pair-alias fee mismatch; both API fills → later id; side mismatch non-dup | 1 | [#70](https://github.com/HyperVon/new-kraken-rebalancer/pull/70) | — |
| CQ-1-6 | S | gap | done | drawdown | MaxDD saturation / over-MaxDD coerce → 100% fiat deploy (exponents 1 & 2) | 1 | [#70](https://github.com/HyperVon/new-kraken-rebalancer/pull/70) | — |
| CQ-1-7 | S | gap | done | docs | ALGORITHM / AGENTS / skill: USD refresh backoff is 250→500→1000ms (not 32s cap) | 1 | [#70](https://github.com/HyperVon/new-kraken-rebalancer/pull/70) | — |
| CQ-1-12 | S | gap | done | dust | Exact dust `>=` boundary significant; just below not | 1 | [#70](https://github.com/HyperVon/new-kraken-rebalancer/pull/70) | — |
| CQ-1-13 | S | gap | done | orders | Executor dust: sell `==` threshold executes; `threshold - ε` skips | 1 | [#70](https://github.com/HyperVon/new-kraken-rebalancer/pull/70) | — |
| CQ-1-14 | M | gap | done | history | SELL reverse-apply + OHLC closest price + negative/missing balance clamp | 1 | [#70](https://github.com/HyperVon/new-kraken-rebalancer/pull/70) | — |
| CQ-1-15 | M | gap | done | trigger | Deviation **exactly** at trigger fires; just below does not | 1 | [#70](https://github.com/HyperVon/new-kraken-rebalancer/pull/70) | — |

## How

1. After discovery: append new rows (`Status=open`, IDs like `CQ-1-1`).
2. When starting work: set `in_progress` and note the branch.
3. When shipping: move to **Done** with PR number; close linked GitHub issues.
4. When gating L: keep `deferred` until user approves; create/link Issue column.
5. Do not delete historical deferred L rows unless the user drops them.
6. Do not re-add items already `done` / `deferred` unless verifying they still apply.
