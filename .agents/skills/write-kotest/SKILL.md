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

- Suite: `EvaluationScenariosTest` (33 scenarios)
- Report: `build/reports/scenarios_evaluation_report.md` (absolute paths redacted)
- Principles: no absolute paths, FakeKraken, virtual time, SSE multi-subscriber checks

## Kotlin/JS tests

Under `frontend-js/src/jsTest/` — Kotest `StringSpec` + `IsolationMode.InstancePerTest`,
mock DOM, clean up nodes. Prefer `CssClass` / `HtmlIds` / `HtmlTags` / `TestDomBuilders`
over raw class names and HTML blobs.
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

## Checklist

- [ ] `init` block + isolation mode; suppress only demonstrated warnings
- [ ] `shouldBeEqualComparingTo`; `:memory:` DB; `TestFixtures` where useful
- [ ] FakeKraken for tests; Flow tests use `advanceUntilIdle`
- [ ] Evaluation scenarios updated when algorithm behavior changes
- [ ] JS History zoom/scrubber branches covered when touching `History.kt`
- [ ] No machine paths/hostnames; no FQNs
