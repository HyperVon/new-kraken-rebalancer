package com.gemini.krakenbot.service

import com.gemini.krakenbot.model.OrderIntentReconciliationException
import com.gemini.krakenbot.model.OrderIntentState
import com.gemini.krakenbot.repository.OrderIntentRepository
import com.gemini.krakenbot.service.impl.OrderIntentServiceImpl
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.*
import kotlinx.coroutines.test.runTest
import java.io.IOException

@Suppress("unused")
class OrderIntentServiceTest : StringSpec() {
    override fun isolationMode() = IsolationMode.InstancePerTest

    private val repository = mockk<OrderIntentRepository>()
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
                } throws OrderIntentReconciliationException("trade identity changed")

                val failure = shouldThrow<OrderIntentReconciliationException> {
                    service.resolve(17, OrderIntentState.CONFIRMED, "evidence")
                }

                failure.message shouldBe "trade identity changed"
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
    }
}
