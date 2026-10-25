package com.gemini.krakenbot.service

import com.gemini.krakenbot.model.ExchangeTradeFill
import com.gemini.krakenbot.model.ExchangeTradePage
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest

@Suppress("unused")
class FakeKrakenServiceTest :
    StringSpec({
        isolationMode = IsolationMode.InstancePerTest

        "getRecentTradePage returns an empty valid page and tracks call count" {
            runTest {
                val fake = FakeKrakenService()

                val page = fake.getRecentTradePage(startSec = 100L, endSec = 200L, cursor = "cursor-1", limit = 50)

                page.valid shouldBe true
                page.entries shouldBe emptyList()
                page.nextCursor shouldBe null
                fake.getTradePageCallCount shouldBe 1
            }
        }

        "tradePageSupplier returns its configured cursor page" {
            runTest {
                val fake = FakeKrakenService()
                val fill = ExchangeTradeFill(
                    tradeId = "T1",
                    orderTxid = "O1",
                    baseAsset = "BTC",
                    quoteCurrency = "USD",
                    feeCurrency = "USD",
                    type = "sell",
                    price = "50000.0",
                    cost = "5000.0",
                    fee = "8.0",
                    volume = "0.1",
                    hasValidTime = true,
                    hasValidPrice = true,
                    hasValidCost = true,
                    hasValidFee = true,
                    hasValidVolume = true,
                    isValidRecord = true,
                )
                fake.tradePageSupplier = { _, _, _, _ ->
                    ExchangeTradePage(entries = listOf(fill), nextCursor = "next-cursor", valid = true)
                }

                val page = fake.getRecentTradePage()

                page.entries shouldBe listOf(fill)
                page.nextCursor shouldBe "next-cursor"
            }
        }
    })
