---
name: write-kotest
description: Write Kotest unit tests following the project's established patterns — StringSpec init blocks, BigDecimal comparisons, in-memory SQLite, and FakeKrakenService test doubles.
---

# Writing Kotest Unit Tests

When writing or modifying unit tests for this project, follow these patterns
exactly.

## Spec Structure

Always use `StringSpec` with a standard class body `init { ... }` block.
Apply `@Suppress("unused")` to the class to prevent IDE warnings from
reflection-based test discovery.

```kotlin
@Suppress("unused")
class MyServiceTest : StringSpec() {

    override fun isolationMode() = IsolationMode.InstancePerTest

    init {
        "should do something correctly" {
            // Test implementation
        }

        "should handle edge case" {
            // Test implementation
        }
    }
}
```

**Never** use the constructor-lambda form `StringSpec({ ... })`. It causes test
discovery failures in Gradle and IDE runners.

## BigDecimal Comparisons

**Never** use `.equals()` to compare `BigDecimal` values. Scale differences
(e.g. `1.0` vs `1.00`) cause `.equals()` to return false.

```kotlin
// CORRECT:
result.shouldBeEqualComparingTo(BigDecimal("100.50"))
result.compareTo(expected) shouldBe 0

// WRONG — will fail on scale differences:
result shouldBe BigDecimal("100.50")
result.equals(expected) shouldBe true
```

Import the matcher:

```kotlin
import io.kotest.matchers.comparables.shouldBeEqualComparingTo
```

## In-Memory Database Isolation

All database tests **must** use an in-memory SQLite database to prevent
modifying the production `kraken-rebalancer.db` file:

```kotlin
private val db = DatabaseConfig.init(":memory:")
private val repository = SqliteTradeRepositoryImpl(db)
```

**Never** use a file-based database path in tests.

## FakeKrakenService Test Double

For tests involving `KrakenService`, use `FakeKrakenService` instead of
complex `coEvery` mock chains:

```kotlin
val fakeKraken = FakeKrakenService().apply {
    balanceSupplier = {
        mapOf("XXBT" to BigDecimal("0.5"), "ZUSD" to BigDecimal("1000.00"))
    }
    pricesSupplier = {
        mapOf("XBTUSD" to BigDecimal("50000.00"))
    }
}
```

Inspect executed orders via `fakeKraken.executedOrders`.

## Coroutine Testing

Wrap all suspend function calls in `runTest` to use virtual time:

```kotlin
"should complete async operation" {
    runTest {
        val result = myService.doSomethingAsync()
        result shouldBe expected
    }
}
```

Import: `import kotlinx.coroutines.test.runTest`

## IOException Testing for Repositories

Use the delegating `TransactionManager` pattern to test database error paths:

```kotlin
class ThrowingTransactionManager(
    private val delegate: TransactionManager
) : TransactionManager by delegate {
    override fun newTransaction(
        isolation: Int,
        readOnly: Boolean,
        outerTransaction: Transaction?
    ): Transaction {
        throw IOException("Simulated IO failure")
    }
}
```

## Client-Side Kotlin/JS (`:frontend-js`) Testing

For client-side tests under `frontend-js/src/jsTest/kotlin/`, use standard `kotlin.test.Test` annotations and manipulate mock DOM containers cleanly:

```kotlin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.browser.document

class RebalancerJsTest {

    @Test
    fun shouldUpdateMetricsSummaryCardsOnTimeFrameSelect() {
        val container = document.createElement("div")
        container.id = "summary-metrics-container"
        document.body?.appendChild(container)

        // Run JS handler
        updateSummaryMetrics("24h")

        val updatedCard = document.getElementById("metric-high-value")
        assertEquals("$105,250.00", updatedCard?.textContent)

        document.body?.removeChild(container)
    }
}
```

Run client JS tests via Gradle:

```bash
./gradlew :frontend-js:jsTest
```

## Code Coverage & Reporting

Generate JaCoCo HTML code coverage reports to verify JVM backend tests achieve 95%+ coverage:

```bash
./gradlew test jacocoTestReport
```

Inspect HTML coverage reports at `build/reports/jacoco/test/html/index.html`.

## Checklist

Before submitting test code, verify:

- [ ] JVM tests use `init { ... }` block (not constructor lambda)
- [ ] Has `@Suppress("unused")` annotation on Kotest spec classes
- [ ] Uses `shouldBeEqualComparingTo` for `BigDecimal` assertions
- [ ] Uses in-memory database (`:memory:`)
- [ ] Uses `FakeKrakenService` instead of complex mocks
- [ ] Suspend calls wrapped in `runTest`
- [ ] Client Kotlin/JS tests pass cleanly (`./gradlew :frontend-js:jsTest`)
- [ ] Code coverage checked via `./gradlew test jacocoTestReport`
- [ ] Environment agnosticism checked (no machine hostnames like `my-macbook` or user paths in test cases)
- [ ] No FQNs — all types imported at the top
