package com.gemini.krakenbot.api

import com.gemini.krakenbot.service.InceptionRecoveryStatus

fun buildSyncProgressResponse(
    seeded: Boolean,
    offset: String?,
    total: String?,
    recovery: InceptionRecoveryStatus = InceptionRecoveryStatus(),
): SyncProgressResponse = SyncProgressResponse(
    seeded = seeded,
    offset = offset.orEmpty(),
    total = total.orEmpty(),
    recoveryStatus = recovery.status,
    recoveryTradeOffset = recovery.tradeOffset,
    recoveryTradeTotal = recovery.tradeTotal,
    recoveryLedgerOffset = recovery.ledgerOffset,
    recoveryLedgerTotal = recovery.ledgerTotal,
    recoveryCandidate = recovery.candidateTime,
    recoveryReason = recovery.reason,
    recoveryHorizon = recovery.coverageHorizon,
)
