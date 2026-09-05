package com.gemini.krakenbot.service.impl

import com.gemini.krakenbot.TestFixtures
import com.gemini.krakenbot.model.Asset
import com.gemini.krakenbot.model.ComparisonAvailability
import com.gemini.krakenbot.model.DepositStatusRecord
import com.gemini.krakenbot.model.FundingEvidence
import com.gemini.krakenbot.model.InternalTransferRecord
import com.gemini.krakenbot.model.KrakenApiConstants
import com.gemini.krakenbot.model.LedgerEvent
import com.gemini.krakenbot.model.PortfolioSnapshot
import com.gemini.krakenbot.model.WithdrawStatusRecord
import com.gemini.krakenbot.service.FakeKrakenService
import com.gemini.krakenbot.service.KrakenService
import com.gemini.krakenbot.service.impl.history.HistoricalPriceProvider
import com.gemini.krakenbot.service.impl.history.RebalancerComparisonCalculator
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import java.math.BigDecimal
import java.time.Instant

class KrakenFundingProvenanceResolverTest : StringSpec() {

    override fun isolationMode() = IsolationMode.InstancePerTest

    private val now = Instant.parse("2026-08-01T12:00:00Z")

    init {
        "does not fetch status families that are absent from the funding batch" {
            runTest {
                val krakenService = FakeKrakenService()
                val resolver = KrakenFundingProvenanceResolver(krakenService)
                val transfer = fundingEvent("transfer-only", KrakenApiConstants.LEDGER_TYPE_TRANSFER, "50.00")

                resolver.prepare(emptyList()) shouldBe resolver
                val prepared = resolver.prepare(listOf(transfer))

                prepared.resolve(transfer) shouldBe FundingEvidence.UNRESOLVED
                krakenService.getDepositStatusCallCount shouldBe 0
                krakenService.getWithdrawStatusCallCount shouldBe 0
                krakenService.getInternalTransfersCallCount shouldBe 1
            }
        }

        "prepares each required funding family once and correlates the batch in memory" {
            runTest {
                val krakenService = FakeKrakenService()
                val deposit = fundingEvent("deposit", KrakenApiConstants.LEDGER_TYPE_DEPOSIT, "100.00")
                val withdrawal = fundingEvent("withdrawal", KrakenApiConstants.LEDGER_TYPE_WITHDRAWAL, "-25.00")
                val transfer = fundingEvent("transfer", KrakenApiConstants.LEDGER_TYPE_TRANSFER, "50.00")
                krakenService.depositStatusSupplier = { _, _ ->
                    listOf(
                        DepositStatusRecord(
                            refid = "deposit-ref",
                            asset = "USD",
                            amount = BigDecimal("100.00"),
                            time = now,
                            status = "Success",
                            method = "Wire",
                        ),
                    )
                }
                krakenService.withdrawStatusSupplier = { _, _ ->
                    listOf(
                        WithdrawStatusRecord(
                            refid = "withdrawal-ref",
                            asset = "USD",
                            amount = BigDecimal("25.00"),
                            time = now,
                            status = "Settled",
                            method = "Wire",
                        ),
                    )
                }
                krakenService.internalTransfersSupplier = { _, _ ->
                    listOf(
                        InternalTransferRecord(
                            refid = "transfer-ref",
                            asset = "USD",
                            amount = BigDecimal("50.00"),
                            time = now,
                            ledgerType = KrakenApiConstants.LEDGER_TYPE_TRANSFER,
                        ),
                    )
                }

                val resolver = KrakenFundingProvenanceResolver(krakenService)
                val prepared = resolver.prepare(listOf(deposit, withdrawal, transfer))

                prepared.resolve(deposit) shouldBe FundingEvidence.EXTERNAL
                prepared.resolve(withdrawal) shouldBe FundingEvidence.EXTERNAL
                prepared.resolve(transfer) shouldBe FundingEvidence.INTERNAL

                // A second query in the same range uses the prepared evidence
                // instead of making one request per ledger row or per call.
                resolver.prepare(listOf(deposit, withdrawal, transfer))
                krakenService.getDepositStatusCallCount shouldBe 1
                krakenService.getWithdrawStatusCallCount shouldBe 1
                krakenService.getInternalTransfersCallCount shouldBe 1
            }
        }

        "a funding-source failure leaves provenance unresolved" {
            runTest {
                val krakenService = FakeKrakenService()
                krakenService.depositStatusSupplier = { _, _ -> error("funding API unavailable") }
                val resolver = KrakenFundingProvenanceResolver(krakenService)
                val event = fundingEvent("failed", KrakenApiConstants.LEDGER_TYPE_DEPOSIT, "100.00")

                val prepared = resolver.prepare(listOf(event))

                prepared.resolve(event) shouldBe FundingEvidence.UNRESOLVED
            }
        }

        "refreshes a cached funding batch after its short cache window" {
            runTest {
                var clock = now
                val krakenService = FakeKrakenService()
                val event = fundingEvent("ttl", KrakenApiConstants.LEDGER_TYPE_DEPOSIT, "100.00")
                krakenService.depositStatusSupplier = { _, _ ->
                    listOf(
                        DepositStatusRecord(
                            refid = event.refid!!,
                            asset = "USD",
                            amount = BigDecimal("100.00"),
                            time = event.time,
                            status = "Success",
                            method = "Wire",
                        ),
                    )
                }
                val resolver = KrakenFundingProvenanceResolver(krakenService, nowProvider = { clock })

                resolver.prepare(listOf(event))
                clock = now.plusSeconds(60)
                resolver.prepare(listOf(event))

                krakenService.getDepositStatusCallCount shouldBe 2
            }
        }

        "does not reuse evidence for a different ledger batch or account scope" {
            runTest {
                var scope = "account-a"
                val krakenService = FakeKrakenService()
                krakenService.fundingEvidenceScopeSupplier = { scope }
                val first = fundingEvent("cache-first", KrakenApiConstants.LEDGER_TYPE_DEPOSIT, "100.00")
                val second = fundingEvent("cache-second", KrakenApiConstants.LEDGER_TYPE_DEPOSIT, "100.00")
                val resolver = KrakenFundingProvenanceResolver(krakenService, nowProvider = { now })

                resolver.prepare(listOf(first))
                resolver.prepare(listOf(second))
                scope = "account-b"
                resolver.prepare(listOf(second))

                krakenService.getDepositStatusCallCount shouldBe 3
            }
        }

        "funding range rounds its upper query bound outward for fractional event times" {
            runTest {
                val krakenService = FakeKrakenService()
                val event = fundingEvent("fractional", KrakenApiConstants.LEDGER_TYPE_DEPOSIT, "100.00")
                    .copy(time = now.plusMillis(999))
                var requestedEnd: Long? = null
                krakenService.depositStatusSupplier = { _, end ->
                    requestedEnd = end
                    listOf(
                        DepositStatusRecord(
                            refid = event.refid!!,
                            asset = "USD",
                            amount = BigDecimal("100.00"),
                            time = event.time,
                            status = "Success",
                            method = "Wire",
                        ),
                    )
                }

                KrakenFundingProvenanceResolver(krakenService).prepare(listOf(event))

                requestedEnd shouldBe event.time.plusSeconds(180).epochSecond + 1
            }
        }

        "funding range clamps pre-epoch lower bounds and preserves an inclusive fractional upper bound" {
            runTest {
                val krakenService = FakeKrakenService()
                val event = fundingEvent("near-epoch", KrakenApiConstants.LEDGER_TYPE_DEPOSIT, "100.00")
                    .copy(time = Instant.ofEpochSecond(10, 1))
                var requestedStart: Long? = null
                var requestedEnd: Long? = null
                krakenService.depositStatusSupplier = { start, end ->
                    requestedStart = start
                    requestedEnd = end
                    emptyList()
                }

                KrakenFundingProvenanceResolver(krakenService).prepare(listOf(event))

                requestedStart shouldBe 0L
                requestedEnd shouldBe 191L
            }
        }

        "refreshes cached evidence when the requested range or family expands" {
            runTest {
                val krakenService = FakeKrakenService()
                val resolver = KrakenFundingProvenanceResolver(krakenService)
                val deposit = fundingEvent("range-deposit", KrakenApiConstants.LEDGER_TYPE_DEPOSIT, "100.00")
                val laterDeposit = deposit.copy(
                    ledgerId = "range-later",
                    refid = "range-later-ref",
                    time = now.plusSeconds(1000),
                )
                val earlierDeposit = deposit.copy(
                    ledgerId = "range-earlier",
                    refid = "range-earlier-ref",
                    time = now.minusSeconds(1000),
                )
                val transfer = fundingEvent("range-transfer", KrakenApiConstants.LEDGER_TYPE_TRANSFER, "50.00")

                resolver.prepare(listOf(deposit))
                resolver.prepare(listOf(laterDeposit))
                resolver.prepare(listOf(earlierDeposit))
                resolver.prepare(listOf(transfer))

                krakenService.getDepositStatusCallCount shouldBe 3
                krakenService.getInternalTransfersCallCount shouldBe 4
            }
        }

        "does not reuse a prepared batch after stable backend selection changes" {
            runTest {
                val firstBackend = FakeKrakenService()
                val secondBackend = FakeKrakenService()
                var selectedBackend: KrakenService = firstBackend
                val switchingService = object : KrakenService by firstBackend {
                    override suspend fun <T> withStableBackend(block: suspend (KrakenService) -> T): T =
                        block(selectedBackend)
                }
                val resolver = KrakenFundingProvenanceResolver(switchingService)
                val event = fundingEvent("backend-switch", KrakenApiConstants.LEDGER_TYPE_DEPOSIT, "100.00")

                resolver.prepare(listOf(event))
                selectedBackend = secondBackend
                resolver.prepare(listOf(event))

                firstBackend.getDepositStatusCallCount shouldBe 1
                secondBackend.getDepositStatusCallCount shouldBe 1
            }
        }

        "concurrent prepares reuse the batch published while the mutex was held" {
            runTest {
                val backingService = FakeKrakenService()
                val fetchStarted = CompletableDeferred<Unit>()
                val releaseFetch = CompletableDeferred<Unit>()
                var depositFetches = 0
                val blockingService = object : KrakenService by backingService {
                    override suspend fun <T> withStableBackend(block: suspend (KrakenService) -> T): T = block(this)

                    override suspend fun getDepositStatus(startSec: Long?, endSec: Long?): List<DepositStatusRecord> {
                        depositFetches++
                        fetchStarted.complete(Unit)
                        releaseFetch.await()
                        return emptyList()
                    }
                }
                val resolver = KrakenFundingProvenanceResolver(blockingService)
                val event = fundingEvent("concurrent", KrakenApiConstants.LEDGER_TYPE_DEPOSIT, "100.00")

                val first = async { resolver.prepare(listOf(event)) }
                fetchStarted.await()
                val second = async { resolver.prepare(listOf(event)) }
                releaseFetch.complete(Unit)

                first.await().resolve(event) shouldBe FundingEvidence.UNRESOLVED
                second.await().resolve(event) shouldBe FundingEvidence.UNRESOLVED
                depositFetches shouldBe 1
            }
        }

        "propagates cancellation from an authoritative funding fetch" {
            runTest {
                val backingService = FakeKrakenService()
                val cancellingService = object : KrakenService by backingService {
                    override suspend fun <T> withStableBackend(block: suspend (KrakenService) -> T): T = block(this)

                    override suspend fun getDepositStatus(startSec: Long?, endSec: Long?): List<DepositStatusRecord> =
                        throw kotlinx.coroutines.CancellationException("cancelled")
                }
                val resolver = KrakenFundingProvenanceResolver(cancellingService)
                val event = fundingEvent("cancelled", KrakenApiConstants.LEDGER_TYPE_DEPOSIT, "100.00")

                io.kotest.assertions.throwables.shouldThrow<kotlinx.coroutines.CancellationException> {
                    resolver.prepare(listOf(event))
                }
            }
        }

        "the unprepared production resolver remains fail closed" {
            val resolver = KrakenFundingProvenanceResolver(FakeKrakenService())
            resolver.resolve(fundingEvent("unprepared", KrakenApiConstants.LEDGER_TYPE_DEPOSIT, "100.00")) shouldBe
                FundingEvidence.UNRESOLVED
        }

        "prepared production provenance drives the comparison calculator" {
            runTest {
                val krakenService = FakeKrakenService()
                val event = fundingEvent("comparison-deposit", KrakenApiConstants.LEDGER_TYPE_DEPOSIT, "10000.00")
                    .copy(time = now.plusSeconds(1800))
                krakenService.depositStatusSupplier = { _, _ ->
                    listOf(
                        DepositStatusRecord(
                            refid = event.refid!!,
                            asset = "USD",
                            amount = BigDecimal("10000.00"),
                            time = event.time,
                            status = "Success",
                            method = "Wire",
                        ),
                    )
                }
                val resolver = KrakenFundingProvenanceResolver(krakenService)
                val prepared = resolver.prepare(listOf(event))

                val baselineAssets = mapOf(
                    "BTC" to TestFixtures.assetSnapshot(
                        symbol = "BTC",
                        balance = BigDecimal.ONE,
                        price = BigDecimal("50000.00"),
                        valueUSD = BigDecimal("50000.00"),
                        targetPercent = BigDecimal("50.0"),
                    ),
                    Asset.USD to TestFixtures.assetSnapshot(
                        symbol = Asset.USD,
                        balance = BigDecimal("50000.00"),
                        price = BigDecimal.ONE,
                        valueUSD = BigDecimal("50000.00"),
                        targetPercent = BigDecimal("50.0"),
                    ),
                )
                val baseline = snapshot(now, BigDecimal("100000.00"), baselineAssets)
                val latest = snapshot(
                    now.plusSeconds(3600),
                    BigDecimal("110000.00"),
                    baselineAssets + (
                        Asset.USD to TestFixtures.assetSnapshot(
                            symbol = Asset.USD,
                            balance = BigDecimal("60000.00"),
                            price = BigDecimal.ONE,
                            valueUSD = BigDecimal("60000.00"),
                            targetPercent = BigDecimal("50.0"),
                        )
                        ),
                )

                val comparison = RebalancerComparisonCalculator.calculate(
                    snapshots = listOf(baseline, latest),
                    trades = emptyList(),
                    rewards = listOf(event),
                    priceProvider = HistoricalPriceProvider { symbol, _ ->
                        if (symbol == Asset.USD) BigDecimal.ONE else BigDecimal("50000.00")
                    },
                    provenanceResolver = prepared,
                )

                comparison.availability shouldBe ComparisonAvailability.AVAILABLE
                comparison.points.last().buyAndHoldValueUSD shouldBe BigDecimal("110000.00")
            }
        }
    }

    private fun fundingEvent(id: String, type: String, amount: String): LedgerEvent = LedgerEvent(
        ledgerId = id,
        refid = "$id-ref",
        time = now,
        type = type,
        asset = "USD",
        amount = BigDecimal(amount),
    )

    private fun snapshot(
        timestamp: Instant,
        totalValueUSD: BigDecimal,
        assets: Map<String, PortfolioSnapshot.AssetSnapshot>,
    ): PortfolioSnapshot = PortfolioSnapshot(
        timestamp = timestamp,
        totalValueUSD = totalValueUSD,
        assets = assets,
        actions = emptyList(),
        drawdownPercent = BigDecimal.ZERO,
        fiatDeploymentPercent = BigDecimal.ZERO,
        effectiveUsdTargetPercent = BigDecimal.ZERO,
        balancesObservedAt = timestamp,
    )
}
