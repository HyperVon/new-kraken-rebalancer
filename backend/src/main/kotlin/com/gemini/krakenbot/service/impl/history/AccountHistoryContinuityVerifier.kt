package com.gemini.krakenbot.service.impl.history

import com.gemini.krakenbot.model.KrakenApiConstants
import com.gemini.krakenbot.model.TradeRecord
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
    UNAVAILABLE,
}

/**
 * Proves that stored financial history belongs to the currently configured Kraken
 * account by intersecting durable exchange identifiers (trade/order/ledger ids)
 * with bounded exchange pages.
 *
 * The account scope digest is credential-derived, so a key rotation on the same
 * account (or an upgraded database that predates the binding contract) presents
 * as a scope mismatch/unbound database. Rebinding on digest equality alone would
 * let any credential set claim foreign history; intersection with exchange-side
 * ids proves the configured credentials can actually see the stored fills.
 *
 * Bounded to the newest page plus the deepest page of each history surface, so
 * mid-range rotation windows are covered without walking full account history.
 * Any exchange failure resolves to [AccountHistoryContinuityStatus.UNAVAILABLE]
 * so callers keep failing closed.
 */
class AccountHistoryContinuityVerifier(
    private val krakenService: KrakenService,
    private val tradeRepository: TradeRepository,
    private val ledgerRepository: LedgerRepository,
    private val nowProvider: () -> Instant = Instant::now,
) {
    private val log = LoggerFactory.getLogger(AccountHistoryContinuityVerifier::class.java)

    suspend fun verifyContinuity(): AccountHistoryContinuityStatus {
        try {
            val horizon = nowProvider()
            val storedTradeIds = tradeRepository
                .getTradesInRange(Instant.EPOCH, horizon)
                .asSequence()
                .sortedByDescending { it.timestamp }
                .take(MAX_STORED_MARKERS)
                .flatMap { sequenceOf(it.tradeId, it.orderTxid) }
                .mapNotNull { it?.trim()?.takeIf(String::isNotBlank) }
                .toSet()
            val storedLedgerIds = ledgerRepository
                .getLedgersInRange(Instant.EPOCH, horizon)
                .asSequence()
                .sortedByDescending { it.time }
                .take(MAX_STORED_MARKERS)
                .map { it.ledgerId.trim() }
                .filter { it.isNotBlank() }
                .toSet()
            if (storedTradeIds.isEmpty() && storedLedgerIds.isEmpty()) {
                return AccountHistoryContinuityStatus.NO_OVERLAP
            }

            val endSec = horizon.epochSecond
            if (storedTradeIds.isNotEmpty() && tradeIdsOverlap(storedTradeIds, endSec)) {
                return AccountHistoryContinuityStatus.VERIFIED
            }
            if (storedLedgerIds.isNotEmpty() && ledgerIdsOverlap(storedLedgerIds, endSec)) {
                return AccountHistoryContinuityStatus.VERIFIED
            }
            return AccountHistoryContinuityStatus.NO_OVERLAP
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("Unable to verify account history continuity", e)
            return AccountHistoryContinuityStatus.UNAVAILABLE
        }
    }

    private suspend fun tradeIdsOverlap(storedTradeIds: Set<String>, endSec: Long): Boolean {
        val exchangeIds = mutableSetOf<String>()
        val page = krakenService.getRecoveryTradeHistoryUntil(null, 0, endSec)
        collectTradeIds(page, exchangeIds)
        val deepOffset = krakenService.getLastTradeHistoryTotalCount() - KrakenApiConstants.TRADE_HISTORY_PAGE_SIZE
        if (deepOffset > 0 && krakenService.getLastTradeHistoryTotalCount() > page.size) {
            collectTradeIds(krakenService.getRecoveryTradeHistoryUntil(null, deepOffset, endSec), exchangeIds)
        }
        return storedTradeIds.any(exchangeIds::contains)
    }

    private suspend fun ledgerIdsOverlap(storedLedgerIds: Set<String>, endSec: Long): Boolean {
        val exchangeIds = mutableSetOf<String>()
        val page = krakenService.getLedgers(null, 0, endSec, null)
        page.mapTo(exchangeIds) { it.ledgerId.trim() }
        val deepOffset = krakenService.getLastLedgerTotalCount() - KrakenApiConstants.LEDGER_PAGE_SIZE
        if (deepOffset > 0 && krakenService.getLastLedgerTotalCount() > page.size) {
            krakenService.getLedgers(null, deepOffset, endSec, null).mapTo(exchangeIds) { it.ledgerId.trim() }
        }
        return storedLedgerIds.any(exchangeIds::contains)
    }

    private fun collectTradeIds(trades: List<TradeRecord>, sink: MutableSet<String>) {
        trades.forEach { trade ->
            trade.tradeId?.trim()?.takeIf(String::isNotBlank)?.let(sink::add)
            trade.orderTxid?.trim()?.takeIf(String::isNotBlank)?.let(sink::add)
        }
    }

    companion object {
        private const val MAX_STORED_MARKERS = 10_000
    }
}
