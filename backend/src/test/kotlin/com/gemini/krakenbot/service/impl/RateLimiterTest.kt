@file:OptIn(ExperimentalCoroutinesApi::class)

package com.gemini.krakenbot.service.impl

import com.gemini.krakenbot.model.KrakenApiConstants
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.math.absoluteValue

class RateLimiterTest : StringSpec() {

    override fun isolationMode() = IsolationMode.InstancePerTest

    init {
        "acquireWithCost increments counter" {
            runTest {
                val limiter = RateLimiter()
                limiter.acquireWithCost(1.0)
                (limiter.getCurrentCounter() >= 0.95).shouldBeTrue()
            }
        }

        "acquireWithCost with multiple calls" {
            runTest {
                val limiter = RateLimiter()
                limiter.acquireWithCost(1.0)
                limiter.acquireWithCost(2.0)
                (limiter.getCurrentCounter() >= 2.95).shouldBeTrue()
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
            runTest {
                val limiter = RateLimiter(safeLimit = 20.0, decayRate = 0.5)
                limiter.getCurrentCounter() shouldBe 0.0
            }
        }

        "acquireWithCost delays when limit exceeded" {
            runTest {
                val safeLimit = 2.0
                val limiter = RateLimiter(safeLimit = safeLimit, decayRate = 1.0, clock = { currentTime })
                limiter.acquireWithCost(1.5)

                // Second call asks for 1.0, total 2.5 > 2.0. Needs delay.
                // neededDecay = 2.5 - 2.0 = 0.5; waitSeconds = 0.5 / 1.0 = 0.5s.
                // Virtual time: without advanceUntilIdle the delay never completes.
                limiter.acquireWithCost(1.0)
                advanceUntilIdle()
                ((limiter.getCurrentCounter() - safeLimit).absoluteValue < 0.05).shouldBeTrue()
            }
        }

        "subsequent acquire after limit wait raises counter or lands at safeLimit plus cost" {
            runTest {
                val safeLimit = 2.0
                val limiter = RateLimiter(safeLimit = safeLimit, decayRate = 1.0, clock = { currentTime })
                limiter.acquireWithCost(1.5)
                limiter.acquireWithCost(1.0)
                advanceUntilIdle()

                val counterBeforeSmallAcquire = limiter.getCurrentCounter()
                ((counterBeforeSmallAcquire - safeLimit).absoluteValue < 0.05).shouldBeTrue()

                val returnedAfterSmallAcquire = limiter.acquireWithCost(0.5)
                advanceUntilIdle()

                val incrementedAbovePrevious = returnedAfterSmallAcquire > counterBeforeSmallAcquire + 0.01
                val landedNearSafeLimit = (returnedAfterSmallAcquire - safeLimit).absoluteValue < 0.05
                (incrementedAbovePrevious || landedNearSafeLimit).shouldBeTrue()
            }
        }

        "getCurrentCounter reflects decay toward zero after reset stays zero" {
            runTest {
                val limiter = RateLimiter(safeLimit = 12.0, decayRate = 1.0)
                limiter.acquireWithCost(3.0)
                limiter.reset()
                limiter.getCurrentCounter() shouldBe 0.0
                advanceUntilIdle()
                limiter.getCurrentCounter() shouldBe 0.0
            }
        }

        "injected clock decays counter deterministically without waiting" {
            runTest {
                var nowMs = 1_000_000L
                val limiter = RateLimiter(
                    safeLimit = 12.0,
                    decayRate = 1.0,
                    clock = { nowMs },
                )
                limiter.acquireWithCost(5.0)
                limiter.getCurrentCounter() shouldBe 5.0

                nowMs += 2_000L // 2s * decayRate 1.0 → counter falls by 2
                limiter.getCurrentCounter() shouldBe 3.0

                nowMs += 10_000L // fully decayed
                limiter.getCurrentCounter() shouldBe 0.0
            }
        }

        "backward clock movement does not inflate the counter" {
            runTest {
                var nowMs = 1_000_000L
                val limiter = RateLimiter(
                    safeLimit = 12.0,
                    decayRate = 1.0,
                    clock = { nowMs },
                )
                limiter.acquireWithCost(5.0)

                nowMs -= 60_000L

                limiter.getCurrentCounter() shouldBe 5.0
                limiter.acquireWithCost(1.0) shouldBe 6.0
            }
        }

        "acquireWithCost does not hold mutex across delay (no HOL blocking)" {
            runTest {
                val safeLimit = 2.0
                val limiter = RateLimiter(safeLimit = safeLimit, decayRate = 1.0, clock = { currentTime })
                // Fill so next cost=1.0 needs ~0.5s wait (same math as limit-exceeded test).
                limiter.acquireWithCost(1.5)

                val waiter = async {
                    limiter.acquireWithCost(1.0)
                }
                // Run until waiter is suspended in delay (mutex must already be released).
                runCurrent()
                waiter.isCompleted.shouldBeFalse()

                // A second acquire that fits under the limit must complete without waiting
                // for the waiter's ~500ms delay. If the mutex were held across delay, this
                // would still be incomplete after runCurrent().
                val concurrent = async {
                    limiter.acquireWithCost(0.4)
                }
                runCurrent()
                concurrent.isCompleted.shouldBeTrue()
                waiter.isCompleted.shouldBeFalse()
                (limiter.getCurrentCounter() >= 1.8).shouldBeTrue()

                // Do not drain the waiter: proving HOL only needs concurrent progress during
                // the delay. Cancel so runTest is not left with an unfinished coroutine.
                waiter.cancel()
            }
        }

        "acquireWithCost throws IllegalArgumentException when cost is zero or negative" {
            runTest {
                val limiter = RateLimiter(safeLimit = 12.0)
                shouldThrow<IllegalArgumentException> {
                    limiter.acquireWithCost(0.0)
                }
                shouldThrow<IllegalArgumentException> {
                    limiter.acquireWithCost(-1.0)
                }
            }
        }

        "acquireWithCost throws IllegalArgumentException when cost exceeds safeLimit" {
            runTest {
                val limiter = RateLimiter(safeLimit = 12.0)
                shouldThrow<IllegalArgumentException> {
                    limiter.acquireWithCost(15.0)
                }
            }
        }

        "public limiter spaces requests by one second" {
            runTest {
                var nowMs = 10_000L
                val limiter = PublicRateLimiter(clock = { nowMs })
                limiter.acquire()

                val waiter = async { limiter.acquire() }
                runCurrent()
                waiter.isCompleted.shouldBeFalse()

                nowMs += 1_000L
                advanceTimeBy(1_000L)
                waiter.await()
            }
        }

        "public limiter rebases after a backward clock step" {
            runTest {
                var nowMs = 10_000L
                val limiter = PublicRateLimiter(clock = { nowMs })
                limiter.acquire()

                nowMs = 0L
                val waiter = async { limiter.acquire() }
                runCurrent()
                waiter.isCompleted.shouldBeFalse()

                nowMs = 1_000L
                advanceTimeBy(1_000L)
                waiter.await()
            }
        }

        "private endpoint costs follow Kraken endpoint classes" {
            krakenPrivateEndpointCost(KrakenApiConstants.PATH_ADD_ORDER) shouldBe 0.0
            krakenPrivateEndpointCost("/0/private/CancelOrder") shouldBe 0.0
            krakenPrivateEndpointCost("/0/private/TradesHistory") shouldBe 4.0
            krakenPrivateEndpointCost("/0/private/Balance") shouldBe 1.0
        }
    }
}
