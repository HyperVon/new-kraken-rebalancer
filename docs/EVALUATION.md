# Current Behavior Verification

This guide maps focused tests to current product contracts. It identifies
coverage areas; it is not a record of a particular test run.

## Current contract coverage

| Contract | Focused tests |
| --- | --- |
| Fixed configured USD target, deviation triggers, fiat correction, precision, and planning | RebalancerEngineTest, PortfolioCalculationsTest, PortfolioManagerFiatCorrectionTest, PortfolioManagerZeroAllocationTest |
| Sell-first execution, settled cash limits, and live submission safety | PortfolioManagerOrderExecutionTest, OrderExecutorFillSettlementTest, OrderExecutorCashCapTest, OrderExecutorSubmissionSafetyTest |
| Current-cycle Dashboard snapshot, revision invalidation and legacy SSE compatibility, health, readiness, and in-memory activity | DashboardCutoverApiTest, SseMultiSubscriberTest, CurrentPortfolioStateTest, RecentActivityComponentTest |
| Active Settings, configuration validation, and hot reload | ConfigServiceTest, ConfigServiceNestedSessionTest, SettingsFormComponentTest |
| Kraken signing, balance/fill parsing, retries, and endpoint rate costs | KrakenServiceTest, KrakenParsersTest, KrakenTradeFillParserTest, KrakenRetryAndRateLimitTest |
| Exchange metadata validation before trading | PortfolioAnalyzerImplTest |
| Data-directory initialization and account binding | DataDirectoryConfigTest, DataDirectoryIdentityTest, ExecutionDatabaseLifecycleTest |
| Offline exchange simulation and live/simulation routing | SimulatedKrakenServiceTest, DynamicKrakenServiceTest |
| Dashboard and Settings browser interactions | DashboardTest, SettingsTest, MainTest |

## Storage and safety boundary

The application has one database: execution.db. It stores durable live-order
intents and account-binding evidence. This journal must survive restarts so
ambiguous AddOrder outcomes cannot be retried as new orders. It is not needed
for portfolio valuation or allocation math, but real live order submission
requires a valid file-backed journal. Tests use isolated in-memory or temporary
SQLite paths.

Kraken TradesHistory remains an exchange read used to confirm fills belonging
to the current sell settlement. The application does not synchronize account
trades or ledgers for retrospective reports or persist portfolio snapshots.

## Product behavior to keep covered

- A cycle keeps the configured USD percentage fixed and recalculates its dollar
  target from the current portfolio total. Retired settings do not change the
  plan.
- The Dashboard shows the latest completed in-memory snapshot, its current-cycle
  activity, and recent process-local cycle outcomes. Its revision-only SSE
  refresh leaves `/api/status/stream` snapshot JSON and activity-cycle-ID
  payloads unchanged for compatibility.
- A live sell settlement matches Kraken fills by order transaction ID, caps
  confirmed proceeds to observed spendable USD, and stops buys when safe cash
  cannot be confirmed. OrderSettleHelperTest covers absent and zero spendable USD;
  KrakenParsersTest rejects malformed balances and required held-amount fields.
- ExecutionDatabaseLifecycleTest checks that closing an in-memory journal releases
  its keeper connection, including after a second initialization fails.
- SettingsTest keeps deferred mode polling active after a later save fails, while
  a newer acknowledged save still supersedes the previous refresh.
- An ambiguous live AddOrder response is persisted before submission becomes
  uncertain; later live orders remain blocked until operator resolution.
- Saving Settings preserves active controls and removes retired settings fields
  from the saved JSON.
