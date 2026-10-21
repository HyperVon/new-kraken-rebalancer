# Current Behavior Verification

This guide maps the focused tests that cover the current product contracts. It
identifies coverage areas; it is not a record of a particular test run.

## Current contract coverage

| Contract | Focused tests |
| --- | --- |
| Fixed configured USD percentage target, deviation triggers, fiat correction, precision, and order planning | [`RebalancerEngineTest`](../engine/src/test/kotlin/com/gemini/krakenbot/domain/RebalancerEngineTest.kt), [`PortfolioCalculationsTest`](../engine/src/test/kotlin/com/gemini/krakenbot/domain/PortfolioCalculationsTest.kt), [`PortfolioManagerFiatCorrectionTest`](../backend/src/test/kotlin/com/gemini/krakenbot/service/PortfolioManagerFiatCorrectionTest.kt), [`PortfolioManagerZeroAllocationTest`](../backend/src/test/kotlin/com/gemini/krakenbot/service/PortfolioManagerZeroAllocationTest.kt) |
| Sell-first execution, settled cash limits, and live submission safety | [`PortfolioManagerOrderExecutionTest`](../backend/src/test/kotlin/com/gemini/krakenbot/service/PortfolioManagerOrderExecutionTest.kt), [`OrderExecutorFillSettlementTest`](../backend/src/test/kotlin/com/gemini/krakenbot/service/impl/OrderExecutorFillSettlementTest.kt), [`OrderExecutorCashCapTest`](../backend/src/test/kotlin/com/gemini/krakenbot/service/impl/OrderExecutorCashCapTest.kt), [`OrderExecutorSubmissionSafetyTest`](../backend/src/test/kotlin/com/gemini/krakenbot/service/impl/OrderExecutorSubmissionSafetyTest.kt) |
| Current-cycle Dashboard snapshot, SSE, health, readiness, and retained activity | [`DashboardCutoverApiTest`](../backend/src/test/kotlin/com/gemini/krakenbot/controller/DashboardCutoverApiTest.kt), [`CurrentPortfolioStateTest`](../backend/src/test/kotlin/com/gemini/krakenbot/service/CurrentPortfolioStateTest.kt), [`RecentActivityComponentTest`](../backend/src/test/kotlin/com/gemini/krakenbot/view/component/RecentActivityComponentTest.kt) |
| History routes, filters, page rendering, and mutation protection | [`DashboardHistoryPageTest`](../backend/src/test/kotlin/com/gemini/krakenbot/controller/DashboardHistoryPageTest.kt), [`HistoryPageComponentTest`](../backend/src/test/kotlin/com/gemini/krakenbot/view/component/HistoryPageComponentTest.kt), [`DashboardMutationRoutesTest`](../backend/src/test/kotlin/com/gemini/krakenbot/controller/DashboardMutationRoutesTest.kt) |
| Direct cycle observations, incomplete evidence, and value classification | [`HistoryObservationRecorderTest`](../backend/src/test/kotlin/com/gemini/krakenbot/service/history/HistoryObservationRecorderTest.kt), [`HistoryObservationValuatorTest`](../backend/src/test/kotlin/com/gemini/krakenbot/service/history/HistoryObservationValuatorTest.kt), [`HistoryModelsTest`](../backend/src/test/kotlin/com/gemini/krakenbot/service/history/HistoryModelsTest.kt) |
| Automatic/urgent History sync, durable backfill, account scope, cursor recovery, and storage | [`HistorySyncCoordinatorTest`](../backend/src/test/kotlin/com/gemini/krakenbot/service/history/HistorySyncCoordinatorTest.kt), [`HistorySynchronizerTest`](../backend/src/test/kotlin/com/gemini/krakenbot/service/history/HistorySynchronizerTest.kt), [`HistoryServiceTest`](../backend/src/test/kotlin/com/gemini/krakenbot/service/history/HistoryServiceTest.kt), [`SqliteHistoryStoreTest`](../backend/src/test/kotlin/com/gemini/krakenbot/service/history/SqliteHistoryStoreTest.kt), [`SqliteHistoryStoreSyncMetadataTest`](../backend/src/test/kotlin/com/gemini/krakenbot/service/history/SqliteHistoryStoreSyncMetadataTest.kt), [`HistoryStoreCodecTest`](../backend/src/test/kotlin/com/gemini/krakenbot/service/history/HistoryStoreCodecTest.kt), [`HistoryDatabaseTest`](../backend/src/test/kotlin/com/gemini/krakenbot/service/history/HistoryDatabaseTest.kt) |
| Range-aware History metrics, native-asset series, API dates/scopes, and export accuracy | [`HistoryReportingServiceTest`](../backend/src/test/kotlin/com/gemini/krakenbot/service/reporting/HistoryReportingServiceTest.kt), [`HistoryApiPresenterTest`](../backend/src/test/kotlin/com/gemini/krakenbot/service/history/HistoryApiPresenterTest.kt), [`HistoryApiSeriesBuilderTest`](../backend/src/test/kotlin/com/gemini/krakenbot/service/history/HistoryApiSeriesBuilderTest.kt), [`HistoryRecordedAccountChartsTest`](../backend/src/test/kotlin/com/gemini/krakenbot/service/history/HistoryRecordedAccountChartsTest.kt), [`HistoryCsvExporterTest`](../backend/src/test/kotlin/com/gemini/krakenbot/service/history/HistoryCsvExporterTest.kt), [`HistoryReadApiRoutesTest`](../backend/src/test/kotlin/com/gemini/krakenbot/controller/HistoryReadApiRoutesTest.kt), [`HistoryPageComponentTest`](../backend/src/test/kotlin/com/gemini/krakenbot/view/component/HistoryPageComponentTest.kt) |
| History client refresh, selected-range state, chart gestures, and named views | [`HistoryRefreshTest`](../frontend-js/src/jsTest/kotlin/HistoryRefreshTest.kt), [`HistoryRangeTest`](../frontend-js/src/jsTest/kotlin/HistoryRangeTest.kt), [`HistoryZoomTest`](../frontend-js/src/jsTest/kotlin/HistoryZoomTest.kt), [`HistoryChartRefreshTest`](../frontend-js/src/jsTest/kotlin/HistoryChartRefreshTest.kt), [`HistoryInteractionTest`](../frontend-js/src/jsTest/kotlin/HistoryInteractionTest.kt), [`HistoryViewsTest`](../frontend-js/src/jsTest/kotlin/HistoryViewsTest.kt) |
| Kraken history parsing and order-specific sell fill confirmation | [`KrakenParsersTest`](../backend/src/test/kotlin/com/gemini/krakenbot/service/impl/KrakenParsersTest.kt), [`KrakenTradeHistoryTest`](../backend/src/test/kotlin/com/gemini/krakenbot/service/KrakenTradeHistoryTest.kt), [`OrderExecutorFillSettlementTest`](../backend/src/test/kotlin/com/gemini/krakenbot/service/impl/OrderExecutorFillSettlementTest.kt) |
| Fresh data-directory lifecycle and account binding | [`DataDirectoryConfigTest`](../backend/src/test/kotlin/com/gemini/krakenbot/config/DataDirectoryConfigTest.kt), [`DataDirectoryIdentityTest`](../backend/src/test/kotlin/com/gemini/krakenbot/config/DataDirectoryIdentityTest.kt), [`ExecutionDatabaseLifecycleTest`](../backend/src/test/kotlin/com/gemini/krakenbot/config/ExecutionDatabaseLifecycleTest.kt) |
| Active Settings, config hot reload, and dropping retired settings fields | [`ConfigServiceTest`](../backend/src/test/kotlin/com/gemini/krakenbot/service/ConfigServiceTest.kt), [`ConfigServiceNestedSessionTest`](../backend/src/test/kotlin/com/gemini/krakenbot/service/ConfigServiceNestedSessionTest.kt), [`SettingsFormComponentTest`](../backend/src/test/kotlin/com/gemini/krakenbot/view/component/SettingsFormComponentTest.kt) |
| Offline emulator and mode routing | [`SimulatedKrakenServiceTest`](../backend/src/test/kotlin/com/gemini/krakenbot/service/SimulatedKrakenServiceTest.kt), [`DynamicKrakenServiceTest`](../backend/src/test/kotlin/com/gemini/krakenbot/service/DynamicKrakenServiceTest.kt) |

## Data and initialization boundaries

The application uses separate `execution.db` and `history.db` files under the
configured data directory. Fresh directories are initialized automatically at
startup; a mixed directory holding only one store fails closed instead of
recreating a missing file. The execution journal identity marker and the shared
`account-identity.sha256` file must remain with the databases. Tests use
in-memory or temporary SQLite paths and do not touch operator data.

History records directly observed cycle values and account-scoped Kraken trade
and ledger pages. It does not reconstruct a full past portfolio from ledger
events, fill observation gaps, or calculate a Buy & Hold comparison. Simulation
records values in a separate process-session scope and does not synchronize
account trades or ledgers.

The fake-account browser fixture seeds multiple scopes, thousands of direct
observations, exchange records, activity outcomes, range boundaries, and delayed
late events. It is separate from unit-test databases and never reads operator
credentials or stores.

## Product behavior to keep covered

- Opening a current History database for reading does not reserve the writer
  lock. The database regression holds an uncommitted write, reads the last
  committed value through another database instance, then verifies the writer
  can commit. Existing schema-upgrade and rollback checks remain in force.
- A key-restricted backfill completes the permitted range without repeated
  downloads, preserves its intended end across pages and restarts, and imports
  missing history when either key bound widens. Coordinator regressions cover
  upper-bound removal and recovery of previously truncated completed jobs.
- A cycle keeps the configured USD percentage fixed and recalculates its dollar
  target from the current portfolio total. Retired ATH/drawdown fields do not
  change the plan.
- The Dashboard displays the latest completed in-memory cycle snapshot and its
  current-cycle actions. SSE publishes that snapshot; the separate History page
  reads persisted observations and synchronized exchange records.
- Account trade and ledger sync uses bounded pages, a committed cursor, and a
  verified account identity. A page failure or scope change cannot silently
  merge data from another account.
- History value samples preserve incomplete evidence and do not infer missing
  values or periods. Oversized charts sample the full time range within the
  point cap while retaining bucket endpoints, extrema, and incomplete-cycle
  boundaries. Simulation values are scoped separately from account data.
- The allocation deviation has one definition for the rendered page, the chart
  API, and the summary: percentage points against the configured targets, a
  target the portfolio does not hold worth zero, and no deviation for an
  unknown holding value or an incomplete sample.
- Sync coverage is a durable union of scanned intervals: a gap between two
  covered intervals leaves a range unsynchronized, and a range without a start
  bound needs coverage that reaches its end.
- History ledger rows show the canonical asset name and settle their fee in the
  same asset, and a record without a fee shows no fee at all.
- Saving Settings retains active controls and removes retired drawdown,
  deployment, inception, and comparison-start keys from the saved JSON.
