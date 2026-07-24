---
name: autonomous-code-optimizer
description: Autonomous multi-pass codebase audit and refactoring loop — systematically scans, refactors, optimizes, and verifies all Kotlin JVM, Kotlin/JS, Exposed repositories, HTML DSL views, and Kotest specs until zero improvements remain.
---

# Autonomous Code Optimizer

Use this skill when tasked with performing an exhaustive, end-to-end codebase optimization loop, or when invoked during a `/goal` autonomous task run.

## Multi-Pass Optimization Loop

Execute the following 4 sequential passes in an iterative loop. After completing Pass 4, return to Pass 1. **Terminate ONLY when a complete 4-pass cycle produces zero new refactorings, zero warnings, and zero test failures.**

---

### Pass 1: Static Quality & Security Audit

Scan all files across `:common`, JVM backend (`src/`), and client JS (`frontend-js/`) for quality and security violations:

1. **Fully Qualified Names (FQNs)**: Replace all inline package names (e.g. `com.gemini.krakenbot...`) with explicit `import` declarations.
2. **Absolute User Paths**: Remove any hardcoded `/Users/...` or user-specific filesystem paths in source code, configs, or tests.
3. **Magic Strings & Constants**: Move inline UI labels, HTML IDs, CSS class names, and domain constants into the shared KMP core (`common/src/commonMain/`).
4. **Secrets & Credentials**: Ensure no secret API keys, private tokens, or passwords exist in source files or tracked properties.

Execute the static scanner script:

```bash
./.agents/skills/autonomous-code-optimizer/scripts/audit_and_verify.sh
```

---

### Pass 2: Financial Precision & Concurrency Safety

Audit financial calculation paths and coroutine concurrency:

1. **`BigDecimal` Precision (CRITICAL)**:
   - Ensure zero usage of `Double` or `Float` in currency valuations, balances, prices, or volumes.
   - Enforce 8 decimal places for crypto quantities and 2 decimal places for USD totals.
   - Verify unit test assertions use `compareTo() == 0` or `shouldBeEqualComparingTo` (NEVER `.equals()`).
2. **Order Execution Safety**:
   - Verify overweight sell legs execute *before* underweight buy legs.
   - Confirm buy allocations are capped to 99% of available USD cash.
3. **Coroutine Safety**:
   - Verify database operations and network IO use `withContext(Dispatchers.IO)`.
   - Ensure rate-limited Kraken API calls use `Mutex` locks with exponential backoff on `EGeneral:Temporary lockout`.

---

### Pass 3: Architecture & Layout Refactoring

Audit application components for Single Responsibility Principle (SRP) and layout modularity:

1. **SRP Decomposition**: Split monolithic classes into clean Orchestrator (`PortfolioManagerImpl`), Brain (`PortfolioAnalyzerImpl`), and Brawn (`OrderExecutorImpl`) responsibilities.
2. **Exposed ORM Repositories**: Verify write operations use `database.safeTransaction`, updates target primary key IDs, and deletes cascade child records before parent records.
3. **Ktor HTML DSL & CSS Views**: Refactor recurring HTML/CSS component structures into modular layout helpers (`Layouts.kt`). Ensure CSS builders use strongly typed `kotlinx-css` properties and `CssTheme` tokens.
4. **Kotlin/JS (`:frontend-js`) Safety**: Ensure Chart.js options are deep-cloned via `JSON.parse(JSON.stringify(...))` and DOM event listeners are cleaned up on detachment.

---

### Pass 4: Verification & Convergence Check

After making refactoring edits in any pass, immediately execute the verification suite:

```bash
./.agents/skills/commit-and-push/scripts/pre_commit_check.sh
```

- **If tests fail**: Immediately revert or fix the specific breaking change before continuing.
- **If tests pass & new improvements were made**: Log the completed refactorings and loop back to Pass 1.
- **Convergence Rule**: Stop looping **ONLY** when a complete pass over the entire codebase yields **0 new issues, 0 compiler warnings, 0 markdown lint errors, and 100% passing tests**.

---

## Checklist

Before declaring the autonomous optimization task complete:

- [ ] Complete 4-pass audit loop executed with zero remaining issues
- [ ] No Fully Qualified Names (FQNs) or absolute `/Users/` paths remain
- [ ] All financial calculations strictly use `BigDecimal` scale 8/2
- [ ] Backend (`./gradlew test`) and frontend (`./gradlew :frontend-js:jsTest`) test suites pass with 100% success
- [ ] Markdown files formatted and linted (`npx markdownlint-cli`)
