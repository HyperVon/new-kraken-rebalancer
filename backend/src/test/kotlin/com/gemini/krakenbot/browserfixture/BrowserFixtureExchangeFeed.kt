package com.gemini.krakenbot.browserfixture

import com.gemini.krakenbot.model.ExchangeHistoryPage
import com.gemini.krakenbot.model.ExchangeLedgerHistoryEntry
import com.gemini.krakenbot.model.ExchangeTradeHistoryEntry
import com.gemini.krakenbot.model.KrakenApiConstants
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.atomic.AtomicReference

internal class BrowserFixtureExchangeFeed {
    private val published = AtomicReference<PublishedEvents?>()

    fun publish(at: Instant) {
        val timestamp = at.truncatedTo(ChronoUnit.SECONDS)
        val events = PublishedEvents(
            trade = trade(timestamp),
            firstLedger = ledger(timestamp.minusSeconds(1), "first", "40.00", "3040.00"),
            secondLedger = ledger(timestamp, "second", "10.00", "3050.00"),
        )
        check(published.compareAndSet(null, events)) {
            "Post-start fixture exchange events can only be published once."
        }
    }

    fun publishIncompleteEvidence(at: Instant) {
        while (true) {
            val current = checkNotNull(published.get()) { "Publish the late account event first." }
            check(current.incompleteLedger == null) { "Incomplete fixture evidence can only be published once." }
            if (published.compareAndSet(current, current.copy(incompleteLedger = incompleteLedger(at)))) return
        }
    }

    fun tradePage(startSec: Long?, endSec: Long?, cursor: String?): ExchangeHistoryPage<ExchangeTradeHistoryEntry> {
        val trade = published.get()?.trade
        val entries = if (trade != null &&
            trade.time?.isWithin(startSec, endSec) == true
        ) {
            listOf(trade)
        } else {
            emptyList()
        }
        return page(entries, startSec, endSec, cursor)
    }

    fun ledgerPage(startSec: Long?, endSec: Long?, cursor: String?): ExchangeHistoryPage<ExchangeLedgerHistoryEntry> {
        val state = published.get()
        val (ledger, nextCursor) = when (cursor) {
            null -> state?.firstLedger to state?.let { SECOND_LEDGER_CURSOR }
            SECOND_LEDGER_CURSOR -> state?.secondLedger to state?.let { INCOMPLETE_LEDGER_CURSOR }
            INCOMPLETE_LEDGER_CURSOR -> state?.incompleteLedger to null
            else -> null to null
        }
        val entries = if (ledger != null &&
            ledger.time?.isWithin(startSec, endSec) == true
        ) {
            listOf(ledger)
        } else {
            emptyList()
        }
        return page(entries, startSec, endSec, cursor, nextCursor)
    }

    private fun trade(at: Instant) = ExchangeTradeHistoryEntry(
        tradeId = "fixture-post-start-trade-${at.epochSecond}",
        orderTxid = "fixture-post-start-order-${at.epochSecond}",
        postTxid = null,
        pair = "XXBTZUSD",
        baseAsset = "BTC",
        quoteCurrency = "USD",
        feeCurrency = null,
        time = at,
        rawTime = at.epochSecond.toString(),
        type = "buy",
        orderType = "market",
        price = "40000",
        cost = "40.00",
        fee = "0.08",
        volume = "0.001",
        margin = "0",
        misc = "",
        tradeSequenceId = "fixture-post-start-sequence-${at.epochSecond}",
        maker = false,
        ledgerIds = emptyList(),
        rawRecordJson = "{}",
        hasValidTime = true,
        hasValidPrice = true,
        hasValidCost = true,
        hasValidFee = true,
        hasValidVolume = true,
        isValidRecord = true,
    )

    private fun ledger(at: Instant, suffix: String, amount: String, balance: String) = ExchangeLedgerHistoryEntry(
        ledgerId = "fixture-post-start-ledger-${at.epochSecond}-$suffix",
        refid = "fixture-post-start-ledger-ref-${at.epochSecond}-$suffix",
        time = at,
        rawTime = at.epochSecond.toString(),
        type = KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
        subtype = "spot",
        assetClass = "currency",
        asset = "ZUSD",
        amount = amount,
        fee = "0.00",
        balance = balance,
        rawRecordJson = "{}",
        hasValidTime = true,
        hasValidAmount = true,
        hasValidFee = true,
        hasValidBalance = true,
        isValidRecord = true,
    )

    private fun incompleteLedger(at: Instant) = ExchangeLedgerHistoryEntry(
        ledgerId = "fixture-post-start-ledger-incomplete-${at.epochSecond}",
        refid = "fixture-post-start-ledger-incomplete-ref-${at.epochSecond}",
        time = at,
        rawTime = at.epochSecond.toString(),
        type = KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
        subtype = "spot",
        assetClass = "unsupported",
        asset = "MYSTERY",
        amount = "1.00",
        fee = "0.00",
        balance = "1.00",
        rawRecordJson = "{}",
        hasValidTime = true,
        hasValidAmount = true,
        hasValidFee = true,
        hasValidBalance = true,
        isValidRecord = true,
    )

    private fun Instant.isWithin(startSec: Long?, endSec: Long?): Boolean =
        (startSec == null || epochSecond >= startSec) && (endSec == null || epochSecond <= endSec)

    private fun <T> page(
        entries: List<T>,
        startSec: Long?,
        endSec: Long?,
        cursor: String?,
        nextCursor: String? = null,
    ) = ExchangeHistoryPage(
        entries = entries,
        startSec = startSec,
        endSec = endSec,
        requestedCursor = cursor,
        nextCursor = nextCursor,
        count = entries.size.toLong(),
        rawSize = entries.size,
        valid = true,
    )

    private data class PublishedEvents(
        val trade: ExchangeTradeHistoryEntry,
        val firstLedger: ExchangeLedgerHistoryEntry,
        val secondLedger: ExchangeLedgerHistoryEntry,
        val incompleteLedger: ExchangeLedgerHistoryEntry? = null,
    )

    private companion object {
        const val SECOND_LEDGER_CURSOR = "fixture-post-start-ledger-page-2"
        const val INCOMPLETE_LEDGER_CURSOR = "fixture-post-start-ledger-page-3"
    }
}
