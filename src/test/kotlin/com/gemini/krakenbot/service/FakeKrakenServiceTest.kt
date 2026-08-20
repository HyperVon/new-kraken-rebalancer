package com.gemini.krakenbot.service

import com.gemini.krakenbot.model.LedgerEvent
import com.gemini.krakenbot.service.impl.KrakenApiConstants
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import java.math.BigDecimal
import java.time.Instant
import java.time.temporal.ChronoUnit

@OptIn(ExperimentalCoroutinesApi::class)
class FakeKrakenServiceTest :
    StringSpec() {
        init {
        isolationMode = IsolationMode.InstancePerTest

        val baseTime = Instant.parse("2026-06-01T00:00:00Z")

        fun event(index: Int, type: String = LedgerEvent.TYPE_STAKING, asset: String = "XBT") = LedgerEvent(
            ledgerId = "ledger-$index",
            time = baseTime.plus(index.toLong(), ChronoUnit.MINUTES),
            type = type,
            asset = asset,
            amount = BigDecimal("0.1"),
        )

        "getLedgers returns empty until entries are seeded" {
            runTest {
                val fake = FakeKrakenService()

                fake.getLedgers(null, null, null, null) shouldBe emptyList()
                fake.getLastLedgerTotalCount() shouldBe 0
            }
        }

        "seedLedgerEntries filters by requested types and time window" {
            runTest {
                val fake = FakeKrakenService()
                fake.seedLedgerEntries(
                    listOf(
                        event(1),
                        event(2, type = LedgerEvent.TYPE_DIVIDEND, asset = "STRC"),
                        event(3),
                    ),
                )
                val windowStart = baseTime.plus(2, ChronoUnit.MINUTES).epochSecond

                val staking = fake.getLedgers(windowStart, null, null, setOf(LedgerEvent.TYPE_STAKING))

                staking.map { it.ledgerId } shouldBe listOf("ledger-3")
                fake.getLastLedgerTotalCount() shouldBe 1
            }
        }

        "seedLedgerEntries pages newest-first at the Kraken page size and tracks the total" {
            runTest {
                val fake = FakeKrakenService()
                fake.seedLedgerEntries((1..KrakenApiConstants.LEDGER_PAGE_SIZE + 1).map { event(it) })

                val firstPage = fake.getLedgers(null, 0, null, null)
                val secondPage = fake.getLedgers(null, KrakenApiConstants.LEDGER_PAGE_SIZE, null, null)

                firstPage.size shouldBe KrakenApiConstants.LEDGER_PAGE_SIZE
                firstPage.first().ledgerId shouldBe "ledger-51"
                secondPage.map { it.ledgerId } shouldBe listOf("ledger-1")
                fake.getLastLedgerTotalCount() shouldBe 51
            }
        }
        }
    }
