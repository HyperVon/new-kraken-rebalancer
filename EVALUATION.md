# Scenario Evaluation Suite

The Kraken Rebalancer includes a production-grade **Scenario Evaluation Suite** designed to verify the correctness, performance, safety, and resilience of the rebalancer under 30 highly realistic market scenarios and operational conditions.

Implemented in [EvaluationScenariosTest.kt](src/test/kotlin/com/gemini/krakenbot/EvaluationScenariosTest.kt), this suite is run as part of the standard Gradle test task. It dynamically evaluates the system without making external network calls, using a highly precise in-process fake exchange client ([FakeKrakenService.kt](src/test/kotlin/com/gemini/krakenbot/service/FakeKrakenService.kt)).

---

## Running the Suite

You can run the full evaluation suite locally with Gradle:

```bash
./gradlew test --tests "com.gemini.krakenbot.EvaluationScenariosTest"
```

Upon completion, the suite automatically compiles and updates a detailed report showing status and evidence at `build/reports/scenarios_evaluation_report.md`.

---

## Test Suite Architecture & Design

To guarantee robust, reliable, and side-effect-free testing in a public GitHub repository, the suite adheres to the following principles:
- **No Hardcoded Absolute Paths**: All file operations write to relative directories (e.g. `build/`) or platform-independent temp paths. System overrides can be set using environment variables (e.g. `SCENARIOS_REPORT_PATH`).
- **Fake Exchange Mocking**: Leverages `FakeKrakenService` to inject controlled API responses (balances, prices, order execution failures) rather than using brittle or timing-dependent `coEvery` mocks.
- **Coroutines & Virtual Time**: Uses Kotlin Coroutines' `runTest` to instantly advance delays and test rebalancing loops without blocking thread execution.
- **SSE Stream Broadcast Checks**: Dynamically spins up local Ktor client/server test applications to verify high-concurrency multi-subscriber Server-Sent Events (SSE) streaming.

---

## Scenarios & Outcomes

Below is the report of the current 30 scenarios run by the suite and their results:

| Scenario | Description | Status | Details / Evidence |
| :--- | :--- | :--- | :--- |
| Scenario 1 | Standard Rebalancing Sequence (Phase 3 Sequencing & Projected Cash) | 🟢 **PASS** | Sub-case 1 (Sequencing): Sell first, then Buy is true (Log: sell XBTUSD volume=0.15000000 -> buy ETHUSD volume=1.25000000)<br>Sub-case 2 (Capped buy on failed sell): Buy order capped at 0.495 ETH is true (Log: buy ETHUSD volume=0.49500000) |
| Scenario 2 | Dynamic Drawdown-Based Fiat Deployment | 🟢 **PASS** | ATH Saved: 10000.00<br>Case 20% Drawdown: Deployment Pct = 100.0%, Effective USD Target = 0.00000%, Crypto Scale Factor = 1.25000000<br>Case 10% Drawdown: Deployment Pct = 25.0%, Effective USD Target = 15.00000%, Crypto Scale Factor = 1.06250000 |
| Scenario 3 | Intelligent Fiat Correction (Deposit/Withdrawal) | 🟢 **PASS** | Sub-case A (Deposit Fiat Correction): true (Orders: 2 orders generated)<br>Sub-case B (Withdrawal Fiat Correction): true |
| Scenario 4 | Live Dashboard & Config Hot-Reload | 🟢 **PASS** | GET Dashboard Shell returns 200 OK & Kraken Rebalancer<br>POST settings updates configuration safely and redirects via HX-Redirect header<br>POST invalid settings fails with allocation verification exception<br>SSE stream successfully broadcasts snapshot payload updates to HTMX clients |
| Scenario 5 | Safety and Resilience (Dry Run & Error Recovery) | 🟢 **PASS** | Sub-case A (Dry Run Mode): true (Actions: [Deviation Triggered details: BTC Dev: 20.0000%, Deviation Triggered details: USD Dev: 20.0000%, [DRY RUN] SELL BTC Volume: 0.02000000 Value: $1000.0000000])<br>Sub-case B (Dust Threshold): true (Trades executed: 0)<br>Sub-case C (Network Failure caught): true<br>Sub-case D (Price Lookup Failure aborts cycle): true |
| Scenario 6 | Zero Target Allocation (Total Liquidation) | 🟢 **PASS** | Trades: 1 generated. Details: [OrderCall(pair=XBTUSD, type=market, side=sell, volume=0.50000000)] |
| Scenario 7 | Kraken Symbol Mapping Quirks (DOGE & BTC Mapping) | 🟢 **PASS** | Queried pairs: XDGUSD,XBTUSD<br>DOGE buy order: OrderCall(pair=XDGUSD, type=market, side=buy, volume=30000.00000000)<br>BTC buy order: OrderCall(pair=XBTUSD, type=market, side=buy, volume=0.06000000) |
| Scenario 8 | Concurrent Multi-Asset Rebalance with Slippage | 🟢 **PASS** | Sell BTC: OrderCall(pair=XBTUSD, type=market, side=sell, volume=0.34400000)<br>Buy ETH: OrderCall(pair=ETHUSD, type=market, side=buy, volume=3.96000000) (Expected volume: 3.96 ETH)<br>Execution log: [sell XBTUSD volume=0.34400000, buy ETHUSD volume=3.96000000] |
| Scenario 9 | Run Loop Lifecycle & Timing | 🟢 **PASS** | Loop started successfully.<br>Executed cycles count: 3 (expected >= 2)<br>Loop stopped cleanly when stopRebalancingLoop() was called. |
| Scenario 10 | Atomic File Writer Resilience | 🟢 **PASS** | Target stats path: .../build/test-scenario10-base/stats.json<br>AtomicJsonFile.write failed with IOException as expected: true<br>Repository remains uncorrupted. |
| Scenario 11 | Configuration Validation Edge Cases | 🟢 **PASS** | Invalid loop delay exception: Loop delay must be a positive integer.<br>Invalid deviation exception: Deviation trigger percent must be non-negative.<br>Invalid drawdown exception: Fiat max drawdown must be between 0% and 100%.<br>Invalid total percent exception: Total allocation percentage must be exactly 100%. Current sum: 90.0<br>Missing USD exception: One asset must be USD. |
| Scenario 12 | Precision and Rounding Tolerances | 🟢 **PASS** | Total assets: 1<br>Parsed prices and balances without rounding / arithmetic errors. |
| Scenario 13 | High Volatility Slippage Capping | 🟢 **PASS** | Sells executed: [OrderCall(pair=XBTUSD, type=market, side=sell, volume=0.01120000)]<br>Buys executed: [OrderCall(pair=ETHUSD, type=market, side=buy, volume=0.17325000)]<br>ETH buy volume expected: 0.17325000, actual: 0.17325000 (Success: true) |
| Scenario 14 | Config File Hot-Reload and Watcher Integration | 🟢 **PASS** | Initial loop delay: 60s<br>Modified config loop delay on disk: 120s<br>Config service dynamically hot-reloaded: true |
| Scenario 15 | Single Asset Dominance (Extreme Rebalance) | 🟢 **PASS** | Total balance: $1000 USD<br>Target: 99% BTC ($990 USD)<br>Executed order: OrderCall(pair=XBTUSD, type=market, side=buy, volume=0.01980000) (Success: true) |
| Scenario 16 | Trade History Storage and JSON Serialization | 🟢 **PASS** | Saved history file path: .../scenario16-*.json<br>Loaded history size: 1<br>Parsed snapshot totals: value=$12345.67, timestamp=2026-06-20T12:00:00Z |
| Scenario 17 | Partial Kraken API Failure (Individual Endpoint Failures) | 🟢 **PASS** | Prices API call threw IOException as expected.<br>Rebalance cycle aborted cleanly.<br>Executed orders count: 0 (expected 0) |
| Scenario 18 | Ktor SSE Keep-Alive and Broadcast Resilience | 🟢 **PASS** | SSE stream client successfully connected and received 3 snapshots sequentially:<br>- Snapshot 1 (Initial): SNAP1<br>- Snapshot 2: SNAP2<br>- Snapshot 3: SNAP3 |
| Scenario 19 | Extremely Large Portfolio Allocation Scaling | 🟢 **PASS** | Portfolio configured with 15 assets.<br>Sum of allocations: 100.0%<br>Configuration validated successfully: true |
| Scenario 20 | Missing or Corrupt Stats File Recovery | 🟢 **PASS** | Corrupted JSON loaded successfully (recovered with default): true<br>New stats saved and verified correctly: true |
| Scenario 21 | Perfect Allocation Alignment (No Trades) | 🟢 **PASS** | Total balance: 1.0 BTC ($1000) and $1000 USD.<br>Executed orders count: 0<br>Snapshot actions: []<br>No trades executed: true |
| Scenario 22 | Order Failure Logging & Snapshot Mapping | 🟢 **PASS** | Target: buy 0.01 BTC ($500).<br>Order result mocked to fail with 'Insufficient funds'.<br>Captured actions in history snapshot: [Deviation Triggered details: BTC Dev: 100.0000%, Deviation Triggered details: USD Dev: 100.0000%, FAILED BUY BTC: Insufficient funds]<br>Error successfully logged in snapshot: true |
| Scenario 23 | Complete Authentication API Failure | 🟢 **PASS** | Balances API call threw: EAPI:Invalid key or signature<br>Executed orders count: 0<br>Rebalance cycle aborted safely: true |
| Scenario 24 | Config File Writer Failure Protection | 🟢 **PASS** | Config file path replaced by directory: .../scenario24-*.json<br>Update config threw RuntimeException as expected: true (Msg: Failed to save configuration) |
| Scenario 25 | Minimum Order Size Rejection Recovery | 🟢 **PASS** | Executed order calls: [OrderCall(pair=XBTUSD, type=market, side=buy, volume=0.00600000), OrderCall(pair=ETHUSD, type=market, side=buy, volume=0.15000000)]<br>Captured actions in history snapshot: [Deviation Triggered details: BTC Dev: 100.0000%, Deviation Triggered details: ETH Dev: 100.0000%, Deviation Triggered details: USD Dev: 150.0000%, FAILED BUY BTC: Order minimum size not met, BUY ETH Volume: 0.15000000 Cost: $300.000000]<br>BTC failure logged: true, ETH success logged: true |
| Scenario 26 | Pure Cash Injection (No Sells, Only Buys) | 🟢 **PASS** | Executed buy orders: [OrderCall(pair=XBTUSD, type=market, side=buy, volume=0.50000000)]<br>Executed sell orders: []<br>Correctly generated single buy of 0.5 BTC: true |
| Scenario 27 | Concurrency of Multiple SSE Listeners | 🟢 **PASS** | Connected 5 clients to SSE endpoint.<br>Clients that successfully received broadcast: [Client 4 OK, Client 3 OK, Client 5 OK, Client 1 OK, Client 2 OK]<br>All 5 clients received the snapshot: true |
| Scenario 28 | Zero Balance Division by Zero Prevention | 🟢 **PASS** | Zero balances supplied for BTC and USD.<br>Executed orders count: 0<br>Rebalance cycle terminated safely: true |
| Scenario 29 | Extremely Large Dust Threshold | 🟢 **PASS** | Captured actions: [Deviation Triggered details: USD Dev: 17.6500%, USD Deviation Triggered. Enforcing fiat correction., Distributing Fiat Correction ($180.00) among 2 candidates., Skipping dust buy for BTC ($90.000000000000000), Skipping dust buy for ETH ($90.000000000000000)]<br>Executed orders count: 0<br>BTC buy skipped: true, ETH buy skipped: true |
| Scenario 30 | Exponent Curve Calibration for Fiat Deployment | 🟢 **PASS** | Drawdown: 10.0000% (Pass: true)<br>Deployment Pct: 25.0% (Expected: 25.0%, Pass: true)<br>Effective USD Target: 15.00000% (Expected: 15.0%, Pass: true)<br>Adjusted BTC Target: 85.000000000% (Expected: 85.0%, Pass: true) |
