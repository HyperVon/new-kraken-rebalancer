package com.gemini.krakenbot.service

import com.gemini.krakenbot.domain.OrderResult
import com.gemini.krakenbot.model.OrderIntent
import com.gemini.krakenbot.model.OrderIntentReconciliationException
import com.gemini.krakenbot.model.OrderIntentState
import com.gemini.krakenbot.repository.ExecutionOrderIntentRepository
import com.gemini.krakenbot.service.impl.OrderIntentServiceImpl
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.*
import kotlinx.coroutines.test.runTest
import java.io.IOException
import java.math.BigDecimal
import java.time.Instant

@Suppress("unused")
class OrderIntentServiceTest : StringSpec() {
    override fun isolationMode() = IsolationMode.InstancePerTest

    private val repository = mockk<ExecutionOrderIntentRepository>()
    private val service = OrderIntentServiceImpl(repository)

    init {
        "rejects oversized manual resolution evidence and transaction ids" {
            runTest {
                val oversizedEvidence = "x".repeat(501)
                shouldThrow<IllegalArgumentException> {
                    service.resolve(20, OrderIntentState.CONFIRMED, oversizedEvidence)
                }
                shouldThrow<IllegalArgumentException> {
                    service.resolve(20, OrderIntentState.CONFIRMED, "evidence", "t".repeat(65))
                }
                coVerify(exactly = 0) { repository.resolve(any(), any(), any(), any(), any()) }
            }
        }

        "propagates reconciliation conflicts from repository" {
            runTest {
                coEvery {
                    repository.resolve(17, OrderIntentState.CONFIRMED, "evidence", any(), null)
                } throws OrderIntentReconciliationException("execution intent state changed")

                val failure = shouldThrow<OrderIntentReconciliationException> {
                    service.resolve(17, OrderIntentState.CONFIRMED, "evidence")
                }

                failure.message shouldBe "execution intent state changed"
            }
        }

        "preserves repository IO failures" {
            runTest {
                coEvery {
                    repository.resolve(18, OrderIntentState.REJECTED, "evidence", any(), null)
                } throws IOException("database unavailable")

                val failure = shouldThrow<IOException> {
                    service.resolve(18, OrderIntentState.REJECTED, "evidence")
                }

                failure.message shouldBe "database unavailable"
            }
        }

        "delegates readiness check to repository" {
            runTest {
                coEvery { repository.ensureReadyForSubmission() } just Runs
                service.ensureReadyForSubmission()
                coVerify(exactly = 1) { repository.ensureReadyForSubmission() }
            }
        }

        "ensureAccountBindingForSubmission requires verifier" {
            runTest {
                val failure = shouldThrow<IllegalStateException> {
                    service.ensureAccountBindingForSubmission()
                }
                failure.message shouldBe "Live order submission requires the execution account binding service."

                val verifier = mockk<ExecutionAccountBindingVerifier>()
                coEvery { verifier.ensureVerifiedForSubmission() } just Runs
                val serviceWithVerifier = OrderIntentServiceImpl(repository, verifier)
                serviceWithVerifier.ensureAccountBindingForSubmission()
                coVerify(exactly = 1) { verifier.ensureVerifiedForSubmission() }
            }
        }

        "savePending forces PENDING state" {
            runTest {
                val intent = OrderIntent(
                    cycleId = "c1",
                    clientOrderId = "cl-1",
                    pair = "XXBTZUSD",
                    symbol = "BTC",
                    side = "buy",
                    volume = BigDecimal("0.1"),
                    usdAmount = BigDecimal("5000.00"),
                    expectedPrice = BigDecimal("50000"),
                    state = OrderIntentState.UNCERTAIN,
                    createdAt = Instant.now(),
                )
                coEvery { repository.savePending(any()) } returns 42

                service.savePending(intent) shouldBe 42
                coVerify { repository.savePending(match { it.state == OrderIntentState.PENDING }) }
            }
        }

        "recordOutcome maps uncertain, confirmed, and rejected states" {
            runTest {
                coEvery { repository.recordOutcome(any(), any(), any(), any(), any(), any()) } returns true

                // Uncertain
                val uncertainResult = OrderResult(
                    pair = "XXBTZUSD",
                    side = "buy",
                    success = false,
                    volume = BigDecimal("0.1"),
                    submissionUncertain = true,
                    errorMessage = "timeout",
                )
                service.recordOutcome(1, uncertainResult) shouldBe true
                coVerify {
                    repository.recordOutcome(
                        1,
                        OrderIntentState.UNCERTAIN,
                        null,
                        "timeout",
                        null,
                        BigDecimal("0.1"),
                    )
                }

                // Confirmed
                val successResult = OrderResult(
                    pair = "XXBTZUSD",
                    side = "buy",
                    success = true,
                    volume = BigDecimal("0.1"),
                    orderTxid = "tx-123",
                )
                service.recordOutcome(2, successResult) shouldBe true
                coVerify {
                    repository.recordOutcome(
                        2,
                        OrderIntentState.CONFIRMED,
                        "tx-123",
                        null,
                        any(),
                        BigDecimal("0.1"),
                    )
                }

                // Rejected
                val failureResult = OrderResult(
                    pair = "XXBTZUSD",
                    side = "buy",
                    success = false,
                    volume = BigDecimal("0.1"),
                    errorMessage = "rejected",
                )
                service.recordOutcome(3, failureResult) shouldBe true
                coVerify {
                    repository.recordOutcome(
                        3,
                        OrderIntentState.REJECTED,
                        null,
                        "rejected",
                        any(),
                        BigDecimal("0.1"),
                    )
                }
            }
        }

        "delegates unresolved intent queries to repository" {
            runTest {
                coEvery { repository.hasUnresolvedIntents() } returns true
                coEvery { repository.countUnresolvedIntents() } returns 5L
                coEvery { repository.loadUnresolvedIntents() } returns emptyList()

                service.hasUnresolvedIntents() shouldBe true
                service.countUnresolvedIntents() shouldBe 5L
                service.getUnresolvedIntents() shouldBe emptyList()
            }
        }

        "resolve validates state, evidence, and repository return value" {
            runTest {
                shouldThrow<IllegalArgumentException> {
                    service.resolve(10, OrderIntentState.PENDING, "evidence")
                }
                shouldThrow<IllegalArgumentException> {
                    service.resolve(10, OrderIntentState.UNCERTAIN, "evidence")
                }
                shouldThrow<IllegalArgumentException> {
                    service.resolve(10, OrderIntentState.CONFIRMED, "   ")
                }

                // Repository returns false (missing or already resolved)
                coEvery { repository.resolve(10, OrderIntentState.CONFIRMED, "valid evidence", any(), "tx-1") } returns
                    false
                val missingFailure = shouldThrow<IllegalStateException> {
                    service.resolve(10, OrderIntentState.CONFIRMED, "valid evidence", "tx-1")
                }
                missingFailure.message shouldBe "Order intent 10 is missing or already resolved."

                // Repository returns true (success)
                coEvery { repository.resolve(11, OrderIntentState.REJECTED, "rejected evidence", any(), null) } returns
                    true
                service.resolve(11, OrderIntentState.REJECTED, "rejected evidence", null)
                coVerify { repository.resolve(11, OrderIntentState.REJECTED, "rejected evidence", any(), null) }
            }
        }
    }
}
