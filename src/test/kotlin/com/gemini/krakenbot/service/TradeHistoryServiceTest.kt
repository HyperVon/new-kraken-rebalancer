package com.gemini.krakenbot.service

import com.gemini.krakenbot.model.PortfolioSnapshot
import com.gemini.krakenbot.repository.TradeRepository
import com.gemini.krakenbot.service.impl.TradeHistoryServiceImpl
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.math.BigDecimal
import java.time.Instant

class TradeHistoryServiceTest : StringSpec() {

    override fun isolationMode() = IsolationMode.InstancePerTest


    init {
        "init_LoadsHistoryFromRepository" {
            val repository = mockk<TradeRepository>(relaxed = true)
            val tradeHistoryService = TradeHistoryServiceImpl(repository)
            val snapshot = PortfolioSnapshot(Instant.now(), BigDecimal.ZERO, emptyMap(), emptyList(), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO)
            every { repository.load() } returns listOf(snapshot)
            tradeHistoryService.init()
            tradeHistoryService.getHistory().size shouldBe 1
            tradeHistoryService.getLatestSnapshot() shouldBe snapshot
        }

        "addSnapshot_AddsToFrontAndSaves" {
            val repository = mockk<TradeRepository>(relaxed = true)
            val tradeHistoryService = TradeHistoryServiceImpl(repository)
            val s1 = PortfolioSnapshot(Instant.now(), BigDecimal.ZERO, emptyMap(), emptyList(), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO)
            val s2 = PortfolioSnapshot(Instant.now(), BigDecimal.ZERO, emptyMap(), emptyList(), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO)
            tradeHistoryService.addSnapshot(s1)
            tradeHistoryService.addSnapshot(s2)
            tradeHistoryService.getHistory().size shouldBe 2
            tradeHistoryService.getLatestSnapshot() shouldBe s2
            verify(exactly = 2) { repository.save(any()) }
        }

        "addSnapshot_LimitsHistorySize" {
            val repository = mockk<TradeRepository>(relaxed = true)
            val tradeHistoryService = TradeHistoryServiceImpl(repository)
            for (i in 0 until 60) {
                tradeHistoryService.addSnapshot(PortfolioSnapshot(Instant.now(), BigDecimal.ZERO, emptyMap(), emptyList(), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO))
            }
            tradeHistoryService.getHistory().size shouldBe 50
            verify(atLeast = 1) { repository.save(any()) }
        }

        "init_HandlesNullLoaded" {
            val repository = mockk<TradeRepository>(relaxed = true)
            val tradeHistoryService = TradeHistoryServiceImpl(repository)
            every { repository.load() } returns emptyList()
            tradeHistoryService.init()
            tradeHistoryService.getHistory().isEmpty().shouldBeTrue()
        }

        "getLatestSnapshot_ReturnsNullWhenEmpty" {
            val repository = mockk<TradeRepository>(relaxed = true)
            val tradeHistoryService = TradeHistoryServiceImpl(repository)
            tradeHistoryService.getLatestSnapshot().shouldBeNull()
        }
    }
}
