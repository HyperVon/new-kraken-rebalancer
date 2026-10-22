package com.gemini.krakenbot.service.history

import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/** Pure ledger-checkpoint replay for account-wide end-of-UTC-day quantities. */
class HistoryDailyBalanceBuilder {
    fun build(
        accountIdentityDigest: String,
        startDay: LocalDate,
        endDay: LocalDate,
        checkpoints: List<HistoryBalanceCheckpoint>,
        coverage: List<HistorySyncCoverageWindow>,
        sourceRevision: Long,
        generationId: String,
        includeBucket: (assetClass: String?, assetKey: String) -> Boolean = ::isIncludedAccountBucket,
    ): List<HistoryDailyBalance> {
        require(startDay <= endDay)
        require(sourceRevision >= 0)
        val streams = checkpoints
            .filter { checkpoint ->
                checkpoint.rawAssetKey?.isNotBlank() == true &&
                    includeBucket(checkpoint.rawAssetClass, checkpoint.rawAssetKey)
            }
            .groupBy { checkpoint -> checkpoint.rawAssetClass to checkpoint.rawAssetKey.orEmpty() }
        val unknownAssetEvidence = checkpoints.any { checkpoint ->
            !isExcludedEquityBucket(checkpoint.rawAssetClass, checkpoint.rawAssetKey) &&
                (checkpoint.rawAssetKey.isNullOrBlank() || !isSupportedAccountBucket(checkpoint.rawAssetClass))
        }
        return buildList {
            var date = startDay
            while (date <= endDay) {
                val boundary = date.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant()
                val boundaryLastSecond = boundary.epochSecond - 1
                streams.forEach { (key, events) ->
                    val replay = replayThrough(events, boundary, coverage, boundaryLastSecond)
                    add(
                        HistoryDailyBalance(
                            dayEnd = boundary,
                            dayLabel = date,
                            accountIdentityDigest = accountIdentityDigest,
                            sourceAssetScope = HISTORY_ACCOUNT_DAILY_SCOPE,
                            rawAssetClass = key.first,
                            rawAssetKey = key.second,
                            quantity = replay.quantity,
                            sourceLedgerId = replay.ledgerId,
                            sourceObservedAt = replay.observedAt,
                            sourceRevision = sourceRevision,
                            generationId = generationId,
                            status = if (unknownAssetEvidence) {
                                HistoryDailyBalanceStatus.INVALID_CHECKPOINT
                            } else {
                                replay.status
                            },
                            reason = if (unknownAssetEvidence) {
                                unresolvedEvidenceReason(checkpoints)
                            } else {
                                replay.reason
                            },
                        ),
                    )
                }
                if (streams.isEmpty() || unknownAssetEvidence) {
                    val emptyAccountCovered = !unknownAssetEvidence && covered(coverage, 0L, boundaryLastSecond)
                    add(
                        HistoryDailyBalance(
                            dayEnd = boundary,
                            dayLabel = date,
                            accountIdentityDigest = accountIdentityDigest,
                            sourceAssetScope = HISTORY_ACCOUNT_DAILY_SCOPE,
                            rawAssetClass = if (unknownAssetEvidence) null else "currency",
                            rawAssetKey = if (unknownAssetEvidence) UNKNOWN_ASSET_KEY else "USD",
                            quantity = if (emptyAccountCovered) BigDecimal.ZERO else null,
                            sourceLedgerId = null,
                            sourceObservedAt = null,
                            sourceRevision = sourceRevision,
                            generationId = generationId,
                            status = if (unknownAssetEvidence) {
                                HistoryDailyBalanceStatus.INVALID_CHECKPOINT
                            } else if (emptyAccountCovered) {
                                HistoryDailyBalanceStatus.VALID
                            } else {
                                HistoryDailyBalanceStatus.NO_OPENING_ANCHOR
                            },
                            reason = if (unknownAssetEvidence) {
                                unresolvedEvidenceReason(checkpoints)
                            } else if (!emptyAccountCovered) {
                                NO_ANCHOR_REASON
                            } else {
                                null
                            },
                        ),
                    )
                }
                date = date.plusDays(1)
            }
        }
    }

    private fun replayThrough(
        checkpoints: List<HistoryBalanceCheckpoint>,
        boundary: Instant,
        coverage: List<HistorySyncCoverageWindow>,
        boundaryLastSecond: Long,
    ): ReplayState {
        val visible = checkpoints
            .filter { checkpoint -> checkpoint.observedAt?.let { it < boundary } ?: true }
            .groupBy { it.observedAt }
            .toSortedMap(compareBy<Instant?> { it ?: Instant.MIN })
        if (visible.isEmpty()) {
            return if (covered(coverage, 0L, boundaryLastSecond)) {
                ReplayState(BigDecimal.ZERO, null, null, HistoryDailyBalanceStatus.VALID, null)
            } else {
                ReplayState(null, null, null, HistoryDailyBalanceStatus.NO_OPENING_ANCHOR, NO_ANCHOR_REASON)
            }
        }

        var quantity: BigDecimal? = null
        var lastLedgerId: String? = null
        var lastObservedAt: Instant? = null
        var status = HistoryDailyBalanceStatus.NO_OPENING_ANCHOR
        var reason: String? = NO_ANCHOR_REASON
        for ((observedAt, group) in visible) {
            if (observedAt == null) {
                status = HistoryDailyBalanceStatus.INVALID_CHECKPOINT
                quantity = null
                reason = "A ledger checkpoint has no valid timestamp."
                continue
            }
            val ordered = group
            if (ordered.any { it.status != HistoryBalanceStatus.VALID || it.quantity == null }) {
                status = HistoryDailyBalanceStatus.INVALID_CHECKPOINT
                quantity = null
                lastLedgerId = ordered.lastOrNull()?.ledgerId
                lastObservedAt = observedAt
                reason = ordered.firstNotNullOfOrNull { it.reason } ?: "A ledger balance checkpoint is invalid."
                continue
            }

            val hasOpeningCoverage = covered(coverage, 0L, observedAt.epochSecond)
            val startQuantity = quantity ?: if (hasOpeningCoverage) BigDecimal.ZERO else null
            val resolved = resolveTimestampGroup(ordered, startQuantity)
            when {
                resolved != null -> {
                    quantity = resolved.last().quantity
                    lastLedgerId = resolved.last().ledgerId
                    lastObservedAt = observedAt
                    status = HistoryDailyBalanceStatus.VALID
                    reason = null
                }

                ordered.size == 1 -> {
                    // A valid Kraken post-balance is an absolute anchor even if the previous
                    // transition cannot be reconciled. The next interval starts at this point.
                    val checkpoint = ordered.single()
                    quantity = checkpoint.quantity
                    lastLedgerId = checkpoint.ledgerId
                    lastObservedAt = observedAt
                    status = HistoryDailyBalanceStatus.VALID
                    reason = null
                }

                else -> {
                    quantity = null
                    lastLedgerId = null
                    lastObservedAt = observedAt
                    status = HistoryDailyBalanceStatus.AMBIGUOUS_ORDER
                    reason = "Same-time ledger balances do not establish one unique order."
                }
            }
        }

        val coveredFrom = lastObservedAt?.epochSecond ?: 0L
        if (status == HistoryDailyBalanceStatus.VALID && !covered(coverage, coveredFrom, boundaryLastSecond)) {
            return ReplayState(
                null,
                lastLedgerId,
                lastObservedAt,
                HistoryDailyBalanceStatus.COVERAGE_GAP,
                "Verified ledger coverage has a gap before this UTC mark.",
            )
        }
        return ReplayState(quantity, lastLedgerId, lastObservedAt, status, reason)
    }

    private fun resolveTimestampGroup(
        checkpoints: List<HistoryBalanceCheckpoint>,
        opening: BigDecimal?,
    ): List<HistoryBalanceCheckpoint>? {
        if (checkpoints.size == 1 && opening == null) return checkpoints
        if (opening == null) return null
        val solutions = mutableListOf<List<HistoryBalanceCheckpoint>>()
        fun search(
            balance: BigDecimal,
            remaining: List<HistoryBalanceCheckpoint>,
            path: List<HistoryBalanceCheckpoint>,
        ) {
            if (solutions.size > 1) return
            if (remaining.isEmpty()) {
                solutions += path
                return
            }
            remaining.forEach { checkpoint ->
                val amount = checkpoint.rawAmount?.toBigDecimalOrNull() ?: return@forEach
                val fee = checkpoint.rawFee?.toBigDecimalOrNull() ?: return@forEach
                val postBalance = checkpoint.rawBalance?.toBigDecimalOrNull() ?: return@forEach
                if (amount.subtract(fee).add(balance).compareTo(postBalance) == 0) {
                    search(postBalance, remaining - checkpoint, path + checkpoint)
                }
            }
        }
        search(opening, checkpoints, emptyList())
        return solutions.singleOrNull()
    }

    private fun covered(windows: List<HistorySyncCoverageWindow>, fromSec: Long, throughSec: Long): Boolean {
        if (fromSec > throughSec) return true
        var cursor = fromSec
        for (window in windows.sortedBy { it.startSec }) {
            if (window.endSec < cursor) continue
            if (window.startSec > cursor) return false
            if (window.endSec >= throughSec) return true
            cursor = window.endSec + 1
        }
        return false
    }

    private data class ReplayState(
        val quantity: BigDecimal?,
        val ledgerId: String?,
        val observedAt: Instant?,
        val status: HistoryDailyBalanceStatus,
        val reason: String?,
    )

    companion object {
        const val UNKNOWN_ASSET_KEY = "[unidentified ledger asset]"
        private const val NO_ANCHOR_REASON = "No opening balance anchor or complete ledger coverage is available."
        private const val UNIDENTIFIED_REASON =
            "A ledger checkpoint has no asset identity, so the full account total is incomplete."
        private const val UNSUPPORTED_SCOPE_REASON =
            "Ledger history contains an unsupported wallet or asset class for account totals."

        /** Equity-class share units are outside the account-value and crypto rebalancer scope. */
        fun isIncludedAccountBucket(assetClass: String?, assetKey: String): Boolean =
            isSupportedAccountBucket(assetClass) && !isExcludedEquityBucket(assetClass, assetKey)

        private fun isSupportedAccountBucket(assetClass: String?): Boolean =
            assetClass.equals("currency", ignoreCase = true)

        private fun isExcludedEquityBucket(assetClass: String?, assetKey: String?): Boolean =
            assetClass.equals("equity", ignoreCase = true)

        private fun unresolvedEvidenceReason(checkpoints: List<HistoryBalanceCheckpoint>): String =
            if (checkpoints.any { it.rawAssetKey.isNullOrBlank() }) UNIDENTIFIED_REASON else UNSUPPORTED_SCOPE_REASON
    }
}
