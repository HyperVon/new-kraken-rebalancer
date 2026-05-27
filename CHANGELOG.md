# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

---

## [2.2.0] - 2026-05-26

### Added
- **`OrderResult` Model**: `KrakenService.executeOrder()` now returns a structured `OrderResult` (success/failure, pair, side, volume, dry-run flag, error message) instead of returning `Unit`. Failed orders no longer corrupt projected cash accounting.
- **`AtomicJsonFile` Utility**: All JSON file persistence (config, trade history, portfolio stats) now uses atomic write-then-rename to prevent data corruption from partial writes during crashes or power loss.
- **`KrakenSymbols` Utility**: Centralized Kraken ticker mapping (BTC→XBT, DOGE→XDG) and USD trading pair construction into a dedicated, tested utility object — replacing ad-hoc inline mapping.
- **`InvalidConfigurationException`**: Configuration validation errors now throw a dedicated exception (instead of generic `RuntimeException`), returned to the frontend as structured `400 Bad Request` JSON responses with user-readable messages.
- **Expanded Config Validation**: Added server-side validation for empty allocations, duplicate symbols, blank symbols, and negative target percentages.
- **Dashboard Startup States**: Frontend now shows a "Waiting for first rebalance cycle" message on `404` (instead of an error), and a clear error state for network failures.
- **Frontend Input Safety**: Numeric settings inputs use `parseNumberInput()` with fallback values to prevent `NaN`/`Infinity` from reaching the configuration. Added `min` attributes and a tooltip on deviation trigger.
- **Graceful Shutdown**: Application now registers a JVM shutdown hook that stops the rebalancing loop, closes the HTTP client, and stops Koin.
- **`KrakenSymbolsTest`**: Unit tests for ticker mapping and trading pair construction.
- **`DashboardControllerTest`**: Test for `400 Bad Request` response on invalid configuration updates.

### Changed
- **`BigDecimal` Order Volumes**: `KrakenService.executeOrder()` volume parameter changed from `Double` to `BigDecimal`, eliminating floating-point precision loss on volumes sent to the Kraken API. Volumes are normalized to 8 decimal places.
- **Price Map Type**: Internal price maps changed from `Map<String, Double>` (keyed by Kraken pair name) to `Map<String, BigDecimal>` (keyed by allocation symbol), eliminating fuzzy key matching and floating-point conversion at the call site.
- **Sell-Before-Buy Cash Tracking**: Projected cash and actual cash are now only updated on `result.success`, preventing a failed sell from inflating the available balance used for subsequent buys.
- **USD Balance Refresh**: `refreshUsdBalanceAfterSells()` now retries up to 3 times at 250ms intervals (750ms worst case) with a 95% settlement threshold, replacing the previous single 100ms delay.
- **Repository Error Propagation**: `FileTradeRepositoryImpl` and `PortfolioStatsRepositoryImpl` now re-throw `IOException` after logging, instead of silently swallowing write failures.
- **Dry Run Action Log**: Dry-run order entries in the snapshot action log are now prefixed with `[DRY RUN]` for clearer distinction from live trades.
- **`FakeKrakenService`**: Updated to support `BigDecimal` volumes and `OrderResult` returns. Added `orderResultFactory` lambda for failure-injection scenarios.
- **Backend test count**: 92 → 97 unit tests.

### Removed
- **Deposit Detection Heuristic**: Removed the `detectDeposit()` method and ATH recalibration-on-deposit logic. The heuristic (USD surplus > deviation threshold ≈ deposit) had false-positive risk from normal sell proceeds. ATH is now set on first run or when a genuine new high is reached.

---

## [2.1.1] - 2026-05-26

### Security
- **logback-classic `1.4.14` → `1.5.32`**: Resolves three vulnerabilities in the 1.4.x branch, which is no longer actively maintained. Fixes CVE-2024-12798 (arbitrary code execution via `JaninoEventEvaluator`), CVE-2024-12801 (SSRF via `SaxEventRecorder` processing external DTDs), and CVE-2025-11226 (arbitrary code execution via the `new` operator in configuration `<if>` conditions).
- **Ktor `2.3.8` → `2.3.13`**: Resolves CVE-2024-49580 (response information disclosure via improper `HttpCache` plugin caching), CVE-2023-45612 (XXE in the `ContentNegotiation` plugin with default XML settings), and CVE-2023-45613 (server certificate verification bypass).
- **Added `jackson-bom:2.21.3`**: Pins `jackson-core` and `jackson-databind` to an explicit, secure version rather than relying on whatever version Ktor's transitive dependency graph resolves. Protects against CVE-2025-52999 (DoS via unbounded recursion on deeply nested JSON, affects `jackson-core < 2.15.0`) and CVE-2025-49128 (information disclosure via reused memory buffers in error messages, affects `< 2.13.0`).
- **Forced Netty version to `4.1.134.Final`**: Resolves 17 Netty-related vulnerabilities pulled in transitively by Ktor (including CVE-2026-33871 HTTP/2 continuation frame flood DoS, SSRF in SslHandler, HTTP Request Smuggling, and resource exhaustion DoS).

### Changed
- **Koin `3.5.3` → `3.5.6`**: Upgraded to the official 3.5.x LTS release.
- **kotlinx-coroutines**: Kept at `1.8.0` to preserve binary compatibility with Ktor 2.3.x (prevents NoSuchMethodError in BlockingAdapter).
- **MockK `1.13.11` → `1.14.9`**: Updated to the latest stable release.
- **Kotest `5.9.0` → `6.1.11`**: Upgraded to the current major version (6.x); the 5.9.x branch is EOL and no longer receives patches.

---

## [2.1.0] - 2026-05-25
### Added
- **Advanced E2E Kotlin Tests**: Introduced highly rigorous Kotest-based test suites using `MockRestServiceServer` to simulate Kraken API behavior (`KrakenE2ETest`, `SerializationParityTest`, `ResilienceChaosTest`, `PrecisionRoundingFuzzTest`). These strictly validate precision handling, JSON backwards compatibility, and resilient coroutine failure states. Increased test suite to 92 unit tests, achieving **98%+ line coverage** and **96%+ branch coverage**.

### Fixed
- **Startup Configuration Crash**: Fixed `ConfigServiceImpl` to automatically load `rebalancer-config.json` upon instantiation, preventing `UninitializedPropertyAccessException`.
- **Koin Duplicate Initialization**: Fixed `KoinAppAlreadyStartedException` by removing duplicate Koin configuration from the Ktor application module.
- **Frontend Data Age Bug**: Disabled `WRITE_DATES_AS_TIMESTAMPS` in Jackson so `java.time.Instant` serializes as an ISO-8601 string, fixing a bug where the frontend misinterpreted raw numeric timestamps as milliseconds.

---

## [2.0.0] - 2026-05-25

### Changed — Breaking (Full Stack Migration)
- **Language**: Rewrote the entire backend from **Java 25** to **Kotlin 2.x**, adopting idiomatic Kotlin constructs throughout (data classes, extension functions, object expressions, coroutines).
- **Framework**: Replaced **Spring Boot 4** with **Ktor 2.3** (Netty engine) for the HTTP server and routing, eliminating classpath scanning and annotation-driven wiring in favour of explicit, type-safe configuration.
- **Dependency Injection**: Replaced Spring's IoC container with **Koin 3.5**, a lightweight, Kotlin-first DI framework. All bindings are defined in a single `AppModule.kt`.
- **HTTP Client**: Replaced the blocking OkHttp client with the **Ktor CIO async client**, making all Kraken API calls fully non-blocking coroutine-native `suspend` functions.
- **Concurrency**: Replaced `Thread.sleep` and Java `ScheduledExecutorService` with **Kotlin Coroutines** (`kotlinx.coroutines` 1.8). The rebalancing loop runs inside a structured `CoroutineScope`; delays use `kotlinx.coroutines.delay`.
- **Build System**: Replaced **Maven** (`pom.xml`) with **Gradle** (Kotlin DSL: `build.gradle.kts`, `settings.gradle.kts`). The Gradle wrapper (`./gradlew`) is included — no Gradle installation required.
- **Testing**: Replaced **JUnit 5 + Mockito** with **Kotest 5.9** (StringSpec) + **MockK 1.13**. `KrakenServiceTest` uses the Ktor `MockEngine`; all `PortfolioManager` tests use `FakeKrakenService` (an in-process test double) and `kotlinx.coroutines.test.runTest`.

### Added
- `FakeKrakenService` — an in-process test double for `KrakenService` that exposes supplier lambdas for controlled state injection, avoiding fragile `coEvery` stubbing of `suspend` functions.
- `executeOrderAction` lambda on `FakeKrakenService` for exception-injection scenarios without subclassing.
- Gradle wrapper binaries (`gradlew`, `gradlew.bat`, `gradle/wrapper/gradle-wrapper.properties`).
- Updated `.gitignore` to cover Gradle build artefacts (`build/`, `.gradle/`).

### Removed
- All `src/main/java` sources (replaced by `src/main/kotlin`)
- All `src/test/java` sources (replaced by `src/test/kotlin`)
- `pom.xml` (replaced by `build.gradle.kts`)
- Spring Boot, Lombok, Tomcat, OkHttp, JUnit 5, Mockito dependencies

---

## [1.3.0] - 2026-05-23

### Added
- **Server-Side Configuration Validation**: Implemented robust backend validation for configuration updates, ensuring values such as drawdown limits, loop delays, and allocation targets are within strict bounds.
- **Frontend Property Whitelisting**: Added explicit whitelist validation for dynamic object property access in the React `Dashboard` and `Settings` components to improve UI security.
- **Edge-Case Test Coverage**: Expanded the backend test suite to 89 unit tests (solidifying >95% branch coverage across all OS environments). This includes coverage for detecting new All-Time Highs, skipping dust-sized buy orders, and handling empty USD API responses. Added frontend test backdoors for better edge-case simulation.

### Changed
- **GitHub Actions Security**: Pinned all GitHub Actions workflows in `.github/workflows/maven.yml` to specific commit SHAs rather than mutable tags for improved supply chain security.
- **Frontend Dependency Management**: Updated all frontend `package.json` dependencies and strictly pinned them to exact versions to prevent future CI breakages from upstream updates.

### Fixed
- **Tomcat Security Vulnerability**: Upgraded the embedded Tomcat server to version `11.0.22` via `pom.xml` to successfully resolve high-severity vulnerabilities (CVE-2026-41284).
- **Allocation Array Bounds**: Added explicit bounds checking for index parameters during allocation state updates to prevent out-of-bounds exceptions.

---

## [1.2.0] - 2026-05-21

### Added
- **TypeScript Migration**: Fully migrated the frontend codebase from JavaScript (`.jsx`, `.js`) to TypeScript (`.tsx`, `.ts`). Added `tsconfig.json`, `tsconfig.app.json`, and `tsconfig.node.json` configurations.
- **Tailwind CSS v4 Integration**: Replaced the custom Vanilla CSS styles with Tailwind CSS v4, utilizing a modern, utility-first approach for styling and theming.
- **Vitest Suite**: Implemented 97 frontend unit tests covering all major UI components (`Dashboard.tsx`, `Settings.tsx`, `StatusCard.tsx`, `AllocationChart.tsx`, `TradeHistory.tsx`).
- **Comprehensive CI Workflow**: Updated the GitHub Actions CI (`.github/workflows/maven.yml`) to build, lint, and run tests for both the Java Spring Boot backend and the React frontend.

### Changed
- **Asset Performance Sorting**: Changed default table sorting in the Asset Performance table to sort by **Dev %** in **ascending** order (`deviationPercent` asc).
- **Layout Spacing & Padding**: Redesigned dashboard cards and table spacing to eliminate wasted layout space, prevent horizontal and vertical scrollbars, and ensure no table rows are cut off.
- **Root Documentation**: Refreshed root `README.md` and `frontend/README.md` to reflect TypeScript, Tailwind CSS v4, correct file paths, and accurate test counts.
- **Updated Screenshots**: Captured and saved high-quality screenshots showing the updated dashboard layout (`docs/images/dashboard.png`, `docs/images/dashboard-bottom.png`, `docs/images/settings.png`).

---

## [1.1.0] - 2026-05-20

### Added
- **Lombok Integration**: Adopted Lombok across backend models and services to reduce boilerplate.
- **95%+ Test Coverage Enforcement**: Expanded unit tests to **78 backend tests** with JaCoCo to strictly enforce code quality and cover edge cases (e.g., Doge symbol mapping, 0% allocations, deposit distribution, and ATH tracking).
- **Security Hardening**: Created a `FrontendConfig` DTO to prevent leaking private backend credentials or raw API key structures to the frontend client.

### Changed
- **Backend Architecture Refactoring**: Restructured backend services into interface-implementation patterns, moving core logic out of controllers and into dedicated packages (`com.gemini.krakenbot.service.impl` and `com.gemini.krakenbot.repository.impl`).
- **Dependency Upgrades**: Upgraded Spring Boot version from `4.0.1` to `4.0.6`.
- **Imports Cleanup**: Removed redundant Fully Qualified Names (FQNs) in backend code and replaced them with standard imports.

---

## [1.0.0] - 2026-05-18

### Added
- **Core Rebalancing Loop**: Continuous monitoring cycle with automated, market-order execution when deviation thresholds are met.
- **Dynamic Drawdown-Based Fiat Deployment**: Automatic, curve-configured deployment of USD cash into crypto assets during market pullbacks using ATH tracking.
- **Intelligent Fiat Correction**: Deposit and withdrawal recognition, distributing USD surpluses/deficits to counter-balancing assets without triggering full portfolio sells.
- **Interactive UI Dashboard**: React-based dashboard featuring real-time overview cards, dynamic Chart.js allocation treemaps, asset tables, and BUY/SELL badge history.
- **Web UI Configuration Editor**: Live hot-reload settings configuration page with allocation target safety validation (must sum to 100%).
- **Dry Run Safety Mode**: Order placement safety valve to simulate portfolio rebalancing cycles without risking live capital.
- **Project Infrastructure**: Setup initial MIT License, Security Policy, contributing guidelines, Pull Request template, issue templates, and basic GitHub Actions Java CI build file.
