package com.gemini.krakenbot.service

import com.gemini.krakenbot.model.ExchangeHistoryPage
import com.gemini.krakenbot.model.ExchangeLedgerHistoryEntry
import com.gemini.krakenbot.model.ExchangeTradeHistoryEntry
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest

class FakeKrakenServiceTest :
    StringSpec({
        isolationMode = IsolationMode.InstancePerTest

        "getAccountTradePage returns default empty page and tracks call count" {
            runTest {
                val fake = FakeKrakenService()

                val page = fake.getAccountTradePage(startSec = 100L, endSec = 200L, cursor = "cursor-1", limit = 50)

                page.valid shouldBe true
                page.entries shouldBe emptyList()
                page.startSec shouldBe 100L
                page.endSec shouldBe 200L
                page.requestedCursor shouldBe "cursor-1"
                fake.getTradePageCallCount shouldBe 1
            }
        }

        "getAccountLedgerPage returns default empty page and tracks call count" {
            runTest {
                val fake = FakeKrakenService()

                val page = fake.getAccountLedgerPage(startSec = 300L, endSec = 400L, cursor = "ledger-cursor")

                page.valid shouldBe true
                page.entries shouldBe emptyList()
                page.startSec shouldBe 300L
                page.endSec shouldBe 400L
                page.requestedCursor shouldBe "ledger-cursor"
                fake.getLedgerPageCallCount shouldBe 1
            }
        }

        "tradePageSupplier and ledgerPageSupplier return custom configured pages" {
            runTest {
                val fake = FakeKrakenService()
                val tradeEntry = ExchangeTradeHistoryEntry(
                    tradeId = "T1",
                    orderTxid = "O1",
                    postTxid = null,
                    pair = "XBTUSD",
                    baseAsset = "XBT",
                    quoteCurrency = "USD",
                    feeCurrency = "USD",
                    time = null,
                    rawTime = null,
                    type = "buy",
                    orderType = "limit",
                    price = "50000.0",
                    cost = "5000.0",
                    fee = "8.0",
                    volume = "0.1",
                    margin = null,
                    misc = null,
                    tradeSequenceId = null,
                    maker = false,
                    ledgerIds = null,
                    rawRecordJson = null,
                    hasValidTime = true,
                    hasValidPrice = true,
                    hasValidCost = true,
                    hasValidFee = true,
                    hasValidVolume = true,
                    isValidRecord = true,
                )
                fake.tradePageSupplier = { start, end, cursor, _ ->
                    ExchangeHistoryPage(
                        entries = listOf(tradeEntry),
                        startSec = start,
                        endSec = end,
                        requestedCursor = cursor,
                        nextCursor = "next-cursor",
                        count = 1L,
                        rawSize = 1,
                        valid = true,
                    )
                }

                val tradePage = fake.getAccountTradePage(null, null, null, null)
                tradePage.entries shouldBe listOf(tradeEntry)
                tradePage.nextCursor shouldBe "next-cursor"
                tradePage.count shouldBe 1L

                val ledgerEntry = ExchangeLedgerHistoryEntry(
                    ledgerId = "L1",
                    refid = "ref-1",
                    time = null,
                    rawTime = null,
                    type = "deposit",
                    subtype = null,
                    assetClass = "currency",
                    asset = "USD",
                    amount = "1000.0",
                    fee = "0.0",
                    balance = "1000.0",
                    rawRecordJson = null,
                    hasValidTime = true,
                    hasValidAmount = true,
                    hasValidFee = true,
                    hasValidBalance = true,
                    isValidRecord = true,
                )
                fake.ledgerPageSupplier = { start, end, cursor ->
                    ExchangeHistoryPage(
                        entries = listOf(ledgerEntry),
                        startSec = start,
                        endSec = end,
                        requestedCursor = cursor,
                        nextCursor = null,
                        count = 1L,
                        rawSize = 1,
                        valid = true,
                    )
                }

                val ledgerPage = fake.getAccountLedgerPage(null, null, null)
                ledgerPage.entries shouldBe listOf(ledgerEntry)
                ledgerPage.rawSize shouldBe 1
            }
        }
    })
