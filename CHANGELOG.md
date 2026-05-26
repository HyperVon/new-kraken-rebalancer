# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

---

## [Unreleased]
### Added
- **Advanced E2E Java Tests**: Introduced highly rigorous Java test suites using Spring's `MockRestServiceServer` to simulate Kraken API behavior (`KrakenE2ETest`, `SerializationParityTest`, `ResilienceChaosTest`, `PrecisionRoundingFuzzTest`). These strictly validate precision handling, JSON backwards compatibility, and resilient failure states. Increased test suite to 98 unit tests, achieving **99.4% line coverage** and **95.5% branch coverage**.

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
