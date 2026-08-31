package com.gemini.krakenbot.repository

import com.gemini.krakenbot.TestFixtures
import com.gemini.krakenbot.config.DatabaseConfig
import com.gemini.krakenbot.model.OrderIntentReconciliationException
import com.gemini.krakenbot.model.TradeReconciliationConflictException
import com.gemini.krakenbot.repository.impl.readSyncMetadata
import com.gemini.krakenbot.repository.impl.safeReadTransaction
import com.gemini.krakenbot.repository.impl.safeTransaction
import com.gemini.krakenbot.repository.impl.writeSyncMetadata
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.slf4j.LoggerFactory
import java.io.IOException

class RepositoryUtilsTest : StringSpec() {
    override fun isolationMode() = IsolationMode.InstancePerTest

    private val db = DatabaseConfig.init(TestFixtures.MEMORY_)
    private val log = LoggerFactory.getLogger(RepositoryUtilsTest::class.java)

    init {
        "safeTransaction rethrows CancellationException without wrapping" {
            runTest {
                val thrown =
                    shouldThrow<CancellationException> {
                        db.safeTransaction(log, "should not be reached") { throw CancellationException("cancelled") }
                    }
                thrown.message shouldBe "cancelled"
            }
        }

        "safeTransaction rethrows IOException directly without wrapping" {
            runTest {
                val thrown =
                    shouldThrow<IOException> {
                        db.safeTransaction(log, "should not be reached") { throw IOException("direct io") }
                    }
                thrown.message shouldBe "direct io"
            }
        }

        "safeTransaction preserves only intentional reconciliation failures" {
            runTest {
                val orderIntentFailure = shouldThrow<OrderIntentReconciliationException> {
                    db.safeTransaction(log, "should not be reached") {
                        throw OrderIntentReconciliationException("order intent conflict")
                    }
                }
                orderIntentFailure.message shouldBe "order intent conflict"

                val tradeFailure = shouldThrow<TradeReconciliationConflictException> {
                    db.safeTransaction(log, "should not be reached") {
                        throw TradeReconciliationConflictException("trade conflict")
                    }
                }
                tradeFailure.message shouldBe "trade conflict"

                val readOrderIntentFailure = shouldThrow<OrderIntentReconciliationException> {
                    db.safeReadTransaction(log, "should not be reached") {
                        throw OrderIntentReconciliationException("read order intent conflict")
                    }
                }
                readOrderIntentFailure.message shouldBe "read order intent conflict"
            }
        }

        "safeTransaction wraps unrelated argument and state failures" {
            runTest {
                shouldThrow<IOException> {
                    db.safeTransaction(log, "Database write failed") {
                        throw IllegalArgumentException("invalid domain input")
                    }
                }
                shouldThrow<IOException> {
                    db.safeReadTransaction(log, "Database read failed") {
                        throw IllegalStateException("unrelated state failure")
                    }
                }
            }
        }

        "safeTransaction wraps other Exceptions as IOException" {
            runTest {
                val thrown =
                    shouldThrow<IOException> {
                        db.safeTransaction(log, "Database write failed") { throw RuntimeException("boom") }
                    }
                thrown.message shouldBe "Database write failed"
                thrown.cause.shouldBeInstanceOf<RuntimeException>()
                thrown.cause!!.message shouldBe "boom"
            }
        }

        "writeSyncMetadata upserts and readSyncMetadata returns it" {
            runTest {
                db.readSyncMetadata("missing-key") shouldBe null

                db.writeSyncMetadata("k", "v1", log, "upsert failed")
                db.readSyncMetadata("k") shouldBe "v1"

                db.writeSyncMetadata("k", "v2", log, "upsert failed")
                db.readSyncMetadata("k") shouldBe "v2"
            }
        }
    }
}
