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
    INCOMPLETE,
    UNAVAILABLE,
}

/**
 * Proves that stored financial history belongs to the currently configured Kraken
 * account by locating retained local continuity markers inside bounded,
 * marker-timestamped Kraken history windows and requiring an exact typed
 * exchange-identity match.
 *
 * The account scope digest is credential-derived, so a key rotation on the same
 * account (or an upgraded database that predates the binding contract) presents
 * as a scope mismatch/unbound database. Rebinding on digest equality alone would
 * let any credential set claim foreign history; finding a retained exchange id
 * inside the live account's own history proves the configured credentials can
 * actually see the stored fills.
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
 * verifies. Kraken generates these fill/ledger identities per account, so a
 * cross-account collision is effectively impossible; timestamp/amount
 * similarity is explicitly not counted. Duplicate local rows or duplicate
 * Kraken rows cannot double-count because a single match decides immediately
 * and markers are de-duplicated by identity first.
 *
 * Boundedness: at most [MAX_TRADE_MARKERS] trade markers (newest + oldest for
 * maximum window spread) and [MAX_LEDGER_MARKERS] ledger markers, each searched
 * in at most [MAX_PAGES_PER_WINDOW] pages. A window that is still full at the
 * page cap resolves to [AccountHistoryContinuityStatus.INCOMPLETE] (search
 * unproven), never [AccountHistoryContinuityStatus.NO_OVERLAP], so callers can
 * fail closed without conflating "checked and absent" with "stopped early".
 * Exchange errors resolve to [AccountHistoryContinuityStatus.UNAVAILABLE].
 */
class AccountHistoryContinuityVerifier(
    private val krakenService: KrakenService,
    private val tradeRepository: TradeRepository,
    private val ledgerRepository: LedgerRepository,
    private val nowProvider: () -> Instant = Instant::now,
) {
    private val log = LoggerFactory.getLogger(AccountHistoryContinuityVerifier::class.java)

    private data class TradeMarker(val tradeId: String, val timestamp: Instant)

    private data class LedgerMarker(val ledgerId: String, val timestamp: Instant)

    suspend fun verifyContinuity(): AccountHistoryContinuityStatus {
        try {
            val horizon = nowProvider()
            val tradeMarkers = tradeRepository
                .getTradesInRange(Instant.EPOCH, horizon)
                .filter { it.success && !it.dryRun && it.source != TradeSource.LOCAL_ESTIMATE }
                .mapNotNull { trade ->
                    val id = trade.tradeId?.trim()?.takeIf(String::isNotBlank) ?: return@mapNotNull null
                    TradeMarker(id, trade.timestamp)
                }
                .distinctBy { it.tradeId }
                .sortedByDescending { it.timestamp }
                .spread(MAX_TRADE_MARKERS)
            val ledgerMarkers = ledgerRepository
                .getLedgersInRange(Instant.EPOCH, horizon)
                .mapNotNull { event ->
                    val id = event.ledgerId.trim().takeIf(String::isNotBlank) ?: return@mapNotNull null
                    LedgerMarker(id, event.time)
                }
                .distinctBy { it.ledgerId }
                .sortedByDescending { it.timestamp }
                .spread(MAX_LEDGER_MARKERS)
            if (tradeMarkers.isEmpty() && ledgerMarkers.isEmpty()) {
                return AccountHistoryContinuityStatus.NO_OVERLAP
            }

            var incomplete = false
            for (marker in tradeMarkers) {
                when (searchTradeWindow(marker)) {
                    AccountHistoryContinuityStatus.VERIFIED -> return AccountHistoryContinuityStatus.VERIFIED
                    AccountHistoryContinuityStatus.INCOMPLETE -> incomplete = true
                    else -> Unit
                }
            }
            for (marker in ledgerMarkers) {
                when (searchLedgerWindow(marker)) {
                    AccountHistoryContinuityStatus.VERIFIED -> return AccountHistoryContinuityStatus.VERIFIED
                    AccountHistoryContinuityStatus.INCOMPLETE -> incomplete = true
                    else -> Unit
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
     * Newest plus oldest markers for maximum time spread with a bounded marker
     * budget; a single marker (or none) passes through unchanged.
     */
    private fun <T> List<T>.spread(limit: Int): List<T> {
        if (size <= limit) return this
        return listOf(first(), last())
    }

    /**
     * Searches one marker window newest-first. The window is located by the
     * marker timestamp; only an exact typed identity match proves continuity.
     */
    private suspend fun searchTradeWindow(marker: TradeMarker): AccountHistoryContinuityStatus {
        val startSec = marker.timestamp.epochSecond - WINDOW_TOLERANCE_SEC
        val endSec = marker.timestamp.epochSecond + WINDOW_TOLERANCE_SEC
        var offset = 0
        repeat(MAX_PAGES_PER_WINDOW) {
            val page = krakenService.getRecoveryTradeHistoryUntil(startSec, offset, endSec)
            if (page.any { it.tradeId?.trim() == marker.tradeId }) {
                return AccountHistoryContinuityStatus.VERIFIED
            }
            if (windowCovered(
                    offset,
                    page.size,
                    krakenService.getLastTradeHistoryTotalCount(),
                    KrakenApiConstants.TRADE_HISTORY_PAGE_SIZE,
                )
            ) {
                return AccountHistoryContinuityStatus.NO_OVERLAP
            }
            offset += KrakenApiConstants.TRADE_HISTORY_PAGE_SIZE
        }
        return AccountHistoryContinuityStatus.INCOMPLETE
    }

    private suspend fun searchLedgerWindow(marker: LedgerMarker): AccountHistoryContinuityStatus {
        val startSec = marker.timestamp.epochSecond - WINDOW_TOLERANCE_SEC
        val endSec = marker.timestamp.epochSecond + WINDOW_TOLERANCE_SEC
        var offset = 0
        repeat(MAX_PAGES_PER_WINDOW) {
            val page = krakenService.getLedgers(startSec, offset, endSec, null)
            if (page.any { it.ledgerId.trim() == marker.ledgerId }) {
                return AccountHistoryContinuityStatus.VERIFIED
            }
            if (windowCovered(
                    offset,
                    page.size,
                    krakenService.getLastLedgerTotalCount(),
                    KrakenApiConstants.LEDGER_PAGE_SIZE,
                )
            ) {
                return AccountHistoryContinuityStatus.NO_OVERLAP
            }
            offset += KrakenApiConstants.LEDGER_PAGE_SIZE
        }
        return AccountHistoryContinuityStatus.INCOMPLETE
    }

    /**
     * A window is exhaustively covered by a short raw page (nothing further
     * follows in the window regardless of any reported total) or — when the
     * backend reports a usable authoritative total — once the fetched range
     * reaches it. Anything else means the search stopped early and must not
     * claim [AccountHistoryContinuityStatus.NO_OVERLAP].
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

        /**
         * Page cap per marker window: 4 x 50-row pages (200 rows) covers dense
         * windows such as a 101-row marker neighborhood with margin, while the
         * overall proof stays bounded to 16 exchange calls worst case.
         */
        private const val MAX_PAGES_PER_WINDOW = 4
    }
}
