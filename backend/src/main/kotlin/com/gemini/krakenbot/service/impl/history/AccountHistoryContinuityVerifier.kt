package com.gemini.krakenbot.service.impl.history

import com.gemini.krakenbot.model.KrakenApiConstants
import com.gemini.krakenbot.model.TradeSource
import com.gemini.krakenbot.repository.LedgerRepository
import com.gemini.krakenbot.repository.TradeRepository
import com.gemini.krakenbot.service.KrakenService
import com.gemini.krakenbot.service.getRecoveryTradeHistoryUntil
import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory
import java.time.Instant

enum class AccountHistoryContinuityStatus {
    VERIFIED,
    NO_OVERLAP,
    CONFLICT,
    INCOMPLETE,
    UNAVAILABLE,
}

/**
 * Proves that stored financial history belongs to the currently configured Kraken
 * account by locating retained local continuity markers inside bounded,
 * marker-timestamped Kraken history windows and requiring exact typed
 * exchange-identity matches.
 *
 * Two trust problems share this proof with different thresholds:
 *
 * - Rotation ([verifyContinuity]): the database already carries a trusted
 *   binding and only the credential generation changed. One exact authoritative
 *   identity visible through the new credentials proves they see history
 *   belonging to the previously trusted account. Lightweight by design.
 *
 * - Legacy first binding ([verifyLegacyConsistency]): the database predates
 *   the binding contract, so nothing guarantees all rows came from one
 *   account — it may have served account A in January and account B in
 *   February. One hit only proves the current account contributed a row, so
 *   binding requires consistency across a time-spread marker sample: every
 *   sampled marker must match. A definitively absent marker alongside a match
 *   is [AccountHistoryContinuityStatus.CONFLICT] (mixed history, never bind);
 *   absence with no match at all is [AccountHistoryContinuityStatus.NO_OVERLAP].
 *   Fewer than two distinct authoritative markers in the whole database is
 *   insufficient evidence either way and reports
 *   [AccountHistoryContinuityStatus.NO_OVERLAP] (unproven, fail closed).
 *
 * Proof is driven from local markers, never from global pagination rank: each
 * marker queries Kraken around its own timestamp, so a retained fill sitting at
 * global offset 437 of 1,000 is found exactly as reliably as one on the newest
 * page. The timestamp window is only a search locator; similarity of time,
 * amount, pair, or price without an exact identity is never evidence.
 *
 * Marker policy (authoritative exchange-backed rows only):
 * - trades: settled (`success && !dryRun`), never [TradeSource.LOCAL_ESTIMATE]
 *   (client-side estimates carry invented ids), with a non-blank `tradeId`.
 *   Legacy rows with `LEGACY_UNKNOWN`/null source remain eligible: their
 *   provenance is unknown but the proof itself is the exact id inside the live
 *   exchange response, which a foreign or invented id cannot satisfy.
 * - ledgers: non-blank `ledgerId`. The ledger table is written only from the
 *   Kraken boundary (sync/recovery paths); no synthetic rows are persisted.
 *
 * Matching is strictly typed: local `tradeId` matches only remote `tradeId`,
 * local `ledgerId` only remote `ledgerId`. `orderTxid` deliberately never
 * proves continuity on its own: it identifies the parent order and can be
 * shared across fills/legs, so it is weaker than a fill or ledger identity.
 *
 * Evidence threshold: one exact `tradeId` or one exact `ledgerId` match
 * verifies a rotation because Kraken generates these fill/ledger identities
 * per account, so a cross-account collision is effectively impossible;
 * timestamp/amount similarity is explicitly not counted. Duplicate local rows
 * or duplicate Kraken rows cannot double-count because markers are
 * de-duplicated by identity first.
 *
 * Boundedness: rotation searches at most 2 trade markers (newest + oldest) and
 * 2 ledger markers; legacy samples at most [LEGACY_MAX_TRADE_MARKERS] trade
 * and [LEGACY_MAX_LEDGER_MARKERS] ledger markers at quantile spread. Each
 * marker is searched in at most [MAX_PAGES_PER_WINDOW] pages. A window that is
 * still full at the page cap resolves to
 * [AccountHistoryContinuityStatus.INCOMPLETE] (search unproven), never
 * [AccountHistoryContinuityStatus.NO_OVERLAP], so callers can fail closed
 * without conflating "checked and absent" with "stopped early". Exchange
 * errors resolve to [AccountHistoryContinuityStatus.UNAVAILABLE].
 *
 * Callers must pin one credential generation across the whole proof (the
 * guard runs verification inside a config execution session plus a pinned
 * backend): window queries are only comparable when they all observe the
 * same generation.
 */
class AccountHistoryContinuityVerifier(
    private val krakenService: KrakenService,
    private val tradeRepository: TradeRepository,
    private val ledgerRepository: LedgerRepository,
    private val nowProvider: () -> Instant = Instant::now,
) {
    private val log = LoggerFactory.getLogger(AccountHistoryContinuityVerifier::class.java)

    private sealed interface ContinuityMarker {
        val timestamp: Instant
    }

    private data class TradeMarker(val tradeId: String, override val timestamp: Instant) : ContinuityMarker

    private data class LedgerMarker(val ledgerId: String, override val timestamp: Instant) : ContinuityMarker

    private enum class WindowOutcome {
        MATCH,
        ABSENT,
        INCOMPLETE,
    }

    /**
     * Rotation proof for an already-bound database: the first exact identity
     * hit verifies (see class contract for why one hit suffices here).
     */
    suspend fun verifyContinuity(): AccountHistoryContinuityStatus {
        try {
            val markers = loadMarkers()
            if (markers.isEmpty) return AccountHistoryContinuityStatus.NO_OVERLAP
            var incomplete = false
            for (marker in markers.trades) {
                when (searchTradeWindow(marker)) {
                    WindowOutcome.MATCH -> return AccountHistoryContinuityStatus.VERIFIED
                    WindowOutcome.INCOMPLETE -> incomplete = true
                    WindowOutcome.ABSENT -> Unit
                }
            }
            for (marker in markers.ledgers) {
                when (searchLedgerWindow(marker)) {
                    WindowOutcome.MATCH -> return AccountHistoryContinuityStatus.VERIFIED
                    WindowOutcome.INCOMPLETE -> incomplete = true
                    WindowOutcome.ABSENT -> Unit
                }
            }
            return if (incomplete) {
                AccountHistoryContinuityStatus.INCOMPLETE
            } else {
                AccountHistoryContinuityStatus.NO_OVERLAP
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("Unable to verify account history continuity", e)
            return AccountHistoryContinuityStatus.UNAVAILABLE
        }
    }

    /**
     * Legacy first-binding proof for an unbound database: every sampled marker
     * must match. Any definitive absence alongside a match is [CONFLICT]
     * (mixed-account history); absence with no match is [NO_OVERLAP].
     * Exchange errors dominate as [UNAVAILABLE]; unfinished searches as
     * [INCOMPLETE]. Fewer than two distinct markers database-wide is
     * insufficient evidence and reports [NO_OVERLAP] (unproven, fail closed).
     */
    suspend fun verifyLegacyConsistency(): AccountHistoryContinuityStatus {
        try {
            val markers = loadMarkers(legacySample = true)
            if (markers.distinctCount < 2) return AccountHistoryContinuityStatus.NO_OVERLAP
            var matched = false
            var absent = false
            var incomplete = false
            for (marker in markers.trades + markers.ledgers) {
                val outcome = when (marker) {
                    is TradeMarker -> searchTradeWindow(marker)
                    is LedgerMarker -> searchLedgerWindow(marker)
                }
                when (outcome) {
                    WindowOutcome.MATCH -> matched = true
                    WindowOutcome.ABSENT -> absent = true
                    WindowOutcome.INCOMPLETE -> incomplete = true
                }
            }
            return when {
                absent && matched -> AccountHistoryContinuityStatus.CONFLICT
                absent -> AccountHistoryContinuityStatus.NO_OVERLAP
                incomplete -> AccountHistoryContinuityStatus.INCOMPLETE
                matched -> AccountHistoryContinuityStatus.VERIFIED
                else -> AccountHistoryContinuityStatus.NO_OVERLAP
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("Unable to verify legacy account history consistency", e)
            return AccountHistoryContinuityStatus.UNAVAILABLE
        }
    }

    private data class MarkerSet(val trades: List<TradeMarker>, val ledgers: List<LedgerMarker>) {
        val isEmpty: Boolean get() = trades.isEmpty() && ledgers.isEmpty()
        val distinctCount: Int get() = trades.size + ledgers.size
    }

    private suspend fun loadMarkers(legacySample: Boolean = false): MarkerSet {
        val horizon = nowProvider()
        val tradeIds = tradeRepository
            .getTradesInRange(Instant.EPOCH, horizon)
            .filter { it.success && !it.dryRun && it.source != TradeSource.LOCAL_ESTIMATE }
            .mapNotNull { trade ->
                val id = trade.tradeId?.trim()?.takeIf(String::isNotBlank) ?: return@mapNotNull null
                TradeMarker(id, trade.timestamp)
            }
            .distinctBy { it.tradeId }
            .sortedByDescending { it.timestamp }
        val ledgerIds = ledgerRepository
            .getLedgersInRange(Instant.EPOCH, horizon)
            .mapNotNull { event ->
                val id = event.ledgerId.trim().takeIf(String::isNotBlank) ?: return@mapNotNull null
                LedgerMarker(id, event.time)
            }
            .distinctBy { it.ledgerId }
            .sortedByDescending { it.timestamp }
        return if (legacySample) {
            MarkerSet(tradeIds.quantiles(LEGACY_MAX_TRADE_MARKERS), ledgerIds.quantiles(LEGACY_MAX_LEDGER_MARKERS))
        } else {
            MarkerSet(tradeIds.spread(MAX_TRADE_MARKERS), ledgerIds.spread(MAX_LEDGER_MARKERS))
        }
    }

    /**
     * Newest plus oldest markers for maximum time spread with a bounded marker
     * budget; a single marker (or none) passes through unchanged.
     */
    private fun <T> List<T>.spread(limit: Int): List<T> {
        if (size <= limit) return this
        return listOf(first(), last())
    }

    /**
     * Deterministic quantile sample (0%, 25%, 50%, 75%, 100% up to [limit]
     * markers) so legacy proof spans the retained lifetime instead of proving
     * one endpoint.
     */
    private fun <T> List<T>.quantiles(limit: Int): List<T> {
        if (size <= limit) return this
        return (0 until limit).map { this[it * (size - 1) / (limit - 1)] }.distinct()
    }

    /**
     * Searches one marker window newest-first. The window is located by the
     * marker timestamp; only an exact typed identity match proves continuity.
     */
    private suspend fun searchTradeWindow(marker: TradeMarker): WindowOutcome {
        val startSec = marker.timestamp.epochSecond - WINDOW_TOLERANCE_SEC
        val endSec = marker.timestamp.epochSecond + WINDOW_TOLERANCE_SEC
        var offset = 0
        repeat(MAX_PAGES_PER_WINDOW) {
            val page = krakenService.getRecoveryTradeHistoryUntil(startSec, offset, endSec)
            if (page.any { it.tradeId?.trim() == marker.tradeId }) {
                return WindowOutcome.MATCH
            }
            if (windowCovered(
                    offset,
                    page.size,
                    krakenService.getLastTradeHistoryTotalCount(),
                    KrakenApiConstants.TRADE_HISTORY_PAGE_SIZE,
                )
            ) {
                return WindowOutcome.ABSENT
            }
            offset += KrakenApiConstants.TRADE_HISTORY_PAGE_SIZE
        }
        return WindowOutcome.INCOMPLETE
    }

    private suspend fun searchLedgerWindow(marker: LedgerMarker): WindowOutcome {
        val startSec = marker.timestamp.epochSecond - WINDOW_TOLERANCE_SEC
        val endSec = marker.timestamp.epochSecond + WINDOW_TOLERANCE_SEC
        var offset = 0
        repeat(MAX_PAGES_PER_WINDOW) {
            val page = krakenService.getLedgers(startSec, offset, endSec, null)
            if (page.any { it.ledgerId.trim() == marker.ledgerId }) {
                return WindowOutcome.MATCH
            }
            // Parsed rows may be fewer than the raw Kraken page when the
            // parser drops rows: occupancy must reflect the raw page, or a
            // full raw page could be mistaken for a short (complete) one and a
            // dense-window marker falsely reported absent.
            val occupancy = maxOf(krakenService.getLastLedgerRawPageSize(), page.size)
            if (windowCovered(
                    offset,
                    occupancy,
                    krakenService.getLastLedgerTotalCount(),
                    KrakenApiConstants.LEDGER_PAGE_SIZE,
                )
            ) {
                return WindowOutcome.ABSENT
            }
            offset += KrakenApiConstants.LEDGER_PAGE_SIZE
        }
        return WindowOutcome.INCOMPLETE
    }

    /**
     * A window is exhaustively covered by a short raw page (nothing further
     * follows in the window regardless of any reported total) or — when the
     * backend reports a usable authoritative total — once the fetched range
     * reaches it. Anything else means the search stopped early and must not
     * claim absence.
     */
    private fun windowCovered(offset: Int, pageSize: Int, authoritativeTotal: Int, fullPageSize: Int): Boolean {
        if (pageSize < fullPageSize) return true
        return authoritativeTotal > 0 && offset + pageSize >= authoritativeTotal
    }

    companion object {
        /**
         * Half-width of the marker search window. Stored fill timestamps
         * originate from the exchange itself, so no clock skew applies; the
         * tolerance only absorbs second-precision inclusive-bound filtering at
         * the Kraken boundary. It narrows the search and is never evidence.
         */
        private const val WINDOW_TOLERANCE_SEC = 300L
        private const val MAX_TRADE_MARKERS = 2
        private const val MAX_LEDGER_MARKERS = 2
        private const val LEGACY_MAX_TRADE_MARKERS = 5
        private const val LEGACY_MAX_LEDGER_MARKERS = 5

        /**
         * Page cap per marker window: 4 x 50-row pages (200 rows) covers dense
         * windows such as a 101-row marker neighborhood with margin, while the
         * overall proof stays bounded.
         */
        private const val MAX_PAGES_PER_WINDOW = 4
    }
}
