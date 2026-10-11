# Current Behavior Verification

This guide maps the focused tests that cover the current product contracts. The
former 41-scenario suite and simulation cases for seeded historical snapshots,
trade imports, retrospective comparison, and report persistence were retired;
they are not a current test report or a description of emulator behavior.

## Current contract coverage

| Contract | Focused tests |
| --- | --- |
| Fixed configured USD target, deviation triggers, fiat correction, precision, and order planning | [`RebalancerEngineTest`](../engine/src/test/kotlin/com/gemini/krakenbot/domain/RebalancerEngineTest.kt), [`PortfolioCalculationsTest`](../engine/src/test/kotlin/com/gemini/krakenbot/domain/PortfolioCalculationsTest.kt), [`PortfolioManagerFiatCorrectionTest`](../backend/src/test/kotlin/com/gemini/krakenbot/service/PortfolioManagerFiatCorrectionTest.kt), [`PortfolioManagerZeroAllocationTest`](../backend/src/test/kotlin/com/gemini/krakenbot/service/PortfolioManagerZeroAllocationTest.kt) |
| Sell-first execution, settled cash limits, and live submission safety | [`PortfolioManagerOrderExecutionTest`](../backend/src/test/kotlin/com/gemini/krakenbot/service/PortfolioManagerOrderExecutionTest.kt), [`OrderExecutorFillSettlementTest`](../backend/src/test/kotlin/com/gemini/krakenbot/service/impl/OrderExecutorFillSettlementTest.kt), [`OrderExecutorCashCapTest`](../backend/src/test/kotlin/com/gemini/krakenbot/service/impl/OrderExecutorCashCapTest.kt), [`OrderExecutorSubmissionSafetyTest`](../backend/src/test/kotlin/com/gemini/krakenbot/service/impl/OrderExecutorSubmissionSafetyTest.kt), [`ExecutionJournalCutoverTest`](../backend/src/test/kotlin/com/gemini/krakenbot/service/impl/ExecutionJournalCutoverTest.kt) |
| Latest current-cycle Dashboard snapshot, `/history` redirect, retired API responses, health/readiness, and SSE | [`DashboardCutoverApiTest`](../backend/src/test/kotlin/com/gemini/krakenbot/controller/DashboardCutoverApiTest.kt) |
| Actual direct observations, incomplete evidence, account/scope continuity, and page rendering | [`DashboardActualPageTest`](../backend/src/test/kotlin/com/gemini/krakenbot/controller/DashboardActualPageTest.kt), [`ActualObservationDispatcherTest`](../backend/src/test/kotlin/com/gemini/krakenbot/service/actual/ActualObservationDispatcherTest.kt), [`ActualObservationStoreTest`](../backend/src/test/kotlin/com/gemini/krakenbot/service/actual/ActualObservationStoreTest.kt), [`ActualObservationValuatorTest`](../backend/src/test/kotlin/com/gemini/krakenbot/service/actual/ActualObservationValuatorTest.kt) |
| Prospective Buy & Hold comparison only after event continuity is verified | [`ProspectiveBenchmarkServiceTest`](../backend/src/test/kotlin/com/gemini/krakenbot/service/actual/benchmark/ProspectiveBenchmarkServiceTest.kt), [`ProspectiveBenchmarkCalculatorTest`](../backend/src/test/kotlin/com/gemini/krakenbot/service/actual/benchmark/ProspectiveBenchmarkCalculatorTest.kt), [`ProspectiveEventContinuityVerifierTest`](../backend/src/test/kotlin/com/gemini/krakenbot/service/actual/benchmark/ProspectiveEventContinuityVerifierTest.kt) |
| Active Settings, config hot reload, and dropping retired fields | [`ConfigServiceTest`](../backend/src/test/kotlin/com/gemini/krakenbot/service/ConfigServiceTest.kt), [`ConfigServiceNestedSessionTest`](../backend/src/test/kotlin/com/gemini/krakenbot/service/ConfigServiceNestedSessionTest.kt), [`SettingsFormComponentTest`](../backend/src/test/kotlin/com/gemini/krakenbot/view/component/SettingsFormComponentTest.kt) |
| Safe legacy account-history continuity, schema migrations, and journal binding | [`AccountHistoryContinuityVerifierTest`](../backend/src/test/kotlin/com/gemini/krakenbot/service/impl/history/AccountHistoryContinuityVerifierTest.kt), [`AccountHistoryScopeGuardTest`](../backend/src/test/kotlin/com/gemini/krakenbot/service/impl/history/AccountHistoryScopeGuardTest.kt), [`SchemaMigrationsTest`](../backend/src/test/kotlin/com/gemini/krakenbot/config/SchemaMigrationsTest.kt) |
| Offline emulator and mode routing, without history seeding | [`SimulatedKrakenServiceTest`](../backend/src/test/kotlin/com/gemini/krakenbot/service/SimulatedKrakenServiceTest.kt), [`DynamicKrakenServiceTest`](../backend/src/test/kotlin/com/gemini/krakenbot/service/DynamicKrakenServiceTest.kt) |

These links identify test coverage; they do not report a recent test run.

## Data and migration boundaries

Test database setup isolates the legacy, execution, and Actual stores with
in-memory or temporary paths. The legacy `kraken-rebalancer.db` file and its
existing data/schema remain available for safe execution-journal migration and
account-history continuity checks during binding. It is not an active reporting
destination. Current startup does not synchronize historical trades or ledgers,
reconstruct reports, or write retrospective report rows; existing files and
tables are left untouched. The execution journal and Actual observation store
remain separate active persistence paths.

Simulation uses the offline exchange emulator for current cycles. It does not
seed retrospective snapshots, trades, or reports, and it does not produce
authenticated Actual observations.

## Product behavior to keep covered

- A cycle plans against configured asset weights and its fixed USD target.
  Historical ATH/drawdown fields and comparison data do not change the plan.
- The Dashboard displays the latest completed in-memory cycle snapshot and its
  current-cycle actions. SSE publishes that snapshot; it is not a persisted
  timeline.
- `/history` permanently redirects to `/actual`; former `/api/history/*`
  reporting endpoints return **410 Gone**.
- Actual presents forward-only raw observations and retains incomplete samples
  as gaps. Its optional Buy & Hold comparison starts from an eligible complete
  observation after continuity verification and uses only forward observations.
- Saving Settings retains active controls and removes retired drawdown,
  deployment, inception, and comparison-start keys from the saved JSON.
