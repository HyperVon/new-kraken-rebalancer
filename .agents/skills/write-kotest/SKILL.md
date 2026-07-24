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
@Suppress("unused")
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

Do not confuse the two. Evaluation suite uses `FakeKrakenService` — see
`EvaluationScenariosTest`.

```kotlin
val fakeKraken = FakeKrakenService().apply {
    balanceSupplier = {
        mapOf("XXBT" to BigDecimal("0.5"), "ZUSD" to BigDecimal("1000.00"))
    }
}
```

## Coroutines & Flows

```kotlin
runTest {
    // suspend work
    advanceUntilIdle() // Flow / SharedFlow collectors, rate limiter, loop events
}
```

Opt in `ExperimentalCoroutinesApi` when using `advanceUntilIdle`.

## Evaluation / E2E / chaos

- Suite: `EvaluationScenariosTest` (~30 scenarios)
- Report: `build/reports/scenarios_evaluation_report.md`
- Run: `./gradlew test --tests "com.gemini.krakenbot.EvaluationScenariosTest"`
- Principles: no absolute paths, FakeKraken, virtual time, SSE multi-subscriber checks

## Kotlin/JS tests

Under `frontend-js/src/jsTest/` — `kotlin.test`, mock DOM, clean up nodes.
Coverage gates: 90% statements/functions/lines, 75% branches.

History chart zoom/scrubber specs should cover:

- Scrubber **disabled** when not zoomed; **enabled** after zoom
- Pan via `chart.zoomScale('x', {min, max})` (assert call args)
- Fallback when `zoomScale` is absent (options.scales + `update`)
- `onZoomComplete` re-attached after `JSON.parse(JSON.stringify(…))` clone
- No-op pan when full span == current span

```bash
./gradlew :frontend-js:jsTest
./gradlew test jacocoTestReport jacocoTestCoverageVerification
```

## Checklist

- [ ] `init` block + `@Suppress("unused")` + isolation mode
- [ ] `shouldBeEqualComparingTo`; `:memory:` DB; `TestFixtures` where useful
- [ ] FakeKraken for tests; Flow tests use `advanceUntilIdle`
- [ ] Evaluation scenarios updated when algorithm behavior changes
- [ ] JS History zoom/scrubber branches covered when touching `History.kt`
- [ ] No machine paths/hostnames; no FQNs
