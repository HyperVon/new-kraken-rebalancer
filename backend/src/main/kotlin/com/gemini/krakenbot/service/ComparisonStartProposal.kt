package com.gemini.krakenbot.service

import com.gemini.krakenbot.model.ComparisonProposalStatus
import java.time.Instant

/** Result of the bounded, resumable search for a later comparison start. */
data class ComparisonStartProposal(
    val status: ComparisonProposalStatus,
    val timestamp: Instant? = null,
    /** Durable snapshot identity used to preserve the exact candidate on acceptance. */
    val snapshotId: Int? = null,
) {
    init {
        require((status == ComparisonProposalStatus.VERIFIED) == (timestamp != null)) {
            "Only a verified proposal may have a timestamp"
        }
        require(status == ComparisonProposalStatus.VERIFIED || snapshotId == null) {
            "Only a verified proposal may have a snapshot identity"
        }
    }
}
