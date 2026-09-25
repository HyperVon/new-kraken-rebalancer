package com.gemini.krakenbot.service.impl

import com.gemini.krakenbot.TestFixtures
import com.gemini.krakenbot.config.DatabaseConfig
import com.gemini.krakenbot.model.Asset
import com.gemini.krakenbot.model.ComparisonAvailability
import com.gemini.krakenbot.model.DepositStatusRecord
import com.gemini.krakenbot.model.FundingEvidence
import com.gemini.krakenbot.model.FundingProvenanceFailureReason
import com.gemini.krakenbot.model.InternalTransferRecord
import com.gemini.krakenbot.model.KrakenApiConstants
import com.gemini.krakenbot.model.KrakenAssetMetadata
import com.gemini.krakenbot.model.LedgerEvent
import com.gemini.krakenbot.model.PortfolioSnapshot
import com.gemini.krakenbot.model.WithdrawStatusRecord
import com.gemini.krakenbot.repository.FundingEvidenceIdentityRecord
import com.gemini.krakenbot.repository.FundingEvidenceIdentityStore
import com.gemini.krakenbot.repository.impl.SqliteFundingEvidenceIdentityStoreImpl
import com.gemini.krakenbot.service.FakeKrakenService
import com.gemini.krakenbot.service.KrakenService
import com.gemini.krakenbot.service.impl.history.HistoricalPriceProvider
import com.gemini.krakenbot.service.impl.history.RebalancerComparisonCalculator
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
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

        "explain stays null until preparation succeeds" {
            runTest {
                val krakenService = FakeKrakenService()
                val resolver = KrakenFundingProvenanceResolver(krakenService)
                val event = fundingEvent("explain", KrakenApiConstants.LEDGER_TYPE_DEPOSIT, "100.00")

                resolver.explain(event) shouldBe null

                val prepared = resolver.prepare(listOf(event))

                prepared.explain(event) shouldBe "no funding record matched"
            }
        }

        "reuses prepared funding evidence within the cache window" {
            runTest {
                val krakenService = FakeKrakenService()
                val resolver = KrakenFundingProvenanceResolver(krakenService)
                val event = fundingEvent("cache", KrakenApiConstants.LEDGER_TYPE_DEPOSIT, "100.00")

                resolver.prepare(listOf(event))
                resolver.prepare(listOf(event))

                krakenService.getDepositStatusCallCount shouldBe 1
            }
        }

        "funding range coerces the lower bound and rounds the upper bound" {
            val krakenService = FakeKrakenService()
            var capturedStart: Long? = null
            var capturedEnd: Long? = null
            krakenService.depositStatusSupplier = { startSec, endSec ->
                capturedStart = startSec
                capturedEnd = endSec
                emptyList()
            }
            val resolver = KrakenFundingProvenanceResolver(krakenService)
            val event = LedgerEvent(
                ledgerId = "L-RANGE",
                refid = "REF-RANGE",
                time = Instant.ofEpochSecond(10, 500_000_000),
                type = KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
                asset = "ETH",
                amount = BigDecimal("1.0"),
            )

            resolver.prepare(listOf(event))

            capturedStart shouldBe 0L
            capturedEnd shouldBe 191L
        }

        "preparation refetches when the funding families grow" {
            val krakenService = FakeKrakenService()
            val resolver = KrakenFundingProvenanceResolver(krakenService)
            val deposit = LedgerEvent(
                ledgerId = "L-FAM-D",
                refid = "FAM-D",
                time = now,
                type = KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
                asset = "ETH",
                amount = BigDecimal("1.0"),
            )
            val withdrawal = LedgerEvent(
                ledgerId = "L-FAM-W",
                refid = "FAM-W",
                time = now,
                type = KrakenApiConstants.LEDGER_TYPE_WITHDRAWAL,
                asset = "ETH",
                amount = BigDecimal("-1.0"),
            )

            resolver.prepare(listOf(deposit))
            krakenService.getWithdrawStatusCallCount shouldBe 0

            resolver.prepare(listOf(deposit, withdrawal))
            krakenService.getWithdrawStatusCallCount shouldBe 1
        }

        "a funding-source failure leaves provenance unresolved" {
            runTest {
                val krakenService = FakeKrakenService()
                krakenService.depositStatusSupplier = { _, _ -> error("funding API unavailable") }
                val resolver = KrakenFundingProvenanceResolver(krakenService)
                val event = fundingEvent("failed", KrakenApiConstants.LEDGER_TYPE_DEPOSIT, "100.00")

                val prepared = resolver.prepare(listOf(event))

                prepared.resolve(event) shouldBe FundingEvidence.UNRESOLVED
                prepared.preparationFailure?.reason shouldBe FundingProvenanceFailureReason.REQUEST_FAILED

                val nullMessageService = FakeKrakenService()
                nullMessageService.depositStatusSupplier = { _, _ -> throw RuntimeException() }
                val nullMessagePrepared = KrakenFundingProvenanceResolver(nullMessageService)
                    .prepare(listOf(event))

                nullMessagePrepared.preparationFailure?.message shouldContain "RuntimeException"
            }
        }

        "permission denial preserves an actionable funding-provenance failure" {
            runTest {
                val backingService = FakeKrakenService()
                val deniedService = object : KrakenService by backingService {
                    override suspend fun <T> withStableBackend(block: suspend (KrakenService) -> T): T = block(this)

                    override suspend fun getDepositStatus(startSec: Long?, endSec: Long?): List<DepositStatusRecord> =
                        throw KrakenApiPermissionDeniedException(
                            endpoint = KrakenApiConstants.PATH_DEPOSIT_STATUS,
                            message = "EGeneral:Permission denied",
                        )
                }
                val resolver = KrakenFundingProvenanceResolver(deniedService)
                val event = fundingEvent("permission-denied", KrakenApiConstants.LEDGER_TYPE_DEPOSIT, "100.00")

                val prepared = resolver.prepare(listOf(event))

                prepared.resolve(event) shouldBe FundingEvidence.UNRESOLVED
                prepared.preparationFailure?.reason shouldBe FundingProvenanceFailureReason.PERMISSION_DENIED
                prepared.preparationFailure?.message?.contains("Funds: Query") shouldBe true
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

                resolver.evidenceFingerprint shouldBe "kraken-funding-unprepared"
                resolver.prepare(listOf(event))
                resolver.evidenceFingerprint shouldNotBe null
                clock = now.plusSeconds(60)
                resolver.evidenceFingerprint shouldBe null
                resolver.prepare(listOf(event))

                krakenService.getDepositStatusCallCount shouldBe 2
            }
        }

        "durable identity survives TTL expiry and restarts without refetching funding status" {
            runTest {
                var clock = now
                val database = DatabaseConfig.init(":memory:")
                val store = SqliteFundingEvidenceIdentityStoreImpl(database)
                val krakenService = FakeKrakenService()
                val event = fundingEvent("durable", KrakenApiConstants.LEDGER_TYPE_DEPOSIT, "100.00")
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
                val resolver = KrakenFundingProvenanceResolver(
                    krakenService,
                    nowProvider = { clock },
                    durableIdentityStore = store,
                )
                val durableFingerprint = resolver.prepare(listOf(event)).evidenceFingerprint
                durableFingerprint shouldNotBe null
                resolver.evidenceFingerprint shouldBe durableFingerprint

                // Long after the in-memory TTL the durable record still certifies cache identity.
                clock = now.plusSeconds(600)
                resolver.evidenceFingerprint shouldBe durableFingerprint

                // A restarted resolver (never prepared) reads the same durable identity and
                // makes no funding status call merely to validate reuse.
                val restartedKraken = FakeKrakenService()
                val restarted = KrakenFundingProvenanceResolver(
                    restartedKraken,
                    nowProvider = { clock },
                    durableIdentityStore = store,
                )
                restarted.evidenceFingerprint shouldBe durableFingerprint
                restartedKraken.getDepositStatusCallCount shouldBe 0
                restartedKraken.getInternalTransfersCallCount shouldBe 0
            }
        }

        "changing the durable funding evidence changes the durable fingerprint" {
            runTest {
                val database = DatabaseConfig.init(":memory:")
                val store = SqliteFundingEvidenceIdentityStoreImpl(database)
                val event = fundingEvent("mutable", KrakenApiConstants.LEDGER_TYPE_DEPOSIT, "100.00")
                var method = "Wire"
                fun kraken() = FakeKrakenService().apply {
                    depositStatusSupplier = { _, _ ->
                        listOf(
                            DepositStatusRecord(
                                refid = event.refid!!,
                                asset = "USD",
                                amount = BigDecimal("100.00"),
                                time = event.time,
                                status = "Success",
                                method = method,
                            ),
                        )
                    }
                }
                val first =
                    KrakenFundingProvenanceResolver(kraken(), durableIdentityStore = store)
                val firstFingerprint = first.prepare(listOf(event)).evidenceFingerprint
                firstFingerprint shouldNotBe null

                method = "Swift"
                val second =
                    KrakenFundingProvenanceResolver(kraken(), durableIdentityStore = store)
                val secondFingerprint = second.prepare(listOf(event)).evidenceFingerprint
                secondFingerprint shouldNotBe firstFingerprint
                second.evidenceFingerprint shouldBe secondFingerprint

                // A resolver that never prepares in this process still observes the change.
                KrakenFundingProvenanceResolver(
                    FakeKrakenService(),
                    durableIdentityStore = store,
                ).evidenceFingerprint shouldBe secondFingerprint
            }
        }

        "preparation failure reports on the instance and leaves the durable identity intact" {
            runTest {
                val database = DatabaseConfig.init(":memory:")
                val store = SqliteFundingEvidenceIdentityStoreImpl(database)
                val event = fundingEvent("failure", KrakenApiConstants.LEDGER_TYPE_DEPOSIT, "100.00")
                val healthy = FakeKrakenService().apply {
                    depositStatusSupplier = { _, _ ->
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
                }
                val healthyResolver = KrakenFundingProvenanceResolver(healthy, durableIdentityStore = store)
                val fingerprint = healthyResolver.prepare(listOf(event)).evidenceFingerprint
                fingerprint shouldNotBe null

                var fail = true
                val failing = FakeKrakenService().apply {
                    depositStatusSupplier = { _, _ ->
                        if (fail) error("kraken unavailable")
                        emptyList()
                    }
                }
                val failingResolver = KrakenFundingProvenanceResolver(failing, durableIdentityStore = store)
                val degraded = failingResolver.prepare(listOf(event))
                degraded.preparationFailure shouldNotBe null
                failingResolver.preparationFailure shouldNotBe null
                // The durable identity of the last healthy batch still certifies cache reuse;
                // the degraded calculation itself must never be persisted under it.
                failingResolver.evidenceFingerprint shouldBe fingerprint
                fail = false
                failingResolver.prepare(listOf(event))
                failingResolver.preparationFailure shouldBe null
            }
        }

        "preparing a funding-free window clears an earlier preparation failure" {
            runTest {
                val event = fundingEvent("sticky", KrakenApiConstants.LEDGER_TYPE_DEPOSIT, "100.00")
                val failing = FakeKrakenService().apply {
                    depositStatusSupplier = { _, _ -> error("kraken unavailable") }
                }
                val resolver = KrakenFundingProvenanceResolver(failing)
                resolver.prepare(listOf(event))
                resolver.preparationFailure shouldNotBe null

                // A later window with no funding rows needs no provenance evidence, so the
                // stale failure must not keep degrading (or uncaching) its healthy result.
                resolver.prepare(emptyList())
                resolver.preparationFailure shouldBe null
            }
        }

        "an unverifiable durable save is cleared so later requests miss the cache" {
            runTest {
                var clock = now
                val backing = mutableMapOf("fp" to "previous-batch-fingerprint")
                val flakyStore = object : FundingEvidenceIdentityStore {
                    override fun load(): FundingEvidenceIdentityRecord? = backing["fp"]?.let {
                        FundingEvidenceIdentityRecord(
                            fingerprint = it,
                            identity = backing["id"].orEmpty(),
                            updatedAtEpochSeconds = 0L,
                        )
                    }

                    override fun save(record: FundingEvidenceIdentityRecord) {
                        error("disk full")
                    }

                    override fun clear() {
                        backing.clear()
                    }
                }
                val event = fundingEvent("flaky", KrakenApiConstants.LEDGER_TYPE_DEPOSIT, "100.00")
                val krakenService = FakeKrakenService().apply {
                    depositStatusSupplier = { _, _ ->
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
                }
                val resolver = KrakenFundingProvenanceResolver(
                    krakenService,
                    nowProvider = { clock },
                    durableIdentityStore = flakyStore,
                )
                resolver.prepare(listOf(event)).evidenceFingerprint shouldNotBe null

                // The save could not be verified, so the durable record must not keep
                // certifying the previous batch's evidence: after the TTL the fingerprint is
                // gone and the next comparison replays authoritatively.
                clock = now.plusSeconds(600)
                resolver.evidenceFingerprint shouldBe null
                backing shouldBe emptyMap()
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
            resolver.explain(fundingEvent("unprepared", KrakenApiConstants.LEDGER_TYPE_DEPOSIT, "100.00")) shouldBe null
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
                    assetMetadata = listOf(
                        KrakenAssetMetadata("BTC", "currency"),
                        KrakenAssetMetadata("USD", "currency"),
                    ),
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

        "isCardFunding delegates to prepared resolver and returns false when unprepared" {
            runTest {
                val krakenService = FakeKrakenService()
                val resolver = KrakenFundingProvenanceResolver(krakenService)
                val deposit = fundingEvent("deposit", KrakenApiConstants.LEDGER_TYPE_DEPOSIT, "100.00")

                // Unprepared resolver returns false
                resolver.isCardFunding(deposit) shouldBe false

                krakenService.depositStatusSupplier = { _, _ ->
                    listOf(
                        DepositStatusRecord(
                            refid = "deposit-ref",
                            asset = "USD",
                            amount = BigDecimal("100.00"),
                            time = now,
                            status = "Success",
                            method = "Visa",
                        ),
                    )
                }

                val prepared = resolver.prepare(listOf(deposit))
                prepared.isCardFunding(deposit) shouldBe true
                resolver.isCardFunding(deposit) shouldBe true
                resolver.explain(deposit)

                val nonCardEvent = fundingEvent("wire-dep", KrakenApiConstants.LEDGER_TYPE_DEPOSIT, "100.00")
                prepared.isCardFunding(nonCardEvent) shouldBe false
                prepared.isCardFunding(
                    deposit.copy(type = KrakenApiConstants.LEDGER_TYPE_WITHDRAWAL),
                ) shouldBe false
                prepared.isCardFunding(deposit.copy(amount = BigDecimal("101.00"))) shouldBe false
                prepared.isCardFunding(deposit.copy(refid = null)) shouldBe true
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
