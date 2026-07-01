# Agent Rules & Coding Harness

Welcome, Antigravity/AI Agent. This workspace contains the **Kraken Rebalancer**, an autonomous portfolio rebalancing bot. Below are the rules, architecture constraints, and standards you must strictly adhere to when modifying this codebase.

---

## 1. Technology Stack

| Layer | Technology |
| :--- | :--- |
| **Language** | Kotlin 2.4.0 (JVM) |
| **Backend** | Ktor 3.5.0 (Netty engine, ContentNegotiation with Jackson) |
| **DI** | Koin 4.2.1 |
| **Concurrency** | Kotlin Coroutines (`kotlinx.coroutines` 1.11.0) |
| **Frontend** | Server-side HTML (`kotlinx.html` DSL) + HTMX + Ktor SSE |
| **Testing** | Kotest 6.1 (StringSpec), MockK 1.14, Ktor MockEngine |
| **Coverage** | JaCoCo (100% class, method, line, and branch coverage achieved) |
| **Build** | Gradle (Kotlin DSL) |

---

## 2. Core Architecture

The rebalancing engine is strictly separated according to the **Single Responsibility Principle (SRP)**:

* **`KrakenRebalancerApplication.kt`**: Main entry point, bootstraps Ktor, configures Koin DI, starts the coroutine loop, and registers JVM shutdown hooks.
* **`PortfolioManagerImpl` (Orchestrator)**: Manages the continuous coroutine loop lifecycle (`startRebalancingLoop`, `stopRebalancingLoop`, `runLoop`).
* **`PortfolioAnalyzer` (Brain)**: Fetches prices and balances, updates All-Time High (ATH), computes drawdown percentages and fiat deployment levels, calculates deviations, and generates `BUY`/`SELL` orders.
* **`OrderExecutor` (Brawn)**: Safely executes orders in a strict sequencing pipeline:
    1. **Sell Overweight Assets First**: Generate USD liquidity.
    2. **USD Balance Refresh**: Poll the Kraken API up to 3 times (250ms intervals) to verify settled cash.
    3. **Buy Underweight Assets Second**: Verify cash sufficiency, capping buy volumes at 99% of available cash to account for slippage.
* **Persistence Repositories** (`SqliteTradeRepositoryImpl`, `SqlitePortfolioStatsRepositoryImpl`): Persist trade logs and portfolio statistics to a SQLite database using JetBrains Exposed ORM.
* **`TradeHistoryServiceImpl`**: Maintains a hot `MutableSharedFlow` to broadcast new portfolio snapshots to HTMX clients via the `/api/status/stream` SSE endpoint.

---

## 3. Coding Standards & Safety Constraints

### BigDecimal Precision
* **NEVER** use `Double` or `Float` for currency amounts, asset values, or order volumes.
* Always use `BigDecimal` with 8 decimal places (`setScale(8, RoundingMode.HALF_UP)`) for transaction volumes, and 2 decimal places for USD valuations.
* Perform assertions in tests using `BigDecimal.compareTo() == 0` instead of `.equals()`, as scale differences (e.g. `1.0` vs `1.00`) cause `.equals()` to fail.

### Path Resolution
* **NEVER** hardcode absolute file paths (e.g., `/tmp/...` or `/Users/...`).
* Use relative paths, temp directories, or system/environment variables (e.g., `SCENARIOS_REPORT_PATH`) to configure file persistence dynamically.

### Kraken API Quirks
* Handle symbol mappings correctly. Kraken APIs map standard symbols to local variants:
    - **BTC** $\rightarrow$ `XBTUSD` (or `XBT` for balances)
    - **DOGE** $\rightarrow$ `XDGUSD` (or `DOGE`/`XDG` for balances)
* Ensure the symbol converter correctly addresses these mapping conventions during API query compilation.

### Config Watcher & Hot-Reload
* Ensure modified parameters (like loop delay, deviation trigger, dust threshold) reload dynamically at runtime without requiring an application restart.

---

## 4. Testing Standards

The test suite enforces a **100% JaCoCo coverage gate** across all packages. If you add or modify code, you must add tests to maintain 100% coverage.

### Kotest Specs Initialization
* Kotest specs must use standard class body `init { ... }` blocks instead of constructor arguments. This ensures compatibility with Gradle test runners and IDE test discovery:
    ```kotlin
    @Suppress("unused")
    class MyServiceTest : StringSpec() {
        init {
            "should perform action successfully" {
                // Test implementation
            }
        }
    }
    ```
* Apply `@Suppress("unused")` to spec classes to avoid static analysis complaints since Kotest specs are instantiated reflectively.

### Test Doubles (Fake Service)
* Do not write complex `coEvery` stubs for concurrent services (like `KrakenService`). Use the in-process `FakeKrakenService.kt` to dynamically adjust prices, mock API failures, and assert executed order calls.

### Coroutines and Virtual Time
* Always wrap tests calling suspend functions in `runTest`.
* Avoid real clock delays; let the virtual time scheduler advance time immediately.

---

## 5. Walkthrough and Verification Artifacts
- When completing a task, you must run `./gradlew test` to ensure the compilation and tests succeed.
- Create or update the `walkthrough.md` in the current conversation directory to report on changes made, tests passed, and verification logs.
