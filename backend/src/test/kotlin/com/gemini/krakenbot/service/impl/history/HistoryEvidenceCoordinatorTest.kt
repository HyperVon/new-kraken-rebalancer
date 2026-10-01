package com.gemini.krakenbot.service.impl.history

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

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
    }
}
