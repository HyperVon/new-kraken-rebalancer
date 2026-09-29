package com.gemini.krakenbot.replay

import io.kotest.core.annotation.Condition
import io.kotest.core.spec.Spec
import kotlin.reflect.KClass

/**
 * Enables a fixture-gated replay spec only when its private fixture is configured.
 *
 * Without this, a spec whose fixture is absent still runs and has to pass somehow — and the only
 * way to pass is a vacuous assertion, which reports as a green test in CI while measuring nothing.
 * A disabled spec reports as skipped, so the absence of a measurement stays visible.
 */
class ReplayFixtureConfiguredCondition : Condition {
    override fun evaluate(kclass: KClass<out Spec>): Boolean = replayFixturePath() != null
}

/** The configured replay fixture, or `null` when this run has no private fixture to measure. */
fun replayFixturePath(): String? = System.getenv("REPLAY_FIXTURE_PATH")?.takeIf { it.isNotBlank() }
