@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.gemini.krakenbot.service.impl

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest

class RateLimiterTest : StringSpec({
    "acquireWithCost increments counter" {
        runTest {
            val limiter = RateLimiter()
            limiter.acquireWithCost(1.0)
            limiter.getCurrentCounter() shouldBe 1.0
        }
    }

    "acquireWithCost with multiple calls" {
        runTest {
            val limiter = RateLimiter()
            limiter.acquireWithCost(1.0)
            limiter.acquireWithCost(2.0)
            limiter.getCurrentCounter() shouldBe 3.0
        }
    }

    "reset clears counter" {
        runTest {
            val limiter = RateLimiter()
            limiter.acquireWithCost(5.0)
            limiter.reset()
            limiter.getCurrentCounter() shouldBe 0.0
        }
    }

    "constructor accepts custom limits" {
        val limiter = RateLimiter(safeLimit = 20.0, decayRate = 0.5)
        limiter.getCurrentCounter() shouldBe 0.0
    }

    "getRateLimitFlow emits events" {
        runTest {
            val limiter = RateLimiter()
            val events = mutableListOf<RateLimitEvent>()
            val job = this.launch {
                limiter.getRateLimitFlow().collect { events.add(it) }
            }
            advanceUntilIdle()
            limiter.acquireWithCost(1.0)
            advanceUntilIdle()
            events.size shouldBe 1
            events[0] shouldBe RateLimitAcquired(1.0)
            job.cancel()
        }
    }

    "acquireWithCost delays when limit exceeded" {
        runTest {
            val limiter = RateLimiter(safeLimit = 2.0, decayRate = 1.0)
            val events = mutableListOf<RateLimitEvent>()
            val job = launch {
                limiter.getRateLimitFlow().collect { events.add(it) }
            }
            advanceUntilIdle()
            limiter.acquireWithCost(1.5)
            advanceUntilIdle()

            // Second call asks for 1.0, total 2.5 > 2.0. Needs delay.
            // neededDecay = 2.5 - 2.0 = 0.5
            // waitSeconds = 0.5 / 1.0 = 0.5s = 500ms
            limiter.acquireWithCost(1.0)
            advanceUntilIdle()

            events.filterIsInstance<RateLimitAcquired>().shouldHaveSize(2)
            events.filterIsInstance<RateLimitAcquired>()[0].cost shouldBe 1.5
            events.filterIsInstance<RateLimitAcquired>()[1].cost shouldBe 1.0
            events.filterIsInstance<RateLimitExceeded>().single().waitMs shouldBe 500
            
            job.cancel()
        }
    }
})
