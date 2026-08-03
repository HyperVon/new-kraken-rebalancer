---
name: write-kotest
description: >-
  Write Kotest specs and evaluation/E2E tests — StringSpec init blocks,
  IsolationMode, TestFixtures, FakeKraken vs SimulatedKraken, BigDecimal
  shouldBeEqualComparingTo, in-memory SQLite, and advanceUntilIdle for Flow
  tests. Use when adding or changing JVM/JS tests or EvaluationScenariosTest.
---

# Writing Kotest Unit Tests

Canonical evaluation doc: [`docs/EVALUATION.md`](../../../docs/EVALUATION.md).

## Spec structure

```kotlin
class MyServiceTest : StringSpec() {

    override fun isolationMode() = IsolationMode.InstancePerTest

    init {
        "should do something correctly" {
            // …
        }
    }
}
```

Never use constructor-lambda `StringSpec({ … })`. Prefer
`IsolationMode.InstancePerTest` for mutable fixtures.
Add `@Suppress("unused")` only when the compiler or IDE reports a real warning;
reflection-based discovery does not require it on every spec.

## TestFixtures

Use `src/test/.../TestFixtures.kt` for shared credentials, pair symbols
(`XBTUSD`, `XXBTZUSD`), sync keys, and `DEFAULT_TEST_SETTINGS` /
`DEFAULT_TEST_CONFIG`. Avoid duplicating magic test constants.

## BigDecimal comparisons

```kotlin
import io.kotest.matchers.comparables.shouldBeEqualComparingTo

result.shouldBeEqualComparingTo(BigDecimal("100.50"))
```

Never `.equals()`, `shouldBe` alone on `BigDecimal`, or
`shouldBeEqualByComparingTo`.

## In-memory SQLite

```kotlin
private val db = DatabaseConfig.init(":memory:")
```

Never touch `kraken-rebalancer.db` from tests.

## FakeKrakenService vs SimulatedKrakenService

| Double | Role |
| :--- | :--- |
| **`FakeKrakenService`** | Test-only controllable fake (suppliers, `executedOrders`) — prefer in unit/evaluation tests |
| **`SimulatedKrakenService`** | Production emulator used when `settings.simulation=true` |

Do not confuse the two:

- `EvaluationScenariosTest` → `FakeKrakenService` (controlled behavior).
- `SimulationEvaluationScenariosTest` → production `SimulatedKrakenService`
  stack; use it when validating emulator integration, not unit order math.

```kotlin
val fakeKraken = FakeKrakenService().apply {
    balanceSupplier = {
        mapOf("XXBT" to BigDecimal("0.5"), "ZUSD" to BigDecimal("1000.00"))
    }
}
```

### MockK + Exposed static mocks

- Use the constant
  `TestFixtures.ORG_JETBRAINS_EXPOSED_SQL_TRANSACTIONS_TRANSACTION_API_KT`.
- Always `unmockkStatic(...)` in `finally` or test teardown.
- Prefer real `:memory:` SQLite via `DatabaseConfig.init(":memory:")` unless the
  test targets failure mapping.
- For services:
  `coEvery { kraken.withStableBackend(any()) } coAnswers { firstArg<suspend (KrakenService) -> Any>().invoke(fakeKraken) }`

## Coroutines & Flows

```kotlin
runTest {
    // suspend work
    advanceUntilIdle() // Flow / SharedFlow collectors, rate limiter, loop events
}
```

Opt in `ExperimentalCoroutinesApi` when using `advanceUntilIdle`.

### Loop & config-change tests

- Emit settings while the loop sits in `delay(loopDelaySeconds)` — expect
  `collectLatest` to cancel and restart with the new settings.
- Assert `CancellationException` is not logged as a cycle failure.
- Use `runTest` + `advanceUntilIdle()` after emissions.
- For SSE: assert the initial snapshot send, then subsequent flow events.

## Evaluation / E2E / chaos

### When EvaluationScenariosTest is mandatory

Add or update a scenario when the diff touches:

- `RebalancerEngine`, `PortfolioCalculations`, `OrderExecutorImpl`, or
  sell/settle/buy ordering
- `DynamicKrakenService` routing, `dryRun` / `simulation`, or `cl_ord_id` mapping
- Fiat correction, dust threshold, ATH/drawdown deployment, 99% buy cap,
  95% settle

Workflow:

1. Run the suite:

   ```bash
   ./gradlew test --tests "com.gemini.krakenbot.EvaluationScenariosTest" \
     -x jacocoTestCoverageVerification
   ```

2. Refresh the outcomes table in `docs/EVALUATION.md` from
   `build/reports/scenarios_evaluation_report.md`.
3. Prefer `FakeKrakenService` + `TestFixtures.DEFAULT_TEST_SETTINGS`; never a
   file-backed DB in unit tests.

- Suite: `EvaluationScenariosTest` (34 scenarios)
- Report: `build/reports/scenarios_evaluation_report.md` (absolute paths redacted)
- Principles: no absolute paths, FakeKraken, virtual time, SSE multi-subscriber checks

## Kotlin/JS tests

Under `frontend-js/src/jsTest/` — Kotest `StringSpec` + `IsolationMode.InstancePerTest`,
mock DOM, clean up nodes. Prefer `CssClass` / `HtmlIds` / `HtmlTags` / `TestDomBuilders`
for internal setup and non-contract mechanics. When the test verifies emitted
DOM/HTML, JSON, HTTP, route, header, or persisted-key spelling, use independent
raw expected literals instead of the generated production catalog. A shared
test mirror of production constants is not an independent oracle.
Coverage gates: 90% statements/functions/lines, 75% branches.

History chart zoom/scrubber specs should cover:

- Scrubber **disabled** when not zoomed; **enabled** after zoom
- Pan via `chart.zoomScale('x', {min, max})` (assert call args)
- Fallback when `zoomScale` is absent (options.scales + `update`)
- `onZoomComplete` re-attached after `JSON.parse(JSON.stringify(…))` clone
- No-op pan when full span == current span

```bash
./gradlew :frontend-js:jsBrowserTest
./gradlew test jacocoTestReport jacocoTestCoverageVerification
```

## Test necessity (no test slop)

Each test is the cheapest way to kill a **distinct defect class**:

- If you cannot name the failure mode a new test uniquely covers, do not add
  it. Cosmetic input variation with identical structure is duplication; use
  table-driven rows only when each row kills a distinct variant.
- Skip inputs the type system or the caller's contract makes impossible (null
  on a non-nullable internal parameter; an already-validated shape deep inside
  the boundary). Keep unlikely-but-possible boundary cases (Kraken responses,
  config files, user input) — they are cheap insurance.
- No coverage padding: assertions that only prove "does not throw" or
  "is not null", or that restate a stubbed mock's return value with no
  production logic in between.
- Do not test the framework: getters, constructors, and delegation with no
  logic unless an external contract depends on them.

Full rubric: [ai-slop-detector](../ai-slop-detector/SKILL.md) § Test necessity.

## Checklist

- [ ] `init` block + isolation mode; suppress only demonstrated warnings
- [ ] Each test kills a distinct defect class; no impossible-case or coverage-padding tests
- [ ] `shouldBeEqualComparingTo`; `:memory:` DB; `TestFixtures` where useful
- [ ] FakeKraken for tests; Flow tests use `advanceUntilIdle`
- [ ] Evaluation scenarios updated when algorithm behavior changes
- [ ] JS History zoom/scrubber branches covered when touching `HistoryZoom.kt`
- [ ] No machine paths/hostnames; no FQNs
