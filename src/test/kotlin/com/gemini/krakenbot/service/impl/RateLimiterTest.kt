package com.gemini.krakenbot.service.impl

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
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
})
