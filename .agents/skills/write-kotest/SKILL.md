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

Use shouldBeEqualComparingTo for BigDecimal. Never use equals or shouldBe
alone for BigDecimal comparisons.

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

## Kotlin/JS tests

Under frontend-js/src/jsTest, use Kotest StringSpec and isolate mutable DOM
state. Use shared catalogs for internal setup, but raw independent literals
when checking emitted DOM, JSON, HTTP, route, header, or persisted-key spelling.
Coverage gates are configured in frontend-js/karma.config.d/coverage.js.

## Checklist

- [ ] StringSpec init block and suitable isolation
- [ ] BigDecimal uses shouldBeEqualComparingTo
- [ ] Tests use fake credentials and isolated database paths
- [ ] Flow tests use runTest and deterministic scheduling
- [ ] Each test covers a distinct failure mode
- [ ] Focused contracts are mapped in docs/EVALUATION.md
