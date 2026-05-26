# Kraken Rebalancer

A production-grade, autonomous portfolio rebalancing engine for the [Kraken](https://www.kraken.com/) cryptocurrency exchange. The system continuously monitors your portfolio and automatically executes trades to maintain target asset allocations — with intelligent strategies for handling deposits, withdrawals, and market drawdowns.

**This application has been running in production managing a live portfolio for several months.**

![Dashboard](docs/images/dashboard.png)

---

## Tech Stack

| Layer | Technology |
|---|---|
| **Language** | Kotlin 2.x (JVM) |
| **Backend** | Ktor 2.3.13 (Netty engine), Koin 3.5.6 (DI), Jackson 2.21 |
| **HTTP Client** | Ktor CIO Client (async, coroutine-native) |
| **Concurrency** | Kotlin Coroutines (`kotlinx.coroutines` 1.11) |
| **Frontend** | React 19 (TypeScript), Vite 7, Tailwind CSS v4, Chart.js |
| **API** | Kraken REST API with HMAC-SHA512 authentication |
| **Testing** | Kotest 6.1 (StringSpec), MockK 1.14, Ktor MockEngine, JaCoCo (95%+ coverage enforced) |
| **Build** | Gradle (Kotlin DSL), npm |

---

## Features

### Autonomous Rebalancing
- Continuously monitors portfolio allocations against configurable targets
- Automatically generates and executes market orders when deviation thresholds are exceeded
- Sells overweight assets first to generate liquidity, then buys underweight assets

### Dynamic Fiat Deployment
- Tracks portfolio All-Time High (ATH) and calculates real-time drawdown
- Progressively deploys idle cash into the market as drawdowns deepen
- Configurable deployment curve via an exponent parameter (linear, aggressive, or conservative)

### Intelligent Fiat Correction
- Detects deposits/withdrawals by recognizing when only USD triggers a deviation
- Distributes surplus cash proportionally among the most underweight assets
- Handles withdrawals by selling from the most overweight assets

### Live Dashboard
- Real-time portfolio overview with auto-refresh (5-second polling)
- Horizontal bar chart showing asset allocation by value
- Sortable asset performance table with deviation indicators
- Trade history log with BUY/SELL badges
- Live/Delayed status indicator with data age tracking

### Hot-Reload Configuration
- Modify all settings (allocations, thresholds, assets) via the web UI
- Add or remove assets without restarting the application
- Allocation validation ensures targets always sum to 100%

### Safety & Reliability
- **Dry Run Mode** — test your strategy without executing real trades
- Dust threshold filtering to avoid minimum order size errors
- Automatic error recovery — API failures don't crash the rebalancing loop
- Price validation — aborts cycle if any asset price is unavailable

---

## Screenshots

### Dashboard
The main dashboard shows portfolio value, cash position with effective target (adjusted for drawdown deployment), crypto asset values, an allocation chart, and a sortable asset performance table.

![Dashboard](docs/images/dashboard.png)

### Asset Table & Trade History
The lower section shows detailed per-asset metrics (price, value, target %, current %, deviation) and a chronological trade activity log.

![Dashboard Bottom](docs/images/dashboard-bottom.png)

### Settings
All configuration is managed through the web UI — loop interval, deviation trigger, dust threshold, fiat deployment parameters, and per-asset allocation targets.

![Settings](docs/images/settings.png)

---

## Architecture

```mermaid
graph LR
    subgraph Frontend["Frontend (React + Vite)"]
        D[Dashboard] --> SC[StatusCard]
        D --> AC[AllocationChart]
        D --> TH[TradeHistory]
        D --> S[Settings]
    end

    subgraph Backend["Backend (Ktor + Koin)"]
        DC[DashboardRoutes] --> THS[TradeHistoryService]
        DC --> CS[ConfigService]
        PM[PortfolioManager] --> KS[KrakenService]
        PM --> CS
        PM --> THS
        PM --> PSR[PortfolioStatsRepository]
        THS --> FTR[FileTradeRepository]
    end

    subgraph External
        KA[Kraken API]
    end

    Frontend -- "REST API\n/api/*" --> DC
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

See **[ALGORITHM.md](ALGORITHM.md)** for a detailed breakdown of the rebalancing logic, fiat correction strategy, and dynamic deployment math.

---

## Project Structure

```
├── src/main/kotlin/com/gemini/krakenbot/
│   ├── KrakenRebalancerApplication.kt    # Entry point, Ktor server & Koin DI bootstrap
│   ├── config/                            # Data classes: AppConfig, Settings, Allocation, KrakenCredentials
│   │   └── AppModule.kt                  # Koin dependency injection module
│   ├── controller/                        # Ktor routes: DashboardRoutes, FrontendConfig
│   ├── model/                             # Domain: PortfolioSnapshot, PortfolioStats
│   ├── repository/                        # Persistence interfaces: TradeRepository, PortfolioStatsRepository
│   │   └── impl/                          # File-backed implementations
│   └── service/                           # Core logic interfaces: PortfolioManager, KrakenService, ConfigService, TradeHistoryService
│       └── impl/                          # Service implementations (coroutine-aware)
├── src/test/kotlin/                       # 92 unit tests (98%+ line coverage, 96%+ branch coverage enforced by JaCoCo)
│   └── com/gemini/krakenbot/service/
│       └── FakeKrakenService.kt           # In-process test double for KrakenService
├── frontend/
│   └── src/
│       ├── assets/                        # Static assets (e.g., images, icons)
│       ├── components/                    # Dashboard, Settings, AllocationChart, TradeHistory, StatusCard
│       ├── services/                      # API client configurations
│       ├── test/                          # Frontend unit and component tests
│       ├── types/                         # TypeScript definitions
│       ├── index.css                      # Dark theme design system
│       └── App.tsx                        # Root component
├── ALGORITHM.md                           # Detailed algorithm documentation
├── rebalancer-config-template.json        # Configuration template
└── build.gradle.kts                       # Gradle build with JaCoCo coverage enforcement
```

---

## Getting Started

### Prerequisites

- JDK 25 or higher
- Gradle (or use the included `./gradlew` wrapper — no installation required)
- Node.js (LTS version) and npm
- A Kraken account with API Keys (Permissions: **Query Funds**, **Create & Modify Orders**)

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
- Optionally configure `fiatMaxDrawdown` and `fiatDeploymentExponent` for dynamic cash deployment

### 2. Start the Backend

```bash
./gradlew run
```

The backend starts on port **8080** and begins the rebalancing loop immediately.

### 3. Start the Frontend

```bash
cd frontend
npm install   # First time setup, or after pulling updates to install new dependencies
npm run dev
```

Open your browser to **http://localhost:5173**. The frontend proxies API requests to the backend automatically.

---

## Configuration Reference

| Field | Type | Default | Description |
|---|---|---|---|
| `loopDelaySeconds` | `Long` | `60` | Seconds between rebalance cycles |
| `deviationTriggerPercent` | `Double` | `5.0` | Minimum deviation % to trigger a trade |
| `dustThresholdUSD` | `Double` | `5.0` | Minimum trade value in USD (below this is skipped) |
| `dryRun` | `Boolean` | `true` | If true, logs intended trades without executing them |
| `fiatMaxDrawdown` | `Double` | `0.0` | Portfolio drawdown % at which 100% of USD is deployed (0 = disabled) |
| `fiatDeploymentExponent` | `Double` | `1.0` | Controls deployment curve: `1.0` = linear, `<1.0` = aggressive, `>1.0` = conservative |

---

## API Endpoints

| Method | Path | Description |
|---|---|---|
| `GET` | `/api/status` | Returns the latest portfolio snapshot |
| `GET` | `/api/history` | Returns the last 50 portfolio snapshots |
| `GET` | `/api/config` | Returns the current configuration |
| `POST` | `/api/config` | Updates and persists configuration (validated server-side) |

---

## Testing

The project enforces **95% line and branch coverage** via JaCoCo. All tests are behavioural — they verify actual rebalancing decisions, not just method invocations.

```bash
./gradlew test
```

**92 tests** across:
- `KrakenE2ETest` / `ResilienceChaosTest` / `PrecisionRoundingFuzzTest` / `SerializationParityTest` — advanced E2E black-box and fuzz testing
- `PortfolioManagerFiatCorrectionTest` — deposit/withdrawal distribution logic
- `PortfolioManagerDrawdownTest` — ATH tracking and dynamic deployment
- `PortfolioManagerOrderExecutionTest` — sell-first/buy-second sequencing
- `PortfolioManagerLoopTest` — loop lifecycle, error recovery, interruption
- `PortfolioManagerZeroAllocationTest` — edge case: 0% target allocation
- `PortfolioManagerEdgeCasesTest` — dust thresholds, price gaps, zero balances
- `PortfolioManagerDogeTest` — Kraken symbol mapping quirks (BTC→XBT, DOGE→XDG)
- `KrakenServiceTest` — API signing, error handling, dry run (using Ktor `MockEngine`)
- `ConfigServiceTest` — validation, hot-reload, persistence
- `TradeHistoryServiceTest` — snapshot storage, size limits
- `FileTradeRepositoryTest` / `PortfolioStatsRepositoryTest` — file I/O

### Test Design Principles

- **`FakeKrakenService`** — an in-process test double for `KrakenService` used by all `PortfolioManager` tests. Avoids fragile `coEvery` stubbing of `suspend` functions in concurrent coroutine contexts.
- **`runTest`** — all tests that call `suspend` functions use `kotlinx.coroutines.test.runTest` for correct coroutine scheduling.
- **`MockEngine`** — `KrakenServiceTest` uses Ktor's `MockEngine` to simulate HTTP responses without a real network.

---

## License

This project is licensed under the MIT License — see the [LICENSE](LICENSE) file for details.
