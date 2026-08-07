# Anchored Summary — CQ-18 Verification → Implementation

## Goal

Implement tests-first fixes for the re-verified real CQ-18 findings on branch `quality/cycle-20260807-0517`: production bugs CQ-18-4 and CQ-18-6, the test-coverage gap CQ-18-11, and the approved L item CQ-18-9. CQ-18-1/2/8 re-checked and are no-defect (by-design count-paginator / persisted-watermark / shared-counter patterns, NOT implemented).

## Constraints & Preferences

- L-sized items that change live order behavior require explicit user approval (continuous-quality skill, impact override); the user approved proceeding with CQ-18-9 in this session.
- Tests first: failing regression test, then minimal production fix; no weakening coverage gates.
- JVM: Kotest `:memory:`, `IsolationMode.InstancePerTest`, `shouldBeEqualComparingTo`; BigDecimal crypto scale 8 / USD scale 2.
- JS: `frontend-js/src/jsTest/kotlin/` test files; karma/istanbul browser tests.
- Quality gates: JVM 95%/90%, JS 90%/75%, Spotless 120, `allWarningsAsErrors`.
- No subagent delegation for the review; parent-owned serial Gradle.
- CQ-18-9 was implemented according to issue #212's approved behavior: non-cancellation save failures warn and continue with the cycle's in-memory ATH.

## Progress

### Done

- Cycle 18 discovery (routed fan-out, 4 tracks, all passed; run 20260807T092109Z-a0d7f7b6).
- Accuracy re-verification of all 13 CQ-18 findings against source.
- Parent-only full review of the codebase (no new defects outside CQ-18).
- Implemented CQ-18-9 after the user's explicit approval: warn+continue on non-cancellation ATH save failure, preserving in-memory ATH and cancellation propagation.
- Updated `.agents/quality-backlog.md`: corrected CQ-18-2 wording, dropped 9 review-rejected/no-defect items (CQ-18-1/2/3/5/7/8/10/12/13), and marked CQ-18-4/6/9/11 done.
- `npx markdownlint-cli .agents/quality-backlog.md` and `git diff --check` pass; the full changed-document lint is the remaining gate.
- Verified line references for every finding against current source.
- Added regression tests and minimal production fixes for CQ-18-4/6/9, plus test-only coverage for CQ-18-11; focused ATH JVM tests pass.

### In Progress

- None (CQ-18 implementation, documentation synchronization, and final quality gates are complete).

### Blocked

- None. CQ-18-9's live-cycle control gate was explicitly approved by the user and implemented with focused regression tests.

## Key Decisions

- Implement ONLY the real open issues: CQ-18-4 (production), CQ-18-6 (production), CQ-18-9 (approved production), CQ-18-11 (test-only).
- CQ-18-1, CQ-18-2, CQ-18-8 are dropped as no-defect (re-verified against source) — do NOT change code; retain the dropped records for traceability.
- CQ-18-9 is done: non-cancellation save failures warn and continue with the in-memory ATH; database loads and cancellation remain fail-closed.
- Dropped findings retained as rejected (not silently deleted) for traceability.
- Fix sites anchored by file:line for each item (see Relevant Files).

## Next Steps

- Full `./gradlew build jacocoTestCoverageVerification --rerun-tasks` passed with serial Gradle execution.
- Complete repository Markdown lint and `git diff --check` passed; inspect the final worktree boundary before handoff.
- Commit and push only after explicit user request; this session's request has been completed.

## Critical Context

- Repository root; main at `bcdf4e6`.
- Production roots: `src/main/kotlin/com/gemini/krakenbot/` (JVM) and `frontend-js/src/jsMain/kotlin/`; tests `src/test/kotlin/com/gemini/krakenbot/` + `frontend-js/src/jsTest/kotlin/` (14 files).
- CQ-18-1: trade paging trusts Kraken `totalCount` (capped 1000) at `TradeHistorySyncService.kt:373,383-388`; ledger count (`:210-212`) is reliable — keep ledger count-trust.
- CQ-18-2: throttle `lastSyncTime` is process-local at `TradeHistorySyncService.kt:36,64-69,311` (same in LedgersSyncService `:36/108/149`); persisted watermark (`finalizeSync :291-312`, `calculateEffectiveLatestTime :140-144`) bounds window but throttle resets on restart. (NO-DEFECT re-confirmed.)
- CQ-18-4: `findClosest` min-abs-distance incl. future at `SnapshotHistoryCalculator.kt:233-249`; consumed by `getPriceForTimestamp` for OHLC (`SnapshotHistoryCalculator.kt:212,222`) and used for `DailyCloseEvent` pricing (`SnapshotHistoryCalculator.kt:58-66`) via the `nowMs` interval (DailyCloseEvent, interval=1440); `TradeHistoryReconstructionService.kt:106-123` builds daily OHLC. Fix = floor to prior candle, not nearest-abs.
- CQ-18-8: shared `AtomicInteger` pagination counters in `DynamicKrakenService.kt:44,47` (setters `:82/89/106`, getters `:96/110`); consumers `TradeHistorySyncService.kt:373`, `OrderExecutorImpl.kt:453-482` (`sumMatchedSellProceeds`), `LedgersSyncService.kt:200/213`. (NO-DEFECT re-confirmed — by-design per trade-history-sync skill.)
- CQ-18-6: `TradeHistoryReconstructionService.kt:146-151` calls `SnapshotHistoryCalculator.buildTimelineEvents` WITHOUT passing `now`; default `Instant.now()` at `SnapshotHistoryCalculator.kt:50`; `nowProvider`/`reconstructionNow` at `TradeHistoryReconstructionService.kt:27/:49`. `TradeHistoryServiceImpl.kt:35` has `syncNowProvider: () -> Instant = Instant::now` and wraps `TradeHistoryReconstructionService`.
- CQ-18-9 current behavior: `PortfolioAnalyzerImpl.kt:67-99` rethrows `CancellationException` but warns and continues after other ATH save failures using the selected in-memory ATH; `PortfolioManagerImpl.kt:250-252` remains unchanged, so the persistence policy has one owner. Issue #212 is implemented.
- CQ-18-11: exact `±0.01` allocation boundary at `Settings.kt:28-48`; `Settings.kt:40` uses `abs(total - PrecisionConstants.TOTAL_ALLOCATION_PERCENTAGE) <= PrecisionConstants.ALLOCATION_TOLERANCE_DELTA`. The added test records actual V8 behavior: 100.00, 100.01, and 99.99 are accepted; 100.02 is rejected.
- Test base: `TradeHistoryServiceTestBase` provides `repository`, `ledgerRepository`, `krakenService` (relaxed + `stubWithStableBackend`), `configService`, `portfolioAnalyzer` mocks and `createService()`; `TradeHistoryReconstructionTest` extends it. `SnapshotHistoryCalculatorTest` uses `:memory:` + its own cleanup.
- Handoff state: CQ-18 implementation/tests/docs and the router EOL blacklist are committed and pushed; no runtime state, credentials, or databases are included.

## Verdict Table (re-verified vs current source)

| ID | Finding | Status | Implement? |
| :--- | :--- | :--- | :--- |
| CQ-18-1 | Paginated count trusts totalCount | M | Dropped (review rejected/no-defect; count-paginator by design; `TradeHistorySyncService.kt:362-391`, `DynamicKrakenService.kt:44/96`, `KrakenServiceImpl.kt:321`) |
| CQ-18-2 | Throttle resets on restart, repeats window | M | Dropped (review rejected/no-defect; process-local throttle + persisted watermark bound window; `:36/64-69/:140-144/:291-312`) |
| CQ-18-3 | Events after newest snapshot excluded | S | Dropped (intentional; `TradeHistoryQueryServiceTest:34-50`) |
| CQ-18-4 | `findClosest` min-abs includes future OHLC | M | **YES** (production) — `SnapshotHistoryCalculator.kt:233-249` used `:212/:222` / `:58-66` / `TradeHistoryReconstructionService:106-123` |
| CQ-18-5 | Dividend payout surface (RebalancerComparison) | S | Dropped (verified contract: `LedgerEvent.kt:6-17`, `ALGORITHM.md:291-304`, `FLOWS.md:370-390`) |
| CQ-18-6 | Injected `now` not propagated to timeline | S | **YES** (production) — `TradeHistoryReconstructionService.kt:147-151` omits `now=`; `SnapshotHistoryCalculator.kt:50` defaults `Instant.now()` (:27/:49 have `nowProvider`/`reconstructionNow`) |
| CQ-18-7 | Paginator count under concurrency | S | Dropped (not observed in source) |
| CQ-18-8 | Shared AtomicInteger paginator counters | M | Dropped (review rejected/no-defect; by-design per trade-history-sync skill; `:44/96`, `OrderExecutorImpl.kt:453-482`) |
| CQ-18-9 | ATH persist failure aborts cycle | L | **YES** — done after explicit user approval; issue #212 behavior implemented |
| CQ-18-10 | Ledger dedupe alias collision | S | Dropped (no defect) |
| CQ-18-11 | Settings allocation ±0.01 boundary | S | Test-only — prod logic correct (`Settings.kt:40` uses `<= ALLOCATION_TOLERANCE_DELTA` = 0.01 @ `PrecisionConstants.kt:37`); missing exact boundary cases in `SettingsTest` |
| CQ-18-12 | OHLC weekend gap semantics | S | Dropped |
| CQ-18-13 | SSOT duplicate source | S | Dropped |

## Relevant Files

- `src/main/kotlin/com/gemini/krakenbot/service/impl/PortfolioAnalyzerImpl.kt` — CQ-18-9:67-99; non-cancellation save failures warn and continue, cancellation propagates.
- `src/test/kotlin/com/gemini/krakenbot/service/impl/PortfolioAnalyzerImplTest.kt` — focused CQ-18-9 stale-ATH and cancellation tests.
- `src/test/kotlin/com/gemini/krakenbot/service/PortfolioManagerEdgeCasesTest.kt` — CQ-18-9 stale-ATH, new-high, and cancellation edge cases.
- `src/main/kotlin/com/gemini/krakenbot/service/impl/history/TradeHistorySyncService.kt` — CQ-18-1:373/383-388, CQ-18-2:36/64-69/311
- `src/main/kotlin/com/gemini/krakenbot/service/impl/history/SnapshotHistoryCalculator.kt` — CQ-18-4:212/222/233-249, CQ-18-6:50; existing tests `SnapshotHistoryCalculatorTest.kt` (`:memory:`, `buildTimelineEvents` :36 with `now`, equidistant :205, floor :302)
- `src/main/kotlin/com/gemini/krakenbot/service/impl/history/TradeHistoryReconstructionService.kt` — CQ-18-6:27/49/106-123/146-151
- `src/main/kotlin/com/gemini/krakenbot/service/impl/TradeHistoryServiceImpl.kt` — wraps `TradeHistoryReconstructionService`; `syncNowProvider :35`
- `src/main/kotlin/com/gemini/krakenbot/service/impl/history/TradeHistoryServiceTestBase.kt` — mocks (`repository`, `ledgerRepository`, `krakenService`, `configService`, `portfolioAnalyzer`) + `createService()` + `stubWithStableBackend`
- `src/test/kotlin/com/gemini/krakenbot/service/impl/history/TradeHistoryReconstructionTest.kt` — extends `TradeHistoryServiceTestBase`; target for CQ-18-6 test
- `src/main/kotlin/com/gemini/krakenbot/service/impl/DynamicKrakenService.kt` — CQ-18-8:44/47/82/89/96/106/110
- `src/main/kotlin/com/gemini/krakenbot/service/impl/OrderExecutorImpl.kt` — CQ-18-8:453-482
- `frontend-js/src/jsMain/kotlin/Settings.kt` — CQ-18-11:28-48
- `frontend-js/src/jsTest/kotlin/SettingsTest.kt` — CQ-18-11 test target (`updateAllocationTotal validates totals and USD allocation` :27, boundary cases :83-132)
- `src/commonMain/kotlin/com/gemini/krakenbot/PrecisionConstants.kt` — `SCALE_USD=2`, `TOTAL_ALLOCATION_PERCENTAGE=100.0`, `ALLOCATION_TOLERANCE_DELTA=0.01` (:37)
- `.agents/quality-backlog.md` — CQ-18 verdict table and statuses
- `.agents/skills/continuous-quality/SKILL.md` — size/classification and L-gating rules
- `.agents/skills/code-review/SKILL.md` — review invariants (SRP, money scales, flow/SSE, modes)
- `docs/ALGORITHM.md` (291-304) and `docs/FLOWS.md` (370-390) — dividend contract (intentional exclusion)

(End of file)
