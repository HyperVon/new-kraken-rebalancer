package com.gemini.krakenbot.service

import com.gemini.krakenbot.model.KrakenApiConstants
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.ktor.http.content.TextContent
import kotlinx.coroutines.test.runTest

class KrakenTradeFillParserTest : KrakenServiceTestBase() {
    init {
        "recent trade page preserves the fields needed to confirm a sell fill" {
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
                                "pair": "SOLUSDT",
                                "time": 1791892800,
                                "type": "sell",
                                "price": "123.450000000000000001",
                                "cost": "12.3450000000000000001",
                                "fee": "0.01234500000000000001",
                                "vol": "0.1000000000000000001"
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

                val page = service.getRecentTradePage(
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
                body.shouldContain("cursor=cursor-current")
                body.shouldContain("limit=37")
                body?.contains("ofs=") shouldBe false

                page.valid shouldBe true
                page.nextCursor shouldBe "cursor-next"
                val fill = page.entries.single()
                fill.tradeId shouldBe "T-NATIVE"
                fill.orderTxid shouldBe "O1"
                fill.baseAsset shouldBe "SOL"
                fill.quoteCurrency shouldBe "USDT"
                fill.feeCurrency shouldBe null
                fill.type shouldBe "sell"
                fill.price shouldBe "123.450000000000000001"
                fill.cost shouldBe "12.3450000000000000001"
                fill.fee shouldBe "0.01234500000000000001"
                fill.volume shouldBe "0.1000000000000000001"
                fill.hasValidTime shouldBe true
                fill.isValidRecord shouldBe true
            }
        }

        "malformed trade rows remain incomplete instead of looking like valid fills" {
            runTest {
                val service = createService(
                    """
                        {"error":[],"result":{"trades":{"T-BAD":{"ordertxid":"O1","pair":"SOLUSD","type":"sell","time":"bad","price":"NaN","cost":"-1","fee":"oops","vol":"0"}},"cursor":{"next":null}}}
                    """.trimIndent(),
                )

                val fill = service.getRecentTradePage().entries.single()

                fill.isValidRecord shouldBe true
                fill.hasValidTime shouldBe false
                fill.hasValidPrice shouldBe false
                fill.hasValidCost shouldBe false
                fill.hasValidFee shouldBe false
                fill.hasValidVolume shouldBe false
            }
        }

        "invalid limits are rejected before the trade endpoint is queried" {
            runTest {
                val service = createService("{\"error\":[],\"result\":{\"trades\":{}}}")
                val thrown = shouldThrow<IllegalArgumentException> {
                    service.getRecentTradePage(limit = 101)
                }
                thrown.message.shouldContain("between 1 and 100")
            }
        }
    }

    private fun String?.shouldContain(value: String) {
        this?.contains(value) shouldBe true
    }
}
