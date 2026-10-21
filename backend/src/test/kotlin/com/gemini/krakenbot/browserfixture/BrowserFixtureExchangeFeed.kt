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
        check(published.compareAndSet(null, PublishedEvents(trade(timestamp), ledger(timestamp)))) {
            "Post-start fixture exchange events can only be published once."
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
        val ledger = published.get()?.ledger
        val entries = if (ledger != null &&
            ledger.time?.isWithin(startSec, endSec) == true
        ) {
            listOf(ledger)
        } else {
            emptyList()
        }
        return page(entries, startSec, endSec, cursor)
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

    private fun ledger(at: Instant) = ExchangeLedgerHistoryEntry(
        ledgerId = "fixture-post-start-ledger-${at.epochSecond}",
        refid = "fixture-post-start-ledger-ref-${at.epochSecond}",
        time = at,
        rawTime = at.epochSecond.toString(),
        type = KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
        subtype = "spot",
        assetClass = "currency",
        asset = "ZUSD",
        amount = "40.00",
        fee = "0.00",
        balance = "3040.00",
        rawRecordJson = "{}",
        hasValidTime = true,
        hasValidAmount = true,
        hasValidFee = true,
        hasValidBalance = true,
        isValidRecord = true,
    )

    private fun Instant.isWithin(startSec: Long?, endSec: Long?): Boolean =
        (startSec == null || epochSecond >= startSec) && (endSec == null || epochSecond <= endSec)

    private fun <T> page(entries: List<T>, startSec: Long?, endSec: Long?, cursor: String?) = ExchangeHistoryPage(
        entries = entries,
        startSec = startSec,
        endSec = endSec,
        requestedCursor = cursor,
        nextCursor = null,
        count = entries.size.toLong(),
        rawSize = entries.size,
        valid = true,
    )

    private data class PublishedEvents(val trade: ExchangeTradeHistoryEntry, val ledger: ExchangeLedgerHistoryEntry)
}
