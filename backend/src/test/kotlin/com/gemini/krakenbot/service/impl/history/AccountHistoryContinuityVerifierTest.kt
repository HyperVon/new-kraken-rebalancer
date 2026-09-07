package com.gemini.krakenbot.service.impl.history

import com.gemini.krakenbot.TestFixtures
import com.gemini.krakenbot.config.DatabaseConfig
import com.gemini.krakenbot.model.Asset
import com.gemini.krakenbot.model.LedgerEvent
import com.gemini.krakenbot.model.TradeRecord
import com.gemini.krakenbot.model.TradeSource
import com.gemini.krakenbot.repository.TradeRepository
import com.gemini.krakenbot.repository.impl.SqliteLedgerRepositoryImpl
import com.gemini.krakenbot.repository.impl.SqliteTradeRepositoryImpl
import com.gemini.krakenbot.service.FakeKrakenService
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import java.math.BigDecimal
import java.time.Instant

class AccountHistoryContinuityVerifierTest : StringSpec() {
    override fun isolationMode() = IsolationMode.InstancePerTest

    private val database = DatabaseConfig.init(TestFixtures.MEMORY_)
    private val tradeRepository = SqliteTradeRepositoryImpl(database)
    private val ledgerRepository = SqliteLedgerRepositoryImpl(database)
    private val krakenService = FakeKrakenService()
    private val now = Instant.parse("2026-05-01T00:00:00Z")
    private val verifier = AccountHistoryContinuityVerifier(
        krakenService,
        tradeRepository,
        ledgerRepository,
        nowProvider = { now },
    )

    init {
        "verifies continuity on newest-page trade overlap" {
            runTest {
                tradeRepository.saveTrade(storedTrade(id = "fill-1", orderId = "order-1"))
                krakenService.tradeHistorySupplier = { _, _ -> listOf(exchangeTrade(id = "fill-1")) }

                verifier.verifyContinuity() shouldBe AccountHistoryContinuityStatus.VERIFIED
            }
        }

        "verifies continuity on order id overlap with blank and missing peer ids" {
            runTest {
                tradeRepository.saveTrade(storedTrade(id = "  ", orderId = "order-9"))
                krakenService.tradeHistorySupplier =
                    { _, _ ->
                        listOf(
                            exchangeTrade(id = null, orderId = "order-9"),
                            exchangeTrade(id = " ", orderId = null),
                        )
                    }

                verifier.verifyContinuity() shouldBe AccountHistoryContinuityStatus.VERIFIED
            }
        }

        "verifies continuity from the deepest trade page" {
            runTest {
                tradeRepository.saveTrade(storedTrade(id = "ancient-fill"))
                val newest = (0 until 50).map { exchangeTrade(id = "new-$it") }
                krakenService.tradeHistoryTotalCountOverride = 100
                krakenService.tradeHistorySupplier = { _, offset ->
                    if ((offset ?: 0) >= 50) {
                        listOf(exchangeTrade(id = "ancient-fill"))
                    } else {
                        newest
                    }
                }

                verifier.verifyContinuity() shouldBe AccountHistoryContinuityStatus.VERIFIED
            }
        }

        "verifies continuity from the deepest ledger page without stored trades" {
            runTest {
                ledgerRepository.saveLedgers(
                    listOf(
                        storedLedger(id = " "),
                        storedLedger(id = "ancient-ledger"),
                    ),
                )
                val newest = (0 until 50).map { exchangeLedger(id = "new-$it") }
                krakenService.ledgerTotalCountOverride = 100
                krakenService.ledgerSupplier = { _, offset, _, _ ->
                    if ((offset ?: 0) >= 50) {
                        listOf(exchangeLedger(id = "ancient-ledger"))
                    } else {
                        newest
                    }
                }

                verifier.verifyContinuity() shouldBe AccountHistoryContinuityStatus.VERIFIED
            }
        }

        "verifies continuity on ledger overlap when trades do not overlap" {
            runTest {
                tradeRepository.saveTrade(storedTrade(id = "local-fill"))
                ledgerRepository.saveLedgers(listOf(storedLedger(id = "ledger-7")))
                krakenService.tradeHistorySupplier = { _, _ -> listOf(exchangeTrade(id = "foreign-fill")) }
                krakenService.ledgerSupplier = { _, _, _, _ -> listOf(exchangeLedger(id = "ledger-7")) }

                verifier.verifyContinuity() shouldBe AccountHistoryContinuityStatus.VERIFIED
            }
        }

        "reports no overlap for disjoint histories" {
            runTest {
                tradeRepository.saveTrade(storedTrade(id = "local-fill"))
                krakenService.tradeHistorySupplier = { _, _ -> listOf(exchangeTrade(id = "foreign-fill")) }

                verifier.verifyContinuity() shouldBe AccountHistoryContinuityStatus.NO_OVERLAP
            }
        }

        "reports no overlap when stored rows carry no exchange ids" {
            runTest {
                tradeRepository.saveTrade(
                    TestFixtures.tradeRecord(
                        timestamp = Instant.parse("2026-01-01T00:00:00Z"),
                        pair = Asset.BTC_USD_PAIR,
                        side = "buy",
                        symbol = Asset.BTC,
                        volume = BigDecimal("0.01"),
                        usdAmount = BigDecimal("100.00"),
                        price = BigDecimal("10000.00"),
                    ),
                )
                krakenService.tradeHistorySupplier = { _, _ -> listOf(exchangeTrade(id = "foreign-fill")) }

                verifier.verifyContinuity() shouldBe AccountHistoryContinuityStatus.NO_OVERLAP
            }
        }

        "reports unavailable when the exchange cannot be reached" {
            runTest {
                tradeRepository.saveTrade(storedTrade(id = "local-fill"))
                krakenService.tradeHistorySupplier = { _, _ -> error("network") }

                verifier.verifyContinuity() shouldBe AccountHistoryContinuityStatus.UNAVAILABLE
            }
        }

        "propagates cancellation instead of reporting unavailable" {
            runTest {
                val tradeRepository = mockk<TradeRepository>()
                coEvery { tradeRepository.getTradesInRange(any(), any()) } throws CancellationException("cancelled")
                val cancelling = AccountHistoryContinuityVerifier(
                    krakenService,
                    tradeRepository,
                    ledgerRepository,
                    nowProvider = { now },
                )

                shouldThrow<CancellationException> { cancelling.verifyContinuity() }
            }
        }
    }

    private fun storedTrade(
        id: String?,
        orderId: String? = id?.let {
            "order-$it"
        },
    ): TradeRecord = TestFixtures.tradeRecord(
        timestamp = Instant.parse("2026-01-01T00:00:00Z"),
        pair = Asset.BTC_USD_PAIR,
        side = "buy",
        symbol = Asset.BTC,
        volume = BigDecimal("0.01"),
        usdAmount = BigDecimal("100.00"),
        price = BigDecimal("10000.00"),
        source = TradeSource.API_FILL,
        tradeId = id,
        orderTxid = orderId,
    )

    private fun exchangeTrade(
        id: String?,
        orderId: String? = id?.let {
            "order-$it"
        },
    ): TradeRecord = TestFixtures.tradeRecord(
        timestamp = Instant.parse("2026-01-01T00:00:00Z"),
        pair = Asset.BTC_USD_PAIR,
        side = "buy",
        symbol = Asset.BTC,
        volume = BigDecimal("0.01"),
        usdAmount = BigDecimal("100.00"),
        price = BigDecimal("10000.00"),
        source = TradeSource.API_FILL,
        tradeId = id,
        orderTxid = orderId,
    )

    private fun storedLedger(id: String): LedgerEvent = LedgerEvent(
        ledgerId = id,
        time = Instant.parse("2026-01-01T00:00:00Z"),
        type = "staking",
        asset = Asset.BTC,
        amount = BigDecimal.ZERO,
    )

    private fun exchangeLedger(id: String): LedgerEvent = storedLedger(id)
}
