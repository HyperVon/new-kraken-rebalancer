# Kraken Rebalancer

A production-grade, autonomous portfolio rebalancing engine for
the [Kraken](https://www.kraken.com/) cryptocurrency exchange. The system
continuously monitors your portfolio and automatically executes trades to
maintain target asset allocations — with intelligent strategies for handling
deposits, withdrawals, and market drawdowns.

**This application has been running in production managing a live portfolio for
several months.**

![Dashboard](docs/images/dashboard.png)

---

## Tech Stack

| Layer           | Technology                                                                                           |
|-----------------|------------------------------------------------------------------------------------------------------|
| **Language**    | Kotlin 2.4.0 (JVM)                                                                                   |
| **Backend**     | Ktor 3.5.0 (Netty engine), Koin 4.2.1 (DI), Jackson 2.21                                             |
| **HTTP Client** | Ktor CIO Client (async, coroutine-native)                                                            |
| **Concurrency** | Kotlin Coroutines (`kotlinx.coroutines` 1.11.0)                                                      |
| **Frontend**    | Server-side HTML (kotlinx.html DSL + HTMX), Ktor SSE                                                 |
| **API**         | Kraken REST API with HMAC-SHA512 authentication                                                      |
| **Testing**     | Kotest 6.1 (StringSpec), MockK 1.14, Ktor MockEngine, JaCoCo (95%+ coverage enforced, 100% achieved) |
| **Build**       | Gradle (Kotlin DSL)                                                                                  |

---

## Features

### Autonomous Rebalancing

- Continuously monitors portfolio allocations against configurable targets
- Automatically generates and executes market orders when deviation thresholds
  are exceeded
- Sells overweight assets first to generate liquidity, then buys underweight
  assets

### Dynamic Fiat Deployment

- Tracks portfolio All-Time High (ATH) and calculates real-time drawdown
- Progressively deploys idle cash into the market as drawdowns deepen
- Configurable deployment curve via an exponent parameter (linear, aggressive,
  or conservative)

### Intelligent Fiat Correction

- Recognizes when only USD triggers a deviation threshold (e.g., after a deposit
  or withdrawal)
- Distributes surplus cash proportionally among the most underweight assets
- Handles withdrawals by selling from the most overweight assets

### Live Dashboard

- Real-time portfolio overview with push updates (via Ktor Server-Sent Events)
- Horizontal bar chart showing asset allocation by value
- Sortable asset performance table with deviation indicators
- Trade history log with BUY/SELL badges
- Live/Delayed status indicator with data age tracking
- **Hypermedia-powered** — uses HTMX for dynamic content swapping and form
  submissions without writing JavaScript

### Hot-Reload Configuration

- Modify all settings (allocations, thresholds, assets) via the web UI
- Add or remove assets without restarting the application
- Allocation validation ensures targets always sum to 100%

### Safety & Reliability

- **Dry Run Mode** — test your strategy without executing real trades
- **Structured Order Results** — each order returns success/failure status;
  failed orders don't corrupt cash projections
- **Atomic File Writes** — config, stats, and trade history use
  write-then-rename to prevent corruption
- **Graceful Shutdown** — JVM shutdown hook cleanly stops the loop, closes
  connections, and tears down DI
- Dust threshold filtering to avoid minimum order size errors
- Automatic error recovery — API failures don't crash the rebalancing loop
- Price validation — aborts cycle if any asset price is unavailable
- **BigDecimal Precision** — order volumes use `BigDecimal` (8 decimal places)
  to eliminate floating-point rounding

---

## Screenshots

### Dashboard

The main dashboard shows portfolio value, cash position with effective target (
adjusted for drawdown deployment), crypto asset values, an allocation chart, and
a sortable asset performance table.

![Dashboard](docs/images/dashboard.png)

### Asset Table & Trade History

The lower section shows detailed per-asset metrics (price, value, target %,
current %, deviation) and a chronological trade activity log.

![Dashboard Bottom](docs/images/dashboard-bottom.png)

### Settings

All configuration is managed through the web UI — loop interval, deviation
trigger, dust threshold, fiat deployment parameters, and per-asset allocation
targets.

![Settings](docs/images/settings.png)

---

## Architecture

```mermaid
graph LR
    subgraph Frontend["Frontend (HTMX + Server-Side HTML)"]
        D[Dashboard Shell] --> DF[Dashboard Fragment]
        D --> SF[Settings Fragment]
        DF --> SS[SSE Stream]
    end

    subgraph Backend["Backend (Ktor + Koin)"]
        DC[DashboardRoutes] --> THS[TradeHistoryService]
        DC --> CS[ConfigService]
        DC --> DV[DashboardView]
        PM[PortfolioManager] --> PA[PortfolioAnalyzer]
        PM --> OE[OrderExecutor]
        PM --> THS
        PA --> KS[KrakenService]
        PA --> CS
        PA --> PSR[PortfolioStatsRepository]
        OE --> KS
        OE --> CS
        OE --> PA
        THS --> FTR[FileTradeRepository]
    end

    subgraph External
        KA[Kraken API]
    end

    Frontend -- "REST API & SSE Stream\n/api/*" --> DC
    PM -- "Coroutine Loop\n(configurable interval)" --> PM
    KS -- "HMAC-SHA512\nAuthenticated" --> KA
```

### Rebalance Cycle

Each cycle executes three phases:

```mermaid
flowchart LR
    A["📸 Snapshot\nFetch balances & prices\nCalculate portfolio value"] --> B["📊 Analysis\nCompute deviations\nApply drawdown adjustments\nDetermine trades"]
    B --> C["⚡ Execution\nSell overweight assets\nBuy underweight assets\nRecord snapshot"]
    C --> D["💤 delay()\n(configurable interval)"]
    D --> A
```

See **[ALGORITHM.md](ALGORITHM.md)** for a detailed breakdown of the rebalancing
logic, fiat correction strategy, and dynamic deployment math.

### Real-Time Event Streaming

To eliminate unnecessary network polling, the system uses a reactive, push-based
architecture to synchronize the dashboard with the backend rebalancing loop:

1. **Kotlin SharedFlow**: `TradeHistoryServiceImpl` maintains a
   `MutableSharedFlow` as a hot event broadcaster. Whenever a rebalance cycle
   records a new `PortfolioSnapshot`, the snapshot is emitted to the flow using
   `tryEmit()`.
2. **Ktor Server-Sent Events (SSE)**: The `/api/status/stream` route installs
   Ktor 3's native `SSE` plugin. When a client connects, Ktor pushes the latest
   cached snapshot and then suspends, collecting subsequent snapshots from the
   `SharedFlow` and streaming them over a single, persistent HTTP connection.
3. **HTMX SSE Extension**: The dashboard shell uses `hx-ext="sse"` and
   `sse-connect="/api/status/stream"`. A div with `sse-swap="message"` and
   `hx-trigger="sse:message"` automatically fetches updated dashboard fragments
   from `/fragments/dashboard` whenever a new snapshot arrives over the SSE
   stream.

---

## Project Structure

```
├── src/main/kotlin/com/gemini/krakenbot/
│   ├── KrakenRebalancerApplication.kt    # Entry point, Ktor server & Koin DI bootstrap
│   ├── config/                            # Data classes: AppConfig, Settings, Allocation, KrakenCredentials
│   │   └── AppModule.kt                  # Koin dependency injection module
│   ├── controller/                        # Ktor routes: DashboardRoutes
│   ├── model/                             # Domain: PortfolioSnapshot, PortfolioStats, OrderResult
│   ├── repository/                        # Persistence interfaces: TradeRepository, PortfolioStatsRepository
│   │   └── impl/                          # File-backed implementations
│   ├── service/                           # Core logic interfaces: PortfolioManager, KrakenService, ConfigService, TradeHistoryService
│   │   └── impl/                          # Service implementations (coroutine-aware)
│   ├── view/                              # HTML templates & components (kotlinx.html DSL)
│   │   ├── DashboardView.kt              # Facade class delegating to components
│   │   ├── component/                    # Modular components (Shell, Grid, Form, etc.)
│   │   └── util/                         # View utilities (Formatter, Icons, ViewText, Layouts)
│   └── util/                              # Utilities: AtomicJsonFile
├── src/test/kotlin/                       # Unit tests (100% overall coverage achieved across all packages and metrics)
│   └── com/gemini/krakenbot/
│       └── service/
│           └── FakeKrakenService.kt       # In-process test double for KrakenService
├── src/main/resources/                    # Static resources
│   └── static/
│       ├── style.css                      # Dashboard stylesheet
│       ├── dashboard.js                   # Dashboard client-side scripts
│       └── settings.js                    # Settings form client-side scripts
├── ALGORITHM.md                           # Detailed algorithm documentation
├── rebalancer-config-template.json        # Configuration template
└── build.gradle.kts                       # Gradle build with JaCoCo coverage enforcement
```

---

## Getting Started

### Prerequisites

- JDK 25 or higher
- Gradle (or use the included `./gradlew` wrapper — no installation required)
- A Kraken account with API Keys (Permissions: **Query Funds**, **Create &
  Modify Orders**)

### 1. Clone & Configure

```bash
git clone https://github.com/HyperVon/new-kraken-rebalancer.git
cd new-kraken-rebalancer
cp rebalancer-config-template.json rebalancer-config.json
```

Edit `rebalancer-config.json`:

- Add your Kraken API Key and Private Key
- Define your desired `allocations` (must sum to 100%, must include USD)
- Set `dryRun` to `true` for initial testing
- Optionally configure `fiatMaxDrawdown` and `fiatDeploymentExponent` for
  dynamic cash deployment

### 2. Start the Application

```bash
./gradlew run
```

The backend starts on port **8080** and begins the rebalancing loop immediately.

### 3. Open Dashboard

Open your browser to **http://localhost:8080**. The dashboard is served directly
from the backend — no separate frontend build step required.

---

## Configuration Reference

| Field                     | Type      | Default | Description                                                                           |
|---------------------------|-----------|---------|---------------------------------------------------------------------------------------|
| `loopDelaySeconds`        | `Long`    | `60`    | Seconds between rebalance cycles                                                      |
| `deviationTriggerPercent` | `Double`  | `5.0`   | Minimum deviation % to trigger a trade                                                |
| `dustThresholdUSD`        | `Double`  | `5.0`   | Minimum trade value in USD (below this is skipped)                                    |
| `dryRun`                  | `Boolean` | `true`  | If true, logs intended trades without executing them                                  |
| `fiatMaxDrawdown`         | `Double`  | `0.0`   | Portfolio drawdown % at which 100% of USD is deployed (0 = disabled)                  |
| `fiatDeploymentExponent`  | `Double`  | `1.0`   | Controls deployment curve: `1.0` = linear, `<1.0` = aggressive, `>1.0` = conservative |

---

## API Endpoints

| Method | Path                   | Description                                                              |
|--------|------------------------|--------------------------------------------------------------------------|
| `GET`  | `/`                    | Main dashboard shell (HTML)                                              |
| `GET`  | `/settings`            | Settings page (HTML)                                                     |
| `POST` | `/settings`            | Submit settings form (HTMX)                                              |
| `GET`  | `/fragments/dashboard` | Dashboard fragment (HTMX)                                                |
| `GET`  | `/api/status/stream`   | Server-Sent Events (SSE) stream for real-time portfolio snapshot updates |
| `GET`  | `/static/*`            | Static assets (CSS)                                                      |

---

## Testing

The project enforces **95% line, branch, method, and instruction coverage** via
JaCoCo, with the test suite achieving exactly **100% line, branch, method,
class, and instruction coverage** across the entire codebase (including view
rendering and routing). All tests are behavioural — they verify actual
rebalancing decisions, not just method invocations. Order volumes are asserted
with `BigDecimal.compareTo()` to avoid floating-point comparison issues.

```bash
./gradlew test
```

**138 tests** across:

- `KrakenE2ETest` / `ResilienceChaosTest` / `PrecisionRoundingFuzzTest` /
  `SerializationParityTest` — advanced E2E black-box and fuzz testing
- `PortfolioManagerComprehensiveTest` — full rebalance cycles with order result
  verification
- `PortfolioManagerFiatCorrectionTest` — deposit/withdrawal distribution logic
- `PortfolioManagerDrawdownTest` — ATH tracking and dynamic deployment
- `PortfolioManagerOrderExecutionTest` — sell-first/buy-second sequencing
- `PortfolioManagerLoopTest` — loop lifecycle, error recovery, interruption
- `PortfolioManagerZeroAllocationTest` — edge case: 0% target allocation
- `PortfolioManagerEdgeCasesTest` — dust thresholds, price gaps, zero balances
- `PortfolioManagerDogeTest` — Kraken symbol mapping quirks (BTC→XBT, DOGE→XDG)
- `KrakenServiceTest` — API signing, error handling, dry run, order failure (
  using Ktor `MockEngine`)
- `ModelTest` — unit tests for models including `Asset` mapping
- `AtomicJsonFileTest` — file-system atomic write verification under normal and
  error/unsupported paths
- `ConfigServiceTest` — validation, hot-reload, persistence, duplicate/blank
  symbol rejection
- `DashboardControllerTest` — REST API endpoints, invalid config error responses
- `TradeHistoryServiceTest` — snapshot storage, size limits
- `FileTradeRepositoryTest` / `PortfolioStatsRepositoryTest` — file I/O, atomic
  writes, error propagation

### Test Design Principles

- **Class Initializers**: All test suites are structured using standard class body `init { ... }` blocks (e.g., `class ExampleTest : StringSpec() { init { ... } }`) instead of constructor lambdas, making them fully compatible with build runners and IDE test discovery tools.
- **`FakeKrakenService`** — an in-process test double for `KrakenService` used
  by all `PortfolioManager` tests. Avoids fragile `coEvery` stubbing of
  `suspend` functions in concurrent coroutine contexts.
- **`runTest`** — all tests that call `suspend` functions use
  `kotlinx.coroutines.test.runTest` for correct coroutine scheduling.
- **`MockEngine`** — `KrakenServiceTest` uses Ktor's `MockEngine` to simulate
  HTTP responses without a real network.

---

## License

This project is licensed under the MIT License — see the [LICENSE](LICENSE) file
for details.