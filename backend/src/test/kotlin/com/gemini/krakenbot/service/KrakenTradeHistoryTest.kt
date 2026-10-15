package com.gemini.krakenbot.service

import com.gemini.krakenbot.model.KrakenApiConstants
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.ktor.http.content.TextContent
import kotlinx.coroutines.test.runTest

class KrakenTradeHistoryTest : KrakenServiceTestBase() {
    init {
        "trade cursor page preserves native values and request-local bounds" {
            runTest {
                var path: String? = null
                var body: String? = null
                val service = createService(
                    responseContent =
                    """
                        {
                          "error": [],
                          "result": {
                            "trades": {
                              "T-NATIVE": {
                                "ordertxid": "O1",
                                "postxid": "P1",
                                "pair": "SOLUSDT",
                                "time": 1791892800,
                                "type": "sell",
                                "ordertype": "limit",
                                "price": "123.450000000000000001",
                                "cost": "12.3450000000000000001",
                                "fee": "0.01234500000000000001",
                                "vol": "0.1000000000000000001",
                                "margin": "0.00000000000000000001",
                                "misc": "stp",
                                "trade_id": "42",
                                "maker": true,
                                "ledgers": ["L1", "L2"]
                              }
                            },
                            "count": null,
                            "cursor": {"next": "cursor-next"}
                          }
                        }
                    """.trimIndent(),
                    onRequest = { request ->
                        path = request.url.encodedPath
                        body = (request.body as TextContent).text
                    },
                )

                val page = service.getAccountTradePage(
                    startSec = 1_700_000_000L,
                    endSec = 1_700_000_100L,
                    cursor = "cursor-current",
                    limit = 37,
                )

                path shouldBe KrakenApiConstants.PATH_TRADES_HISTORY
                body.shouldContain("start=1700000000")
                body.shouldContain("end=1700000100")
                body.shouldContain("with_cursor=true")
                body.shouldContain("trades=true")
                body.shouldContain("consolidate_taker=false")
                body.shouldContain("ledgers=true")
                body.shouldContain("cursor=cursor-current")
                body.shouldContain("limit=37")
                body?.contains("ofs=") shouldBe false

                page.valid shouldBe true
                page.entries.size shouldBe 1
                page.startSec shouldBe 1_700_000_000L
                page.endSec shouldBe 1_700_000_100L
                page.requestedCursor shouldBe "cursor-current"
                page.nextCursor shouldBe "cursor-next"
                page.count shouldBe null
                page.rawSize shouldBe 1
                val fill = page.entries.single()
                fill.tradeId shouldBe "T-NATIVE"
                fill.orderTxid shouldBe "O1"
                fill.postTxid shouldBe "P1"
                fill.pair shouldBe "SOLUSDT"
                fill.baseAsset shouldBe "SOL"
                fill.quoteCurrency shouldBe "USDT"
                fill.feeCurrency shouldBe null
                fill.rawTime shouldBe "1791892800"
                fill.price shouldBe "123.450000000000000001"
                fill.cost shouldBe "12.3450000000000000001"
                fill.fee shouldBe "0.01234500000000000001"
                fill.volume shouldBe "0.1000000000000000001"
                fill.margin shouldBe "0.00000000000000000001"
                fill.misc shouldBe "stp"
                fill.tradeSequenceId shouldBe "42"
                fill.maker shouldBe true
                fill.ledgerIds shouldBe listOf("L1", "L2")
                fill.rawRecordJson.shouldContain("\"pair\":\"SOLUSDT\"")
                fill.isValidRecord shouldBe true
            }
        }

        "ledger cursor page retains native asset suffixes and optional count" {
            runTest {
                var path: String? = null
                var body: String? = null
                val service = createService(
                    responseContent =
                    """
                        {
                          "error": [],
                          "result": {
                            "ledger": {
                              "L-DOT": {
                                "refid": "R1",
                                "time": 1791892800,
                                "type": "staking",
                                "subtype": "reward",
                                "aclass": "currency",
                                "asset": "DOT.S",
                                "amount": "1.234567890123456789",
                                "fee": "0.000000000000000001",
                                "balance": "10.234567890123456789"
                              },
                              "L-USD": {
                                "refid": "R2",
                                "time": 1791892801,
                                "type": "trade",
                                "subtype": "",
                                "aclass": "currency",
                                "asset": "ZUSD",
                                "amount": "-12.3400",
                                "fee": "0.0100",
                                "balance": "99.9900"
                              }
                            },
                            "count": null,
                            "cursor": {"next": null}
                          }
                        }
                    """.trimIndent(),
                    onRequest = { request ->
                        path = request.url.encodedPath
                        body = (request.body as TextContent).text
                    },
                )

                val page = service.getAccountLedgerPage(
                    startSec = 1_700_000_000L,
                    endSec = 1_700_000_100L,
                    cursor = "ledger-current",
                )

                path shouldBe KrakenApiConstants.PATH_LEDGERS
                body.shouldContain("start=1700000000")
                body.shouldContain("end=1700000100")
                body.shouldContain("type=all")
                body.shouldContain("with_cursor=true")
                body.shouldContain("cursor=ledger-current")
                body?.contains("ofs=") shouldBe false

                page.valid shouldBe true
                page.entries.size shouldBe 2
                page.startSec shouldBe 1_700_000_000L
                page.endSec shouldBe 1_700_000_100L
                page.requestedCursor shouldBe "ledger-current"
                page.nextCursor shouldBe null
                page.count shouldBe null
                page.rawSize shouldBe 2
                val staking = page.entries.first()
                staking.ledgerId shouldBe "L-DOT"
                staking.refid shouldBe "R1"
                staking.assetClass shouldBe "currency"
                staking.asset shouldBe "DOT.S"
                staking.amount shouldBe "1.234567890123456789"
                staking.fee shouldBe "0.000000000000000001"
                staking.balance shouldBe "10.234567890123456789"
                staking.rawTime shouldBe "1791892800"
                staking.hasValidAmount shouldBe true
                staking.rawRecordJson.shouldContain("\"asset\":\"DOT.S\"")

                val usd = page.entries.last()
                usd.asset shouldBe "ZUSD"
                usd.amount shouldBe "-12.3400"
                usd.hasValidAmount shouldBe true
            }
        }

        "malformed credentials are rejected before either cursor endpoint is queried" {
            runTest {
                val service = createService("{\"error\":[],\"result\":{\"trades\":{}}}")
                val thrown = shouldThrow<IllegalArgumentException> {
                    service.getAccountTradePage(limit = 101)
                }
                thrown.message.shouldContain("between 1 and 100")
            }
        }
    }

    private fun String?.shouldContain(value: String) {
        this?.contains(value) shouldBe true
    }
}
