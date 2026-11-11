---
name: write-kotest
description: >-
  Write focused JVM and Kotlin/JS Kotest specs for rebalancing, live-order
  safety, Dashboard, Settings, and current-cycle behavior.
---

# Writing Kotest Unit Tests

The current contract map is in docs/EVALUATION.md.

## Spec structure

Use StringSpec with an init block and prefer InstancePerTest for mutable
fixtures. Do not use constructor-lambda StringSpec. Add suppressions only when
the compiler or IDE reports a real warning.

## Comparisons and isolation

Use `shouldBeEqualComparingTo` for every BigDecimal assertion. Never
`shouldBeEqualByComparingTo`, `.equals()`, `shouldBe` alone, or `hashCode` on
money values — those are banned anti-patterns, not synonyms.

Contract-assert with raw expected numbers (`"0.12".toBigDecimal()`), not values
computed via the same helper under test. Assert rounding through `toUsdScale()`
/ `toCryptoScale()` outcomes (`RoundingMode.HALF_UP` volumes), not string
formatting.

Never touch an operator's configured data directory from tests. Use in-memory
or temporary SQLite paths for execution-journal tests.

## Test doubles and coroutines

Use FakeKrakenService for controlled cases and SimulatedKrakenService when the
production emulator itself is under test. Keep credentials and account
identifiers fake.

Use runTest for suspending functions and advanceUntilIdle for Flow or loop
events. Do not resolve races with arbitrary sleeps or longer fixed delays.

For SSE, assert the initial revision and subsequent Dashboard invalidations,
plus compatibility of the legacy snapshot/activity stream. For settings
changes, verify collectLatest cancels an idle delay and restarts with the new
configuration. Cancellation must not be recorded as a cycle failure.

## Focused contract coverage

Add or update tests when changes affect:

- Fixed USD targets, rebalancing math, fiat correction, dust thresholds, or
  sell/settle/buy ordering.
- OrderExecutor, settled-cash limits, buy reserve, or durable live-order
  intent safety.
- DynamicKrakenService, simulation/dryRun behavior, or Settings parsing.
- Dashboard current-cycle snapshots, in-memory activity, health/readiness, or
  SSE.
- Execution-journal initialization, account binding, or restart recovery.

Prefer observable domain effects to invocation counts. Each test should cover a
distinct failure mode. Avoid coverage padding and framework-only tests.

Raw independent expected literals are required in **all** tests, not just JS:
any test asserting emitted HTML/IDs/classes, HTTP routes/headers, JSON keys, or
persisted metadata spelling must use the literal string, not `Routes.*` /
`HtmlIds.*` / `ViewText.*` values — a wrong catalog value would otherwise pass
implementation and assertion together. Typed catalogs stay fine for
non-assertion setup (fixtures, navigation, request construction). Review
anti-pattern: `response shouldBe Routes.SETTINGS` — replace with the literal
path.

## Kotlin/JS tests

Under frontend-js/src/jsTest, use Kotest StringSpec and isolate mutable DOM
state. Use shared catalogs for internal setup, and the raw-literal rule above
when checking emitted DOM, JSON, HTTP, route, header, or persisted-key
spelling. Coverage gates are configured in frontend-js/karma.config.d/coverage.js.

Kotlin/JS Kotest discovery requires `alias(libs.plugins.ksp)` plus the
`io.kotest` plugin in `frontend-js/build.gradle.kts`; never remove them as
"unused" during cleanup. After adding or renaming a jsTest spec, run
`./gradlew :frontend-js:jsBrowserTest` and confirm the spec name appears in the
output — a run reporting 0 executed tests is a failure, not a pass. New JS test
source sets need the same KSP + `kotest-framework-engine` setup before their
specs exist.

## Checklist

- [ ] StringSpec init block and suitable isolation
- [ ] BigDecimal uses shouldBeEqualComparingTo (never shouldBeEqualByComparingTo / .equals())
- [ ] Contract assertions use raw expected literals, not shared catalog values
- [ ] Tests use fake credentials and isolated database paths
- [ ] Flow tests use runTest and deterministic scheduling
- [ ] Each test covers a distinct failure mode
- [ ] jsTest runs report the new spec by name (0 executed tests is a failure)
- [ ] Focused contracts are mapped in docs/EVALUATION.md
