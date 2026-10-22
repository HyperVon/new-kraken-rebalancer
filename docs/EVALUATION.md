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
| Daily account valuation, fee-aware balance replay, candle fallback, USD-at-par validation, atomic publication, equity exclusion, and v4 migration | [`HistoryDailyValuationTest`](../backend/src/test/kotlin/com/gemini/krakenbot/service/history/HistoryDailyValuationTest.kt), [`HistoryValuationCoordinatorTest`](../backend/src/test/kotlin/com/gemini/krakenbot/service/history/HistoryValuationCoordinatorTest.kt), [`HistoryArchiveAcceptanceTest`](../backend/src/test/kotlin/com/gemini/krakenbot/service/history/HistoryArchiveAcceptanceTest.kt), [`SqliteHistoryStoreTest`](../backend/src/test/kotlin/com/gemini/krakenbot/service/history/SqliteHistoryStoreTest.kt), [`HistoryApiPresenterTest`](../backend/src/test/kotlin/com/gemini/krakenbot/service/history/HistoryApiPresenterTest.kt), [`KrakenServiceTest`](../backend/src/test/kotlin/com/gemini/krakenbot/service/KrakenServiceTest.kt) |
| Configured-asset metadata fails closed before live price analysis; no ticker-name equity guesses | [`PortfolioAnalyzerImplTest`](../backend/src/test/kotlin/com/gemini/krakenbot/service/impl/PortfolioAnalyzerImplTest.kt) |
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

History retains direct cycle observations and account-scoped Kraken trade and
ledger pages. A reporting-only valuation worker reuses imported ledger
checkpoints to establish daily account balances, prices nonzero included assets
with completed same-day candles, and publishes a complete total only when every
required balance and price is supported. It does not fill gaps or calculate
investment returns or Buy & Hold comparisons. All equity-class shares are
excluded while all USD ledger balance changes remain cash. Simulation values remain in a
separate process-session scope and do not synchronize authenticated account
trades or ledgers.

The fake-account browser fixture seeds multiple scopes, thousands of direct
observations, exchange records, activity outcomes, range boundaries, and delayed
late events. It is separate from unit-test databases and never reads operator
credentials or stores.

## Product behavior to keep covered

- The real History page, 24-hour page, and concurrent chart, summary, trade,
  ledger, and bot-activity API requests succeed while a file-backed SQLite
  writer holds an uncommitted update. The browser-fixture integration test
  verifies committed-value visibility, subsequent writer commit, and preserved
  event counts. Paginated event reads retain a deferred snapshot; daily-value
  APIs and exports materialize one bounded snapshot containing totals, holdings,
  and valuation status. Write transactions still reserve the writer before
  reading.
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
- Account-history valuation tests preserve the raw imported record counts across
  the v4-to-v5 migration, evaluate a frozen 365-day UTC target with offline
  public-candle fixtures, omit all equity-class shares, and keep missing price or
  balance evidence as dated gaps. The private archive runner is opt-in through
  `HISTORY_RESTORATION_COPY_DB` and `HISTORY_RESTORATION_PUBLIC_PRICE_FIXTURE`;
  it accepts only disposable database files under `/tmp`.
- Price resolver tests distinguish an explicit unknown-pair response from
  network and rate-limit failures, verify same-day conversion provenance, and
  retain incomplete candle evidence as a gap. The coordinator repairs cached
  USD away from par and verifies the repaired cache after reopening SQLite.
- Balance replay tests keep future unsupported evidence from blocking earlier
  dates, retain untimed evidence as a global blocker, and recover only after a
  later valid anchor.
- Holdings-client tests resolve out-of-order success and failure callbacks by
  the latest selected date and scope; delegated click handling remains
  idempotent after repeated HTMX swaps.
- Trading analyzer tests require explicit `currency` metadata for configured
  non-USD assets and stop before ticker requests when classification is missing,
  unknown, or non-currency.
- Saving Settings retains active controls and removes retired drawdown,
  deployment, inception, and comparison-start keys from the saved JSON.
