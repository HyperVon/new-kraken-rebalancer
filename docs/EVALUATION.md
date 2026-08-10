# Scenario Evaluation Suite

The Kraken Rebalancer includes a production-grade **Scenario Evaluation Suite** designed to verify the correctness, performance, safety, and resilience of the rebalancer under 39 highly realistic market scenarios and operational conditions.

Implemented in [EvaluationScenariosTest.kt](../src/test/kotlin/com/gemini/krakenbot/EvaluationScenariosTest.kt), this suite is run as part of the standard Gradle test task. It dynamically evaluates the system without making external network calls, using a highly precise in-process fake exchange client ([FakeKrakenService.kt](../src/test/kotlin/com/gemini/krakenbot/service/FakeKrakenService.kt)).

A complementary suite, [SimulationEvaluationScenariosTest.kt](../src/test/kotlin/com/gemini/krakenbot/SimulationEvaluationScenariosTest.kt), exercises the production [SimulatedKrakenService](../src/main/kotlin/com/gemini/krakenbot/service/impl/SimulatedKrakenService.kt) emulator with real TradeHistory + in-memory SQLite (invariant assertions; it uses a fixed seeded trade pattern while balances and prices drift randomly). See the architecture section below for the six named cases.

---

## Running the Suite

You can run the full evaluation suite locally with Gradle:

```bash
./gradlew test --tests "com.gemini.krakenbot.EvaluationScenariosTest"
./gradlew test --tests "com.gemini.krakenbot.SimulationEvaluationScenariosTest"
```

Upon completion, the suite automatically compiles and updates a detailed report showing status and evidence at `build/reports/scenarios_evaluation_report.md`.

---

## Test Suite Architecture & Design

To guarantee robust, reliable, and side-effect-free testing in a public GitHub repository, the suite adheres to the following principles:

- **No Hardcoded Absolute Paths**: All file operations write to relative directories (e.g. `build/`) or platform-independent temp paths. System overrides can be set using environment variables (e.g. `SCENARIOS_REPORT_PATH`).
- **Fake Exchange Mocking**: Leverages `FakeKrakenService` to inject controlled API responses (balances, prices, order execution failures) rather than using brittle or timing-dependent `coEvery` mocks.
- **Coroutines & Virtual Time**: Uses Kotlin Coroutines' `runTest` to instantly advance delays and test rebalancing loops without blocking thread execution. Flow-based tests for the two hot `SharedFlow` channels — config changes (`ConfigService._configFlow`) and snapshot broadcasts (`TradeHistorySnapshotStore.snapshotFlow`, exposed via the `TradeHistoryService` façade `getHistoryFlow()`) — use `advanceUntilIdle()` with `@OptIn(ExperimentalCoroutinesApi::class)`. See [`docs/FLOWS.md`](FLOWS.md).
- **SSE Stream Broadcast Checks**: Dynamically spins up local Ktor client/server test applications to verify high-concurrency multi-subscriber Server-Sent Events (SSE) streaming.

### SimulationEvaluationScenariosTest cases

These are **invariant** assertions against the production emulator, which uses a
fixed seeded trade pattern while balances and prices drift randomly, not
FakeKraken exact-math cases:

| Case | Intent |
| :--- | :--- |
| sim cold start seeds historical snapshots when DB empty | Empty DB → seeded history |
| sim cold start comparison reconciles seeded fills | Seeded fills → reconciled comparison |
| sim rebalance cycle persists snapshot and trades with cycleId | Cycle persistence + `cycleId` |
| sim sync imports emulator trade history | Emulator history import path |
| sim multi-cycle keeps portfolio value positive | Multi-cycle value stays positive |
| sim addSnapshot emits on history flow | SnapshotStore / façade flow emit |

---

## Scenarios & Outcomes

Below is the report of the current 39 scenarios run by the suite and their results.
The latest run recorded 39/39 PASS. Generated order-client UUIDs are omitted
from this stable summary; refresh it from
`build/reports/scenarios_evaluation_report.md` after suite changes and redact
absolute paths or temporary identifiers.

| Scenario | Description | Status | Details / Evidence |
| :--- | :--- | :--- | :--- |
| Scenario 1 | Standard Rebalancing Sequence (Phase 3 Sequencing & Projected Cash) | 🟢 **PASS** | Sub-case 1 (Sequencing): Sell first, then Buy is true (Log: sell XBTUSD volume=0.15000000 -> buy ETHUSD volume=1.25000000)<br>Sub-case 2 (Capped buy on failed sell): Buy order capped at 0.495 ETH is true (Log: buy ETHUSD volume=0.49500000) |
| Scenario 2 | Dynamic Drawdown-Based Fiat Deployment | 🟢 **PASS** | ATH Saved: 10000.00<br>Case 20% Drawdown: Deployment Pct = 100.0000%, Effective USD Target = 0.00000%, Crypto Scale Factor = 1.25000000<br>Case 10% Drawdown: Deployment Pct = 25.0000%, Effective USD Target = 15.00000%, Crypto Scale Factor = 1.06250000 |
| Scenario 3 | Intelligent Fiat Correction (Deposit/Withdrawal) | 🟢 **PASS** | Sub-case A (Deposit Fiat Correction): true (Orders: 2 orders generated)<br>Sub-case B (Withdrawal Fiat Correction): true |
| Scenario 4 | Live Dashboard & Settings Flow Publication | 🟢 **PASS** | GET Dashboard Shell returns 200 OK & Kraken Rebalancer<br>POST settings updates configuration and publishes the new settings on a replaying hot flow<br>POST invalid settings fails with allocation verification exception<br>SSE stream receives the persisted snapshot and a replayed hot-flow snapshot update |
| Scenario 5 | Safety and Resilience (Dry Run & Cycle Failure Propagation) | 🟢 **PASS** | Sub-case A (Dry Run Mode): true (Actions: [Deviation: BTC 20%, Deviation: USD -20%, [DRY RUN] SELL BTC Volume: 0.02 Value: $1000.00])<br>Sub-case B (Minimum Order Size): true (Trades executed: 0)<br>Sub-case C (Network Failure propagated out of cycle to loop boundary): true<br>Sub-case D (Price Lookup Failure aborts cycle): true |
| Scenario 6 | Zero Target Allocation (Total Liquidation) | 🟢 **PASS** | One XBTUSD market sell generated at volume 0.50000000; generated client-order UUID omitted. |
| Scenario 7 | Kraken Symbol Mapping Quirks (DOGE/BTC) | 🟢 **PASS** | Queried pairs: XDGUSD,XBTUSD<br>DOGE buy: XDGUSD volume 30000.00000000<br>BTC buy: XBTUSD volume 0.06000000; generated client-order UUIDs omitted. |
| Scenario 8 | Concurrent Multi-Asset Rebalance with Slippage | 🟢 **PASS** | Sell XBTUSD volume 0.34400000, then buy ETHUSD volume 3.96000000; generated client-order UUIDs omitted. |
| Scenario 9 | Run Loop Lifecycle & Timing | 🟢 **PASS** | Loop started successfully.<br>Executed cycles count: 3 (expected >= 2)<br>Loop stopped cleanly when stopRebalancingLoop() was called. |
| Scenario 10 | Portfolio Stats Database Failure Resilience | 🟢 **PASS** | Portfolio stats table was removed from the in-memory database.<br>Stats save failed with the database-write IOException: true<br>Failure message: Database write failed |
| Scenario 11 | Configuration Validation Edge Cases | 🟢 **PASS** | Invalid loop delay exception: Loop delay must be a positive integer.<br>Invalid deviation exception: Deviation trigger percent must be non-negative.<br>Invalid drawdown exception: Fiat max drawdown must be between 0% and 100%.<br>Invalid total percent exception: Total allocation percentage must be exactly 100%. Current sum: 90.0<br>Missing USD exception: One asset must be USD. |
| Scenario 12 | Precision and Rounding Tolerances | 🟢 **PASS** | Precise inputs: USD=1.00000001, BTC=0.00000001 @ $48523.97<br>Portfolio total rounded once to $1.00; BTC snapshot value rounded to $0.00<br>Dry-run BTC buy volume: 0.00001030 (8-decimal order precision) |
| Scenario 13 | High Volatility Slippage Capping | 🟢 **PASS** | XBTUSD sell volume 0.01120000; ETHUSD buy volume 0.17325000; expected and actual ETH volume match. Generated client-order UUIDs omitted. |
| Scenario 14 | Config File Hot-Reload via `loadConfig()` | 🟢 **PASS** | Initial loop delay: 60s<br>Modified config loop delay on disk: 120s<br>Config service reloaded via `loadConfig()` (no filesystem watcher): true |
| Scenario 15 | Single Asset Dominance (Extreme Rebalance) | 🟢 **PASS** | Total balance $1000; 99% BTC target; XBTUSD buy volume 0.01980000 succeeded in dry-run mode. Generated client-order UUID omitted. |
| Scenario 16 | Trade History Storage and JSON Serialization | 🟢 **PASS** | Saved and loaded one history snapshot; parsed totals value=$12345.67 at 2026-06-20T12:00:00Z. *(Title retained to match `EvaluationScenariosTest`; persistence is SQLite, not a JSON file.)* |
| Scenario 17 | Partial Kraken API Failure (Individual Endpoint Failures) | 🟢 **PASS** | Prices API call threw IOException as expected.<br>Rebalance cycle aborted cleanly.<br>Executed orders count: 0 (expected 0) |
| Scenario 18 | Ktor SSE Keep-Alive and Broadcast Resilience | 🟢 **PASS** | SSE client connected to the hot replaying flow and received three snapshots sequentially: SNAP1, SNAP2, SNAP3. |
| Scenario 19 | Extremely Large Portfolio Allocation Scaling | 🟢 **PASS** | Portfolio configured with 15 assets.<br>Sum of allocations: 100.0%<br>Configuration validated successfully: true |
| Scenario 20 | Missing or Corrupt Stats File Recovery | 🟢 **PASS** | Malformed JSON failed closed with IOException; missing stats recovered with ATH 0; new stats saved and verified. |
| Scenario 21 | Perfect Allocation Alignment (No Trades) | 🟢 **PASS** | Total balance: 1.0 BTC ($1000) and $1000 USD.<br>Executed orders count: 0<br>Snapshot actions: []<br>No trades executed: true |
| Scenario 22 | Order Failure Logging & Snapshot Mapping | 🟢 **PASS** | Target: buy 0.01 BTC ($500).<br>Order result mocked to fail with 'Insufficient funds'.<br>Captured actions in history snapshot: [Deviation: BTC -100%, Deviation: USD 100%, FAILED BUY BTC: Insufficient funds]<br>Error successfully logged in snapshot: true |
| Scenario 23 | Complete Authentication API Failure | 🟢 **PASS** | Balances API call threw: EAPI:Invalid key or signature<br>Executed orders count: 0<br>Rebalance cycle aborted safely: true |
| Scenario 24 | Config File Writer Failure Protection | 🟢 **PASS** | Config file path replaced by directory: .../scenario24-*.json<br>Update config threw RuntimeException as expected: true (Msg: Failed to save configuration) |
| Scenario 25 | Minimum Order Size Rejection Recovery | 🟢 **PASS** | XBTUSD buy volume 0.00600000 was rejected for minimum size; ETHUSD buy volume 0.15000000 succeeded; history logged both outcomes. Generated client-order UUIDs omitted. |
| Scenario 26 | Pure Cash Injection (No Sells, Only Buys) | 🟢 **PASS** | One XBTUSD buy volume 0.50000000; no sells; generated client-order UUID omitted. |
| Scenario 27 | Concurrency of Multiple SSE Listeners | 🟢 **PASS** | Five clients connected to the hot SSE flow and all five received the broadcast snapshot. |
| Scenario 28 | Zero Balance Division by Zero Prevention | 🟢 **PASS** | Zero balances supplied for BTC and USD.<br>Executed orders count: 0<br>Rebalance cycle terminated safely: true |
| Scenario 29 | Extremely Large Minimum Order Size | 🟢 **PASS** | Fiat correction distributed as $180.00; BTC and ETH dust buys of $90.00 each were skipped; no orders executed. |
| Scenario 30 | Exponent Curve Calibration for Fiat Deployment | 🟢 **PASS** | At 10.0000% drawdown: deployment 25.0000%, effective USD target 15.00000%, adjusted BTC target 85.00%. |
| Scenario 31 | USD Refresh Early-Accept and Fail-Closed Buys | 🟢 **PASS** | Sub-case A (early-accept ≥95%): pollsPass=true buyPass=true<br>Sub-case B (fail-closed abort buys): pollsPass=true noBuysPass=true |
| Scenario 32 | Multi-Cycle Convergence with Fill Feedback | 🟢 **PASS** | Start: BTC=0.18 @ $50000, ETH=0.50 @ $2000, USD=$0; targets=50%/40%/10%<br>99% partial-buy fills fed back into balances<br>Post-cycle max \|deviation\|: [3.00, 0.03, 0.03]<br>Executed orders per cycle: [2, 1, 0]<br>Total value per cycle: [10000.00, 10000.00, 10000.00] |
| Scenario 33 | Drawdown Deployment Changes Order Sizes | 🟢 **PASS** | With $8000 all-cash and 40/40/20 targets, control buys were BTC 0.06400000 and ETH 1.60000000; at 20% drawdown they increased to BTC 0.08000000 and ETH 1.96000000. |
| Scenario 34 | Zero-Target Liquidation Never Exceeds Holdings | 🟢 **PASS** | BTC holding 0.00000001 at $500000; rounded liquidation intent $0.01; submitted sell volume stayed at 0.00000001. |
| Scenario 35 | Complete Liquidation of Zero-Target Position | 🟢 **PASS** | Zero-target BTC holding=0.1 ($100 USD > $10 dust) generates full liquidation sell order |
| Scenario 36 | retryWithFlow handles 429/503 and lockout | 🟢 **PASS** | Rate-limit and lockout retry cases each succeeded on the second attempt; virtual time advanced 20000ms. |
| Scenario 37 | withStableBackend pins config across rebalance | 🟢 **PASS** | Config values were 100 outside and inside the pinned block, then 999 afterward; pinning verified. |
| Scenario 38 | Ledgers sync recovery uses 96d bound not full history | 🟢 **PASS** | Interrupted ledgers recovery completed from the 96-day bound with a null prior watermark. |
| Scenario 39 | PENDING→UNCERTAIN batch abort via cl_ord_id | 🟢 **PASS** | Two intents were created; the second became UNCERTAIN, the remaining batch was not attempted, and the next live cycle was blocked until reconciliation. Generated client-order UUIDs omitted. |
