package com.gemini.krakenbot.service.reporting

import com.gemini.krakenbot.config.Allocation
import com.gemini.krakenbot.service.history.HISTORY_MAX_PAGE_LIMIT
import com.gemini.krakenbot.service.history.HistoryAssetValue
import com.gemini.krakenbot.service.history.HistoryAssetValueStatus
import com.gemini.krakenbot.service.history.HistoryCycleActivity
import com.gemini.krakenbot.service.history.HistoryEventQuery
import com.gemini.krakenbot.service.history.HistoryFeed
import com.gemini.krakenbot.service.history.HistoryLedger
import com.gemini.krakenbot.service.history.HistoryObservation
import com.gemini.krakenbot.service.history.HistoryObservationStatus
import com.gemini.krakenbot.service.history.HistoryPage
import com.gemini.krakenbot.service.history.HistoryRangeQuery
import com.gemini.krakenbot.service.history.HistoryService
import com.gemini.krakenbot.service.history.HistorySourceMode
import com.gemini.krakenbot.service.history.HistorySourceScope
import com.gemini.krakenbot.service.history.HistorySyncJobState
import com.gemini.krakenbot.service.history.HistorySyncProgressSummary
import com.gemini.krakenbot.service.history.HistoryTrade
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.comparables.shouldBeEqualComparingTo
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.mockk
import java.math.BigDecimal
import java.time.Instant

class HistoryReportingServiceTest : StringSpec() {
    override fun isolationMode() = IsolationMode.InstancePerTest

    private val historyService = mockk<HistoryService>()
    private val fixedNow = Instant.parse("2026-06-01T12:00:00Z")
    private val reporting = HistoryReportingService(historyService, nowProvider = { fixedNow })

    private val fingerprint = "fedcba9876543210fedcba9876543210fedcba9876543210fedcba9876543210"
    private val accountDigest = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"

    private val accountScope = HistorySourceScope(
        mode = HistorySourceMode.ACCOUNT,
        managedScopeFingerprint = fingerprint,
        managedScopeSymbols = listOf("BTC", "USD"),
        accountIdentityDigest = accountDigest,
    )

    private val simulationScope = HistorySourceScope(
        mode = HistorySourceMode.SIMULATION,
        managedScopeFingerprint = fingerprint,
        managedScopeSymbols = listOf("BTC", "USD"),
        simulationSessionId = "sim-reporting",
    )

    private val period = HistoryReportingPeriod(
        sourceScope = accountScope,
        from = Instant.parse("2026-05-01T00:00:00Z"),
        until = Instant.parse("2026-06-01T00:00:00Z"),
    )

    init {
        coEvery { historyService.syncProgress(any()) } returns listOf(
            coveredProgress(HistoryFeed.TRADES),
            coveredProgress(HistoryFeed.LEDGERS),
        )
        coEvery { historyService.queryActivityPage(any()) } returns activityPage(emptyList(), total = 0L)

        "uncovered empty trade history is not an authoritative zero" {
            coEvery { historyService.queryTrades(any()) } returns page(emptyList())
            coEvery { historyService.syncProgress(any()) } returns emptyList()

            val stats = reporting.tradeStatistics(period)

            stats.tradeCount shouldBe 0L
            stats.complete shouldBe false
            stats.incompleteReason shouldBe "Not synchronized for this period"
        }

        "a stale recent horizon leaves the selected period unsynchronized" {
            coEvery { historyService.queryTrades(any()) } returns page(emptyList())
            coEvery { historyService.syncProgress(any()) } returns listOf(
                coveredProgress(HistoryFeed.TRADES).copy(
                    recentCompletedThroughSec = Instant.parse("2026-05-02T00:00:00Z").epochSecond,
                ),
            )

            val stats = reporting.tradeStatistics(period)

            stats.complete shouldBe false
            stats.incompleteReason shouldBe "Not synchronized for this period"
        }

        "uncovered ledger aggregates are not an authoritative zero" {
            coEvery { historyService.queryLedgers(any()) } returns page(emptyList())
            coEvery { historyService.syncProgress(any()) } returns emptyList()

            val flows = reporting.depositsWithdrawals(period)
            val rewards = reporting.rewards(period)

            flows.complete shouldBe false
            flows.incompleteReason shouldBe "Not synchronized for this period"
            rewards.complete shouldBe false
            rewards.incompleteReason shouldBe "Not synchronized for this period"
        }

        "uncovered ledgers cannot mark deposit or withdrawal rows complete" {
            coEvery { historyService.queryLedgers(any()) } returns page(
                listOf(
                    ledger("l1", type = "deposit", asset = "ZUSD", amount = "100.00"),
                    ledger("l2", type = "withdrawal", asset = "XXBT", amount = "-0.25"),
                ),
            )
            coEvery { historyService.syncProgress(any()) } returns emptyList()

            val flows = reporting.depositsWithdrawals(period)

            flows.deposits.complete shouldBe false
            flows.deposits.incompleteReason shouldBe "Not synchronized for this period"
            flows.withdrawals.complete shouldBe false
            flows.withdrawals.incompleteReason shouldBe "Not synchronized for this period"
            flows.complete shouldBe false
            flows.incompleteReason shouldBe "Not synchronized for this period"
        }

        "trade volume and fees group by currency and never sum mixed currencies" {
            coEvery { historyService.queryTrades(any()) } returns page(
                listOf(
                    trade("t1", cost = "100.00", fee = "1.00", quoteCurrency = "USD"),
                    trade("t2", cost = "0.02", fee = "0.0001", quoteCurrency = "BTC"),
                    trade("t3", cost = "50.00", fee = "0.50", quoteCurrency = "EUR"),
                ),
            )

            val stats = reporting.tradeStatistics(period)

            stats.tradeCount shouldBe 3L
            stats.volumeByCurrency.getValue("USD").shouldBeEqualComparingTo(BigDecimal("100.00"))
            stats.volumeByCurrency.getValue("EUR").shouldBeEqualComparingTo(BigDecimal("50.00"))
            stats.volumeByCurrency.getValue("BTC").shouldBeEqualComparingTo(BigDecimal("0.02"))
            stats.feesByCurrency.getValue("USD").shouldBeEqualComparingTo(BigDecimal("1.00"))
            stats.feesByCurrency.getValue("EUR").shouldBeEqualComparingTo(BigDecimal("0.50"))
            stats.feesByCurrency.getValue("BTC").shouldBeEqualComparingTo(BigDecimal("0.0001"))
            stats.excludedCount shouldBe 0
            stats.complete shouldBe true
            stats.incompleteReason shouldBe null
        }

        "observed value change is Unavailable with fewer than two complete points" {
            coEvery { historyService.queryValues(any()) } returns page(
                listOf(observation("o1", Instant.parse("2026-05-05T00:00:00Z"), "101.00")),
            )

            val change = reporting.observedValueChange(period)

            change.changeUsd shouldBe null
            change.firstValueUsd shouldBe null
            change.lastValueUsd shouldBe null
            change.complete shouldBe false
            change.incompleteReason shouldBe "Unavailable"
            change.includesFlowsNotice shouldBe "Includes deposits, withdrawals and market moves"
        }

        "observed value change spans the period endpoints with both timestamps" {
            coEvery { historyService.queryValues(any()) } returns page(
                listOf(
                    observation("o1", Instant.parse("2026-05-20T00:00:00Z"), "150.00"),
                    observation("o2", Instant.parse("2026-05-05T00:00:00Z"), "100.00"),
                ),
            )

            val change = reporting.observedValueChange(period)

            change.changeUsd!!.shouldBeEqualComparingTo(BigDecimal("50.00"))
            change.firstValueUsd!!.shouldBeEqualComparingTo(BigDecimal("100.00"))
            change.firstObservedAt shouldBe Instant.parse("2026-05-05T00:00:00Z")
            change.lastValueUsd!!.shouldBeEqualComparingTo(BigDecimal("150.00"))
            change.lastObservedAt shouldBe Instant.parse("2026-05-20T00:00:00Z")
            change.complete shouldBe true
        }

        "latest observed value skips incomplete rows and reports the most recent complete observation" {
            coEvery { historyService.queryValues(any()) } returns page(
                listOf(
                    observation("o1", Instant.parse("2026-05-20T00:00:00Z"), totalUsd = null),
                    observation("o2", Instant.parse("2026-04-01T00:00:00Z"), "250.00"),
                ),
            )

            val latest = reporting.latestObservedValue(period)

            latest.valueUsd!!.shouldBeEqualComparingTo(BigDecimal("250.00"))
            latest.observedAt shouldBe Instant.parse("2026-04-01T00:00:00Z")
            latest.outsideSelectedPeriod shouldBe true
            latest.complete shouldBe true
        }

        "latest observed value is Unavailable instead of a fabricated zero when nothing is complete" {
            coEvery { historyService.queryValues(any()) } returns page(
                listOf(observation("o1", Instant.parse("2026-05-20T00:00:00Z"), totalUsd = null)),
            )

            val latest = reporting.latestObservedValue(period)

            latest.valueUsd shouldBe null
            latest.observedAt shouldBe null
            latest.complete shouldBe false
            latest.incompleteReason shouldBe "Unavailable"
        }

        "account-scoped statistics are Unavailable without an account source instead of zero counts" {
            val simPeriod = HistoryReportingPeriod(simulationScope)

            val trades = reporting.tradeStatistics(simPeriod)
            val flows = reporting.depositsWithdrawals(simPeriod)
            val rewards = reporting.rewards(simPeriod)

            trades.tradeCount shouldBe null
            trades.complete shouldBe false
            trades.incompleteReason shouldBe "Unavailable"
            flows.complete shouldBe false
            flows.incompleteReason shouldBe "Unavailable"
            flows.deposits.incompleteReason shouldBe "Unavailable"
            rewards.quantitiesByAsset shouldBe emptyMap<String, BigDecimal>()
            rewards.complete shouldBe false
            rewards.incompleteReason shouldBe "Unavailable"
        }

        "range extrema cover the whole selected period rather than one visible page" {
            coEvery { historyService.queryValues(any()) } coAnswers {
                when (firstArg<HistoryRangeQuery>().offset) {
                    0 -> page(
                        listOf(
                            observation("o1", Instant.parse("2026-05-03T00:00:00Z"), "100.00"),
                            observation("o2", Instant.parse("2026-05-04T00:00:00Z"), "50.00"),
                        ),
                        hasMore = true,
                    )

                    else -> page(
                        listOf(
                            observation("o3", Instant.parse("2026-05-20T00:00:00Z"), "500.00"),
                            observation("o4", Instant.parse("2026-05-21T00:00:00Z"), "10.00"),
                        ),
                        offset = 2,
                    )
                }
            }

            val extrema = reporting.rangeExtrema(period)

            val high = extrema.high!!
            high.label shouldBe "Range high"
            high.valueUsd.shouldBeEqualComparingTo(BigDecimal("500.00"))
            high.observedAt shouldBe Instant.parse("2026-05-20T00:00:00Z")
            val low = extrema.low!!
            low.label shouldBe "Range low"
            low.valueUsd.shouldBeEqualComparingTo(BigDecimal("10.00"))
            low.observedAt shouldBe Instant.parse("2026-05-21T00:00:00Z")
            extrema.complete shouldBe true
        }

        "trade count and bot cycle count stay distinct" {
            coEvery { historyService.queryTrades(any()) } returns page(
                listOf(
                    trade("t1", cost = "10.00", fee = "0.10", quoteCurrency = "USD"),
                    trade("t2", cost = "20.00", fee = "0.20", quoteCurrency = "USD"),
                ),
            )
            coEvery { historyService.queryActivityPage(any()) } returns activityPage(
                listOf(
                    cycle("c1", Instant.parse("2026-05-05T00:00:00Z")),
                    cycle("c2", Instant.parse("2026-05-06T00:00:00Z")),
                    cycle("c3", Instant.parse("2026-05-07T00:00:00Z")),
                ),
            )

            val trades = reporting.tradeStatistics(period)
            val botCycles = reporting.botCycles(period)

            trades.tradeCount shouldBe 2L
            botCycles.cycleCount shouldBe 3L
            botCycles.complete shouldBe true
            botCycles.incompleteReason shouldBe null
            reporting.botCycles(HistoryReportingPeriod(accountScope)).cycleCount shouldBe 3L
        }

        "invalid and unknown amounts are excluded with an explicit count and Incomplete state" {
            coEvery { historyService.queryTrades(any()) } returns page(
                listOf(
                    trade("t1", cost = "10.00", fee = "0.10", quoteCurrency = "USD"),
                    trade(
                        "t2",
                        cost = "0.50",
                        fee = "0.20",
                        quoteCurrency = "USD",
                        hasValidCost = false,
                        hasValidFee = false,
                    ),
                    trade("t3", cost = "5.00", fee = null, quoteCurrency = null),
                    trade("t4", cost = null, fee = "0.05", quoteCurrency = "USD"),
                    trade("t5", cost = "5.00", fee = "0.05", quoteCurrency = "   "),
                ),
            )

            val stats = reporting.tradeStatistics(period)

            stats.tradeCount shouldBe 5L
            stats.volumeByCurrency.getValue("USD").shouldBeEqualComparingTo(BigDecimal("10.00"))
            stats.feesByCurrency.getValue("USD").shouldBeEqualComparingTo(BigDecimal("0.15"))
            stats.excludedCount shouldBe 7
            stats.complete shouldBe false
            stats.incompleteReason shouldBe "Incomplete"
        }

        "rewards report native asset quantities only with no USD conversion path" {
            coEvery { historyService.queryLedgers(any()) } returns page(
                listOf(
                    ledger("l1", type = "staking", asset = "XXBT", amount = "0.5"),
                    ledger("l2", type = "dividend", asset = "ZUSD", amount = "2.00"),
                ),
            )

            val rewards = reporting.rewards(period)

            rewards.quantitiesByAsset.getValue("XXBT").shouldBeEqualComparingTo(BigDecimal("0.5"))
            rewards.quantitiesByAsset.getValue("ZUSD").shouldBeEqualComparingTo(BigDecimal("2.00"))
            rewards.complete shouldBe true
            rewards.javaClass.declaredFields.none { it.name.contains("usd", ignoreCase = true) } shouldBe true
        }

        "allocation deviation is labelled in percentage points against the targets used" {
            coEvery { historyService.queryValues(any()) } returns page(
                listOf(
                    observation(
                        "o1",
                        Instant.parse("2026-05-31T00:00:00Z"),
                        "100.00",
                        assets = listOf(asset("BTC", "60.00"), asset("USD", "40.00")),
                    ),
                ),
            )

            val deviation = reporting.allocationDeviation(
                period,
                listOf(Allocation("BTC", 70.0), Allocation("USD", 30.0)),
            )

            deviation.label shouldBe "Deviation from current targets (percentage points)"
            deviation.targetsUsed.getValue("BTC").shouldBeEqualComparingTo(BigDecimal("70"))
            deviation.deviationsBySymbol.getValue("BTC").shouldBeEqualComparingTo(BigDecimal("-10"))
            deviation.deviationsBySymbol.getValue("USD").shouldBeEqualComparingTo(BigDecimal("10"))
            deviation.observedAt shouldBe Instant.parse("2026-05-31T00:00:00Z")
            deviation.complete shouldBe true
        }

        "allocation deviation is Incomplete rather than a fabricated zero for a missing target symbol" {
            coEvery { historyService.queryValues(any()) } returns page(
                listOf(
                    observation(
                        "o1",
                        Instant.parse("2026-05-31T00:00:00Z"),
                        "100.00",
                        assets = listOf(asset("BTC", "60.00"), asset("USD", null)),
                    ),
                ),
            )

            val deviation = reporting.allocationDeviation(
                period,
                listOf(Allocation("BTC", 50.0), Allocation("USD", 50.0)),
            )

            deviation.deviationsBySymbol.keys shouldBe setOf("BTC")
            deviation.deviationsBySymbol.getValue("BTC").shouldBeEqualComparingTo(BigDecimal("10"))
            deviation.complete shouldBe false
            deviation.incompleteReason shouldBe "Incomplete"
        }

        "deposits and withdrawals keep native per-asset quantities with invalid rows excluded" {
            coEvery { historyService.queryLedgers(any()) } returns page(
                listOf(
                    ledger("l1", type = "deposit", asset = "ZUSD", amount = "100.00"),
                    ledger("l2", type = "withdrawal", asset = "XXBT", amount = "-0.25"),
                    ledger("l3", type = "deposit", asset = "XXBT", amount = "bogus"),
                    ledger("l4", type = "staking", asset = "XXBT", amount = "0.5"),
                    ledger("l5", type = "deposit", asset = "ZUSD", amount = "5.00", hasValidAmount = false),
                    ledger("l6", type = "deposit", asset = null, amount = "1.00"),
                ),
            )

            val flows = reporting.depositsWithdrawals(period)

            flows.deposits.quantitiesByAsset.keys shouldBe setOf("ZUSD")
            flows.deposits.quantitiesByAsset.getValue("ZUSD").shouldBeEqualComparingTo(BigDecimal("100.00"))
            flows.deposits.excludedCount shouldBe 3
            flows.withdrawals.quantitiesByAsset.getValue("XXBT").shouldBeEqualComparingTo(BigDecimal("-0.25"))
            flows.withdrawals.complete shouldBe true
            flows.complete shouldBe false
            flows.incompleteReason shouldBe "Incomplete"
            flows.notice shouldBe "Ledger classification is not proof of external capital."
        }

        "account-only ledger statistics remain Unavailable in simulation scope" {
            val unverifiedPeriod = period.copy(
                sourceScope = simulationScope,
            )

            val flows = reporting.depositsWithdrawals(unverifiedPeriod)
            val rewards = reporting.rewards(unverifiedPeriod)

            flows.complete shouldBe false
            flows.incompleteReason shouldBe "Unavailable"
            flows.deposits.quantitiesByAsset shouldBe emptyMap()
            flows.withdrawals.quantitiesByAsset shouldBe emptyMap()
            rewards.complete shouldBe false
            rewards.incompleteReason shouldBe "Unavailable"
            rewards.quantitiesByAsset shouldBe emptyMap()
        }

        "bot cycle totals count every cycle in the selected interval beyond the recent page cap" {
            val start = Instant.parse("2026-05-01T00:00:00Z")
            coEvery { historyService.queryActivityPage(any()) } returns activityPage(
                (0 until 100).map { index -> cycle("c$index", start.plusSeconds(index.toLong())) },
                total = 250L,
            )

            val botCycles = reporting.botCycles(period)

            botCycles.cycleCount shouldBe 250L
            botCycles.complete shouldBe true
            botCycles.incompleteReason shouldBe null
        }

        "empty complete query reports zero with a complete state" {
            coEvery { historyService.queryValues(any()) } returns page(emptyList())
            coEvery { historyService.queryTrades(any()) } returns page(emptyList())
            coEvery { historyService.queryLedgers(any()) } returns page(emptyList())
            coEvery { historyService.queryActivityPage(any()) } returns activityPage(emptyList(), total = 0L)

            val stats = reporting.computeStatistics(period, listOf(Allocation("BTC", 60.0), Allocation("USD", 40.0)))

            stats.generatedAt shouldBe fixedNow
            stats.trades.tradeCount shouldBe 0L
            stats.trades.complete shouldBe true
            stats.botCycles.cycleCount shouldBe 0L
            stats.botCycles.complete shouldBe true
            stats.rewards.quantitiesByAsset shouldBe emptyMap<String, BigDecimal>()
            stats.rewards.complete shouldBe true
            stats.depositsWithdrawals.complete shouldBe true
            stats.rangeExtrema.high shouldBe null
            stats.rangeExtrema.incompleteReason shouldBe "Unavailable"
            stats.observedValueChange.changeUsd shouldBe null
            stats.observedValueChange.incompleteReason shouldBe "Unavailable"
            reporting.allocationDeviation(period, emptyList()).incompleteReason shouldBe "Unavailable"
        }

        "reward classification covers earn-reward and transfer-airdrop rows" {
            coEvery { historyService.queryLedgers(any()) } returns page(
                listOf(
                    ledger("l1", type = "earn", subtype = "reward", asset = "XXBT", amount = "0.1"),
                    ledger("l2", type = "transfer", subtype = "airdrop", asset = "XXBT", amount = "0.2"),
                    ledger("l3", type = "transfer", subtype = "spottostaking", asset = "XXBT", amount = "9.9"),
                    ledger("l4", type = "earn", subtype = "interest", asset = "XXBT", amount = "8.8"),
                    ledger("l5", type = null, subtype = null, asset = "XXBT", amount = "7.7"),
                ),
            )

            val rewards = reporting.rewards(period)

            rewards.quantitiesByAsset.keys shouldBe setOf("XXBT")
            rewards.quantitiesByAsset.getValue("XXBT").shouldBeEqualComparingTo(BigDecimal("0.3"))
            rewards.complete shouldBe true
        }

        "fees follow an explicit fee currency when the trade records one" {
            coEvery { historyService.queryTrades(any()) } returns page(
                listOf(trade("t1", cost = "10.00", fee = "0.25", quoteCurrency = "USD").copy(feeCurrency = "USDT")),
            )

            val stats = reporting.tradeStatistics(period)

            stats.volumeByCurrency.getValue("USD").shouldBeEqualComparingTo(BigDecimal("10.00"))
            stats.feesByCurrency.getValue("USDT").shouldBeEqualComparingTo(BigDecimal("0.25"))
            stats.complete shouldBe true
        }

        "reporting period bounds include from and exclude until" {
            val bounded = HistoryReportingPeriod(
                sourceScope = accountScope,
                from = Instant.parse("2026-05-01T00:00:00Z"),
                until = Instant.parse("2026-06-01T00:00:00Z"),
            )

            bounded.contains(Instant.parse("2026-05-01T00:00:00Z")) shouldBe true
            bounded.contains(Instant.parse("2026-06-01T00:00:00Z")) shouldBe false
            bounded.contains(Instant.parse("2026-04-30T23:59:59Z")) shouldBe false
            bounded.isOutside(Instant.parse("2026-04-30T23:59:59Z")) shouldBe true
            bounded.isOutside(Instant.parse("2026-05-31T23:59:59Z")) shouldBe false
        }

        "bot cycle totals count the selected interval without a recent page cap" {
            val start = Instant.parse("2026-05-01T00:00:00Z")
            coEvery { historyService.queryActivityPage(any()) } returns activityPage(
                (0 until 99).map { index -> cycle("c$index", start.plusSeconds(index.toLong())) },
                total = 99L,
            )

            val botCycles = reporting.botCycles(period)

            botCycles.cycleCount shouldBe 99L
            botCycles.complete shouldBe true
            botCycles.incompleteReason shouldBe null
        }

        "allocation deviation is Unavailable for a zero total instead of dividing by zero" {
            coEvery { historyService.queryValues(any()) } returns page(
                listOf(observation("o1", Instant.parse("2026-05-31T00:00:00Z"), "0.00")),
            )

            val deviation = reporting.allocationDeviation(period, listOf(Allocation("BTC", 100.0)))

            deviation.deviationsBySymbol shouldBe emptyMap<String, BigDecimal>()
            deviation.observedAt shouldBe Instant.parse("2026-05-31T00:00:00Z")
            deviation.complete shouldBe false
            deviation.incompleteReason shouldBe "Unavailable"
        }

        "native funding and reward series keep assets separate and require no price samples" {
            val firstAt = Instant.parse("2026-05-01T00:00:00Z")
            val secondAt = Instant.parse("2026-05-02T00:00:00Z")
            val funding = reporting.fundingQuantitySeries(
                listOf(
                    ledger("d1", "deposit", "ZUSD", "100.00").copy(time = firstAt),
                    ledger("w1", "withdrawal", "ZUSD", "40.00").copy(time = secondAt),
                    ledger("d2", "deposit", "XXBT", "0.5").copy(time = secondAt),
                    ledger("trade", "trade", "ZUSD", "500.00").copy(time = secondAt),
                    ledger("invalid", "deposit", "ZUSD", "bad", hasValidAmount = false).copy(time = secondAt),
                    ledger("flagged", "deposit", "ZUSD", "50.00", hasValidAmount = false).copy(time = secondAt),
                ),
            )

            funding.map { it.asset } shouldBe listOf("XXBT", "ZUSD")
            funding.first().points.single().cumulativeQuantity.shouldBeEqualComparingTo(BigDecimal("0.5"))
            funding.last().points.map { it.observedAt } shouldBe listOf(firstAt, secondAt)
            funding.last().points.map { it.cumulativeQuantity } shouldBe
                listOf(BigDecimal("100.00"), BigDecimal("60.00"))

            val rewards = reporting.rewardQuantitySeries(
                listOf(
                    ledger("r1", "staking", "XXBT", "0.001", subtype = "reward").copy(time = firstAt),
                    ledger("r2", "earn", "XXBT", "0.001", subtype = "reward").copy(time = secondAt),
                    ledger("a1", "transfer", "XETH", "0.1", subtype = "airdrop").copy(time = secondAt),
                    ledger("t1", "trade", "ZUSD", "9.00").copy(time = secondAt),
                    ledger("null", "staking", "XXBT", null, subtype = "reward").copy(time = secondAt),
                    ledger("bad", "staking", "XXBT", "bad", subtype = "reward").copy(time = secondAt),
                    ledger("flagged", "staking", "XXBT", "0.5", subtype = "reward", hasValidAmount = false)
                        .copy(time = secondAt),
                ),
            )

            rewards.map { it.asset } shouldBe listOf("XETH", "XXBT")
            rewards.first().points.single().cumulativeQuantity.shouldBeEqualComparingTo(BigDecimal("0.1"))
            rewards.last().points.last().cumulativeQuantity.shouldBeEqualComparingTo(BigDecimal("0.002"))
        }

        "history reporting stops when a continuation page is empty" {
            val scannedOffsets = mutableListOf<Int>()
            val firstReward = ledger("reward", "staking", "XXBT", "0.01", subtype = "reward")
            coEvery { historyService.queryLedgers(any()) } coAnswers {
                val offset = firstArg<HistoryEventQuery>().offset
                scannedOffsets += offset
                if (offset == 0) {
                    page(listOf(firstReward), hasMore = true, offset = offset)
                } else {
                    page(emptyList(), hasMore = true, offset = offset)
                }
            }

            val rewards = reporting.rewards(period)

            scannedOffsets shouldBe listOf(0, 1)
            rewards.quantitiesByAsset.getValue("XXBT").shouldBeEqualComparingTo(BigDecimal("0.01"))
            rewards.complete shouldBe true
        }
    }

    private fun <T> page(items: List<T>, hasMore: Boolean = false, offset: Int = 0): HistoryPage<T> =
        HistoryPage(items, items.size.toLong(), HISTORY_MAX_PAGE_LIMIT, offset, hasMore)

    private fun observation(
        id: String,
        observedAt: Instant,
        totalUsd: String?,
        assets: List<HistoryAssetValue> = emptyList(),
    ): HistoryObservation = HistoryObservation(
        observationId = id,
        cycleId = "cycle-$id",
        sourceScope = accountScope,
        observedAt = observedAt,
        balanceRequestStartedAt = observedAt,
        balanceResponseEndedAt = observedAt,
        priceRequestStartedAt = observedAt,
        priceResponseEndedAt = observedAt,
        status = if (totalUsd == null) HistoryObservationStatus.INCOMPLETE else HistoryObservationStatus.COMPLETE,
        totalUsd = totalUsd?.let { BigDecimal(it) },
        incompleteReasons = if (totalUsd == null) listOf("missing price") else emptyList(),
        assets = assets,
    )

    private fun asset(symbol: String, valueUsd: String?): HistoryAssetValue = HistoryAssetValue(
        symbol = symbol,
        status = HistoryAssetValueStatus.COMPLETE,
        quantity = BigDecimal("1"),
        valueUsd = valueUsd?.let { BigDecimal(it) },
    )

    private fun trade(
        id: String,
        cost: String?,
        fee: String?,
        quoteCurrency: String?,
        hasValidCost: Boolean = true,
        hasValidFee: Boolean = true,
    ): HistoryTrade = HistoryTrade(
        accountIdentityDigest = accountDigest,
        tradeId = id,
        orderTxid = null,
        postTxid = null,
        pair = null,
        baseAsset = null,
        quoteCurrency = quoteCurrency,
        time = Instant.parse("2026-05-10T00:00:00Z"),
        rawTime = null,
        type = null,
        orderType = null,
        price = null,
        cost = cost,
        fee = fee,
        volume = null,
        margin = null,
        misc = null,
        tradeSequenceId = null,
        maker = null,
        ledgerIds = emptyList(),
        rawRecordJson = null,
        hasValidTime = true,
        hasValidPrice = true,
        hasValidCost = hasValidCost,
        hasValidFee = hasValidFee,
        hasValidVolume = true,
        isValidRecord = true,
    )

    private fun ledger(
        id: String,
        type: String?,
        asset: String?,
        amount: String?,
        subtype: String? = null,
        hasValidAmount: Boolean = true,
    ): HistoryLedger = HistoryLedger(
        accountIdentityDigest = accountDigest,
        ledgerId = id,
        refid = null,
        time = Instant.parse("2026-05-10T00:00:00Z"),
        rawTime = null,
        type = type,
        subtype = subtype,
        assetClass = null,
        asset = asset,
        amount = amount,
        fee = null,
        balance = null,
        rawRecordJson = null,
        hasValidTime = true,
        hasValidAmount = hasValidAmount,
        hasValidFee = true,
        hasValidBalance = true,
        isValidRecord = true,
    )

    private fun cycle(id: String, occurredAt: Instant): HistoryCycleActivity = HistoryCycleActivity(
        cycleId = id,
        sourceScope = accountScope,
        occurredAt = occurredAt,
        dryRun = true,
        actions = emptyList(),
    )

    private fun activityPage(
        cycles: List<HistoryCycleActivity>,
        total: Long = cycles.size.toLong(),
    ): HistoryPage<HistoryCycleActivity> = HistoryPage(
        items = cycles,
        total = total,
        limit = cycles.size.coerceAtLeast(1),
        offset = 0,
        hasMore = false,
    )

    /** Durable progress that proves the reporting period was scanned for one feed. */
    private fun coveredProgress(feed: HistoryFeed): HistorySyncProgressSummary = HistorySyncProgressSummary(
        accountIdentityDigest = accountDigest,
        feed = feed,
        recentWindowStartSec = Instant.parse("2026-05-01T00:00:00Z").epochSecond,
        recentCompletedThroughSec = Instant.parse("2026-06-01T00:00:00Z").epochSecond,
        recentLastSuccessAt = fixedNow,
        backfillState = HistorySyncJobState.COMPLETE,
        backfillCompletedThroughSec = Instant.parse("2026-05-01T00:00:00Z").epochSecond,
        backfillCursor = null,
        repairLastRunAt = null,
        lastError = null,
        nextRetryAt = null,
    )
}
