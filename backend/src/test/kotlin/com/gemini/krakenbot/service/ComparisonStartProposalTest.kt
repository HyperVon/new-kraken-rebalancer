package com.gemini.krakenbot.service

import com.gemini.krakenbot.model.ComparisonProposalStatus
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import java.time.Instant

class ComparisonStartProposalTest : StringSpec() {
    override fun isolationMode() = IsolationMode.InstancePerTest

    init {
        "only verified proposals may carry a timestamp" {
            val timestamp = Instant.parse("2026-07-01T12:00:00Z")

            ComparisonStartProposal(ComparisonProposalStatus.VERIFIED, timestamp).timestamp shouldBe timestamp
            ComparisonStartProposal(ComparisonProposalStatus.VERIFIED, timestamp, snapshotId = 7).snapshotId shouldBe 7
            ComparisonStartProposal(ComparisonProposalStatus.INCOMPLETE).timestamp.shouldBeNull()
            ComparisonStartProposal(ComparisonProposalStatus.EXHAUSTED).timestamp.shouldBeNull()

            shouldThrow<IllegalArgumentException> {
                ComparisonStartProposal(ComparisonProposalStatus.VERIFIED)
            }
            shouldThrow<IllegalArgumentException> {
                ComparisonStartProposal(ComparisonProposalStatus.INCOMPLETE, timestamp)
            }
            shouldThrow<IllegalArgumentException> {
                ComparisonStartProposal(ComparisonProposalStatus.INCOMPLETE, snapshotId = 7)
            }
        }
    }
}
