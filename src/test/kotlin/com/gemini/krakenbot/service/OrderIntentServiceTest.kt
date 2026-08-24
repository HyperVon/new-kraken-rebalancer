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
        "rethrows reconciliation conflicts from a repository transaction wrapper" {
            runTest {
                coEvery {
                    repository.resolve(17, OrderIntentState.CONFIRMED, "evidence", any(), null)
                } throws IOException(
                    "transaction failed",
                    OrderIntentReconciliationException("trade identity changed"),
                )

                val failure = shouldThrow<IllegalStateException> {
                    service.resolve(17, OrderIntentState.CONFIRMED, "evidence")
                }

                failure.message shouldBe "trade identity changed"
            }
        }

        "preserves unrelated repository IO failures" {
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

        "preserves unrelated nested state failures" {
            runTest {
                coEvery {
                    repository.resolve(19, OrderIntentState.CONFIRMED, "evidence", any(), null)
                } throws IOException("transaction failed", IllegalStateException("database state changed"))

                val failure = shouldThrow<IOException> {
                    service.resolve(19, OrderIntentState.CONFIRMED, "evidence")
                }

                failure.message shouldBe "transaction failed"
            }
        }
    }
}
