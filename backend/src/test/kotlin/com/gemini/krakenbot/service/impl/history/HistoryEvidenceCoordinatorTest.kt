@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.gemini.krakenbot.service.impl.history

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.slf4j.LoggerFactory

class HistoryEvidenceCoordinatorTest : StringSpec() {
    override fun isolationMode() = IsolationMode.InstancePerTest

    init {
        "tryWithLock runs the block and releases an available lock" {
            val coordinator = HistoryEvidenceCoordinator()
            var blockCalls = 0

            coordinator.tryWithLock { blockCalls++ } shouldBe true
            blockCalls shouldBe 1

            coordinator.tryWithLock { blockCalls++ } shouldBe true
            blockCalls shouldBe 2
        }

        "tryWithLock declines a busy lock without running the block" {
            val coordinator = HistoryEvidenceCoordinator()
            var nestedBlockCalls = 0

            coordinator.withLock {
                coordinator.tryWithLock { nestedBlockCalls++ } shouldBe false
                nestedBlockCalls shouldBe 0
            }

            coordinator.tryWithLock { nestedBlockCalls++ } shouldBe true
            nestedBlockCalls shouldBe 1
        }

        "tryWithLock releases the lock when its block throws" {
            val coordinator = HistoryEvidenceCoordinator()

            shouldThrow<IllegalStateException> {
                coordinator.tryWithLock { throw IllegalStateException("save failed") }
            }

            coordinator.tryWithLock {} shouldBe true
        }

        "tryWithLock rejects a cancelled caller without acquiring the lock" {
            runTest {
                val coordinator = HistoryEvidenceCoordinator()
                val rejected = CompletableDeferred<Boolean>()
                val caller = launch(start = CoroutineStart.UNDISPATCHED) {
                    currentCoroutineContext()[Job]?.cancel()
                    val exception = runCatching { coordinator.tryWithLock {} }.exceptionOrNull()
                    rejected.complete(exception is CancellationException)
                }

                rejected.await() shouldBe true
                caller.join()
                coordinator.tryWithLock {} shouldBe true
            }
        }

        "withLock logs the owner when another history operation waits beyond the diagnostic threshold" {
            runTest {
                val coordinator = HistoryEvidenceCoordinator()
                val logger = LoggerFactory.getLogger(HistoryEvidenceCoordinator::class.java) as Logger
                val events = ListAppender<ILoggingEvent>().apply {
                    context = logger.loggerContext
                    start()
                }
                logger.addAppender(events)
                val owner = launch {
                    coordinator.withLock(operation = "ledger-sync") {
                        awaitCancellation()
                    }
                }
                val waiter = launch { coordinator.withLock(operation = "settings-fragment") {} }

                try {
                    runCurrent()
                    advanceTimeBy(5_001)
                    runCurrent()

                    waiter.isCompleted shouldBe false
                    val warning = events.list.single { it.level == Level.WARN }
                    warning.formattedMessage shouldContain "operation=settings-fragment"
                    warning.formattedMessage shouldContain "currentOwner=ledger-sync"

                    owner.cancelAndJoin()
                    waiter.join()
                } finally {
                    owner.cancelAndJoin()
                    waiter.cancelAndJoin()
                    logger.detachAppender(events)
                    events.stop()
                }
            }
        }
    }
}
