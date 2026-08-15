# Scenario Evaluation Suite

The Kraken Rebalancer includes a production-grade **Scenario Evaluation Suite** designed to verify the correctness, performance, safety, and resilience of the rebalancer under 41 highly realistic market scenarios and operational conditions.

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

Below is the report of the current 41 scenarios run by the suite and their results.
The latest run recorded 41/41 PASS. Generated order-client UUIDs are omitted
from this stable summary; refresh it from
`build/reports/scenarios_evaluation_report.md` after suite changes and redact
absolute paths or temporary identifiers.

| Scenario | Name | Status | Key Evidence / Invariants |
| :--- | :--- | :---: | :--- |
| Scenario 1 | Standard Rebalancing Sequence (Phase 3 Sequencing & Projected Cash) | 🟢 **PASS** | Sell XBTUSD volume 0.34400000, then buy ETHUSD volume 3.96000000; generated client-order UUIDs omitted. |
| Scenario 2 | Dynamic Drawdown-Based Fiat Deployment | 🟢 **PASS** | Base USD target was 20.00%, effective USD target became 10.00%, effective BTC target became 45.00%, and effective ETH target became 45.00%.<br>Orders generated: 2<br>Generated client-order UUIDs omitted. |
| Scenario 3 | Intelligent Fiat Correction (Deposit/Withdrawal) | 🟢 **PASS** | Cash injection distributed across BTC ($4500.00) and ETH ($4500.00); 2 buy orders; generated client-order UUIDs omitted. |
| Scenario 4 | Live Dashboard & Settings Flow Publication | 🟢 **PASS** | Initial broadcast received snapshot total USD $10000.00.<br>Settings updated to loopDelaySeconds=15, deviationTriggerPercent=2.0%.<br>Next loop cycle completed using updated settings. |
| Scenario 5 | Safety and Resilience (Dry Run & Cycle Failure Propagation) | 🟢 **PASS** | Dry-run orders count: 2 (no exchange orders submitted).<br>Zero price test aborted safely: 0 orders submitted. |
| Scenario 6 | Zero Target Allocation (Total Liquidation) | 🟢 **PASS** | ETH target percent was 0.00%.<br>Liquidated ETH volume 5.00000000; 1 sell order; generated client-order UUID omitted. |
| Scenario 7 | Kraken Symbol Mapping Quirks (DOGE/BTC) | 🟢 **PASS** | Correctly mapped DOGE (XDG) and BTC (XXBT) pairs.<br>Executed 2 orders; generated client-order UUIDs omitted. |
| Scenario 8 | Concurrent Multi-Asset Rebalance with Slippage | 🟢 **PASS** | Sell XBTUSD volume 0.34400000, then buy ETHUSD volume 3.96000000; generated client-order UUIDs omitted. |
| Scenario 9 | Extreme Market Volatility (Massive Price Swings) | 🟢 **PASS** | BTC surged to $100,000; ETH crashed to $1,000.<br>Rebalancer successfully stabilized portfolio without negative cash or infinite loops.<br>Generated client-order UUIDs omitted. |
| Scenario 10 | Complete Kraken API Outage (Graceful Degradation) | 🟢 **PASS** | Kraken API returned HTTP 500 across all endpoints.<br>Rebalancer logged failure and aborted cycle cleanly without crashing the daemon. |
| Scenario 11 | Partial Kraken Rate Limiting (Exponential Backoff & Jitter) | 🟢 **PASS** | Kraken returned `EAPI:Rate limit exceeded`.<br>Rebalancer backed off, retried, and completed execution once limits recovered. |
| Scenario 12 | Precision & Micro-Dust Order Filtering | 🟢 **PASS** | Portfolio deviation of $0.45 fell below $5.00 minimum order size.<br>No dust orders were executed. |
| Scenario 13 | Rapid Consecutive Deposit/Withdrawal Shocks | 🟢 **PASS** | Successive fiat shocks of +$50,000 then -$20,000 handled gracefully across consecutive rebalance ticks. |
| Scenario 14 | Rapid Settings Mutation During Active Rebalance Loop | 🟢 **PASS** | Loop delay and deviation triggers mutated concurrently while execution engine was running without race conditions. |
| Scenario 15 | Single Asset Dominance (Extreme Rebalance) | 🟢 **PASS** | One asset represented 99% of total portfolio value.<br>Order sequencing and projected cash correctly executed 1 sell and 2 buys; generated client-order UUIDs omitted. |
| Scenario 16 | Trade History Storage and JSON Serialization | 🟢 **PASS** | Successfully logged 3 executed trades, verified deterministic UUIDs, and persisted valid JSON to storage. |
| Scenario 17 | Partial Kraken API Failure (Individual Endpoint Failures) | 🟢 **PASS** | Ticker endpoint failed while Balance endpoint succeeded.<br>Rebalancer safely aborted cycle before placing any orders. |
| Scenario 18 | Ktor SSE Keep-Alive and Broadcast Resilience | 🟢 **PASS** | Verified that SSE stream maintained active keep-alive comments and broadcast new snapshots without dropping clients. |
| Scenario 19 | Extremely Large Portfolio Allocation Scaling | 🟢 **PASS** | Handled 100 configured asset allocations without precision breakdown or UI serialization bottlenecks. |
| Scenario 20 | Missing or Corrupt Stats File Recovery | 🟢 **PASS** | Corrupted stats JSON file on disk was detected, quarantined, and safely re-initialized from scratch. |
| Scenario 21 | Perfect Allocation Alignment (No Trades) | 🟢 **PASS** | Zero deviation across all configured assets.<br>Cycle completed in <5ms with 0 executed orders. |
| Scenario 22 | Order Failure Logging & Snapshot Mapping | 🟢 **PASS** | Simulated order rejection by exchange; failure reason logged and mapped into `TradeRecord` and snapshot actions. |
| Scenario 23 | Complete Authentication API Failure | 🟢 **PASS** | Invalid API keys (`EAPI:Invalid key`); safely caught, logged, and cycle aborted with zero state corruption. |
| Scenario 24 | Config File Writer Failure Protection | 🟢 **PASS** | Read-only config file prevented write; atomic write-then-rename prevented half-written file corruption. |
| Scenario 25 | Minimum Order Size Rejection Recovery | 🟢 **PASS** | Exchange rejected order as below minimum volume; caught cleanly and next cycle proceeded normally. |
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
| Scenario 36 | retryWithFlow handles 429/503 and lockout | 🟢 **PASS** | Rate-limit, lockout, and HTTP 503 retry cases each succeeded on the second attempt; virtual time advanced 30000ms. |
| Scenario 37 | withStableBackend pins config across rebalance | 🟢 **PASS** | Config values were 100 outside and inside the pinned block, then 999 afterward; pinning verified. |
| Scenario 38 | Ledgers sync recovery uses 96d bound not full history | 🟢 **PASS** | Interrupted ledgers recovery completed from the 96-day bound with a null prior watermark. |
| Scenario 39 | PENDING→UNCERTAIN batch abort via cl_ord_id | 🟢 **PASS** | Two local PENDING submission guards were created; the second became UNCERTAIN, the remaining batch was not attempted, and the next live cycle was blocked until reconciliation. Generated client-order UUIDs omitted. |
| Scenario 40 | Zero Total Portfolio Value / 100% Drawdown | 🟢 **PASS** | Zero balances handled without exception; 0 orders verified. |
| Scenario 41 | Zero/negative fiat deployment exponent and normalized staking rewards | 🟢 **PASS** | Exponent values ≤ 0 deploy $0.00 fiat on positive drawdown. |
