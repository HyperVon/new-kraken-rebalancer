# Current Behavior Verification

This guide maps the focused tests that cover the current product contracts. It
identifies coverage areas; it is not a record of a particular test run.

## Current contract coverage

| Contract | Focused tests |
| --- | --- |
| Fixed configured USD percentage target, deviation triggers, fiat correction, precision, and order planning | [`RebalancerEngineTest`](../engine/src/test/kotlin/com/gemini/krakenbot/domain/RebalancerEngineTest.kt), [`PortfolioCalculationsTest`](../engine/src/test/kotlin/com/gemini/krakenbot/domain/PortfolioCalculationsTest.kt), [`PortfolioManagerFiatCorrectionTest`](../backend/src/test/kotlin/com/gemini/krakenbot/service/PortfolioManagerFiatCorrectionTest.kt), [`PortfolioManagerZeroAllocationTest`](../backend/src/test/kotlin/com/gemini/krakenbot/service/PortfolioManagerZeroAllocationTest.kt) |
| Sell-first execution, settled cash limits, and live submission safety | [`PortfolioManagerOrderExecutionTest`](../backend/src/test/kotlin/com/gemini/krakenbot/service/PortfolioManagerOrderExecutionTest.kt), [`OrderExecutorFillSettlementTest`](../backend/src/test/kotlin/com/gemini/krakenbot/service/impl/OrderExecutorFillSettlementTest.kt), [`OrderExecutorCashCapTest`](../backend/src/test/kotlin/com/gemini/krakenbot/service/impl/OrderExecutorCashCapTest.kt), [`OrderExecutorSubmissionSafetyTest`](../backend/src/test/kotlin/com/gemini/krakenbot/service/impl/OrderExecutorSubmissionSafetyTest.kt) |
| Current-cycle Dashboard snapshot, SSE, health, and readiness | [`DashboardCutoverApiTest`](../backend/src/test/kotlin/com/gemini/krakenbot/controller/DashboardCutoverApiTest.kt), [`CurrentPortfolioStateTest`](../backend/src/test/kotlin/com/gemini/krakenbot/service/CurrentPortfolioStateTest.kt) |
| History routes, filters, page rendering, and mutation protection | [`DashboardHistoryPageTest`](../backend/src/test/kotlin/com/gemini/krakenbot/controller/DashboardHistoryPageTest.kt), [`HistoryPageComponentTest`](../backend/src/test/kotlin/com/gemini/krakenbot/view/component/HistoryPageComponentTest.kt), [`DashboardMutationRoutesTest`](../backend/src/test/kotlin/com/gemini/krakenbot/controller/DashboardMutationRoutesTest.kt) |
| Direct cycle observations, incomplete evidence, and value classification | [`HistoryObservationRecorderTest`](../backend/src/test/kotlin/com/gemini/krakenbot/service/history/HistoryObservationRecorderTest.kt), [`HistoryObservationValuatorTest`](../backend/src/test/kotlin/com/gemini/krakenbot/service/history/HistoryObservationValuatorTest.kt), [`HistoryModelsTest`](../backend/src/test/kotlin/com/gemini/krakenbot/service/history/HistoryModelsTest.kt) |
| Bounded Kraken trade/ledger synchronization, account scope, cursor recovery, and storage | [`HistorySynchronizerTest`](../backend/src/test/kotlin/com/gemini/krakenbot/service/history/HistorySynchronizerTest.kt), [`HistoryServiceTest`](../backend/src/test/kotlin/com/gemini/krakenbot/service/history/HistoryServiceTest.kt), [`SqliteHistoryStoreTest`](../backend/src/test/kotlin/com/gemini/krakenbot/service/history/SqliteHistoryStoreTest.kt), [`HistoryStoreCodecTest`](../backend/src/test/kotlin/com/gemini/krakenbot/service/history/HistoryStoreCodecTest.kt), [`HistoryDatabaseTest`](../backend/src/test/kotlin/com/gemini/krakenbot/service/history/HistoryDatabaseTest.kt) |
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

## Product behavior to keep covered

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
- Saving Settings retains active controls and removes retired drawdown,
  deployment, inception, and comparison-start keys from the saved JSON.
