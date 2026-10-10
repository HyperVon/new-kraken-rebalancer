package com.gemini.krakenbot.service.actual.benchmark

import com.gemini.krakenbot.service.actual.ActualAssetObservation
import com.gemini.krakenbot.service.actual.ActualAssetStatus
import com.gemini.krakenbot.service.actual.ActualObservation
import com.gemini.krakenbot.service.actual.ActualObservationStatus
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.comparables.shouldBeEqualComparingTo
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.math.BigDecimal
import java.time.Instant

class ProspectiveBenchmarkCalculatorTest :
    StringSpec({
        val t0 = Instant.parse("2026-03-01T10:00:00Z")
        val t1 = Instant.parse("2026-03-01T10:05:00Z")

        val baselineSegment = BenchmarkSegment(
            segmentId = "seg-1",
            baselineObservationId = "obs-0",
            accountIdentityDigest = "a".repeat(64),
            scopeFingerprint = "b".repeat(64),
            scopeSymbols = listOf("BTC", "ETH", "USD"),
            baselineAt = t0,
            initialHoldings = mapOf(
                "BTC" to BigDecimal("1.0"),
                "ETH" to BigDecimal("1.0"),
                "USD" to BigDecimal("10.00"),
            ),
            baselineMarks = mapOf(
                "BTC" to BigDecimal("100.00"),
                "ETH" to BigDecimal("150.00"),
                "USD" to BigDecimal("1.00"),
            ),
            baselineTotalUsd = BigDecimal("260.00"),
            status = BenchmarkSegmentStatus.TRACKING,
            lastVerifiedEventTime = t0,
            createdAt = t0,
        )

        fun createAsset(symbol: String, qty: BigDecimal?, price: BigDecimal?): ActualAssetObservation =
            ActualAssetObservation(
                symbol = symbol,
                status = ActualAssetStatus.COMPLETE,
                rawBalanceKeys = listOf(symbol),
                rawBalanceValues = mapOf(symbol to qty?.toPlainString()),
                quantity = qty,
                requestedPair = null,
                responsePair = null,
                candidateResponsePairs = emptyList(),
                candidateRawPrices = emptyMap(),
                rawPrice = price?.toPlainString(),
                priceUsd = price,
                valueUsd = if (qty != null && price != null) qty.multiply(price) else null,
                reason = null,
            )

        fun createObservation(
            id: String,
            time: Instant,
            holdings: Map<String, BigDecimal>,
            prices: Map<String, BigDecimal>,
            totalUsd: BigDecimal,
        ): ActualObservation = ActualObservation(
            observationId = id,
            accountIdentityDigest = "a".repeat(64),
            walletScope = "KRAKEN_DEFAULT_WALLET_CONFIGURED_ALLOCATIONS",
            scopeFingerprint = "b".repeat(64),
            scopeSymbols = listOf("BTC", "ETH", "USD"),
            observedAt = time,
            balanceRequestStartedAt = time.minusMillis(200),
            balanceResponseEndedAt = time.minusMillis(100),
            priceRequestStartedAt = time.minusMillis(100),
            priceResponseEndedAt = time,
            persistedAt = time,
            status = ActualObservationStatus.COMPLETE,
            totalUsd = totalUsd,
            incompleteReasons = emptyList(),
            assets = listOf("BTC", "ETH", "USD").map { symbol ->
                createAsset(symbol, holdings[symbol], prices[symbol])
            },
        )

        "Oracle A: baseline equality at T0 with zero difference" {
            val obs0 = createObservation(
                id = "obs-0",
                time = t0,
                holdings = mapOf(
                    "BTC" to BigDecimal("1.0"),
                    "ETH" to BigDecimal("1.0"),
                    "USD" to BigDecimal("10.00"),
                ),
                prices = mapOf(
                    "BTC" to BigDecimal("100.00"),
                    "ETH" to BigDecimal("150.00"),
                    "USD" to BigDecimal("1.00"),
                ),
                totalUsd = BigDecimal("260.00"),
            )

            val point = checkNotNull(ProspectiveBenchmarkCalculator.calculatePoint(baselineSegment, obs0))
            point.actualValueUsd shouldBeEqualComparingTo BigDecimal("260.00")
            point.holdValueUsd shouldBeEqualComparingTo BigDecimal("260.00")
            point.differenceUsd shouldBeEqualComparingTo BigDecimal("0.00")
            point.differencePercent shouldBeEqualComparingTo BigDecimal("0.00")
        }

        "Oracle B: passive static holding at T1 with updated contemporaneous prices" {
            // T1: P_BTC=120, P_ETH=180, P_USD=1.00 -> hold: 1*120 + 1*180 + 10 = 310.00
            val obs1 = createObservation(
                id = "obs-1",
                time = t1,
                holdings = mapOf(
                    "BTC" to BigDecimal("1.0"),
                    "ETH" to BigDecimal("1.0"),
                    "USD" to BigDecimal("10.00"),
                ),
                prices = mapOf(
                    "BTC" to BigDecimal("120.00"),
                    "ETH" to BigDecimal("180.00"),
                    "USD" to BigDecimal("1.00"),
                ),
                totalUsd = BigDecimal("310.00"),
            )

            val point = checkNotNull(ProspectiveBenchmarkCalculator.calculatePoint(baselineSegment, obs1))
            point.actualValueUsd shouldBeEqualComparingTo BigDecimal("310.00")
            point.holdValueUsd shouldBeEqualComparingTo BigDecimal("310.00")
            point.differenceUsd shouldBeEqualComparingTo BigDecimal("0.00")
            point.differencePercent shouldBeEqualComparingTo BigDecimal("0.00")
        }

        "Oracle C: bot rebalances actual portfolio while B&H holdings remain fixed at T0" {
            val t2 = Instant.parse("2026-03-01T10:10:00Z")
            val obs2 = createObservation(
                id = "obs-2",
                time = t2,
                holdings = mapOf(
                    "BTC" to BigDecimal("0.9"),
                    "ETH" to BigDecimal("1.0"),
                    "USD" to BigDecimal("22.00"),
                ),
                prices = mapOf(
                    "BTC" to BigDecimal("100.00"),
                    "ETH" to BigDecimal("180.00"),
                    "USD" to BigDecimal("1.00"),
                ),
                totalUsd = BigDecimal("292.00"),
            )

            val point = checkNotNull(ProspectiveBenchmarkCalculator.calculatePoint(baselineSegment, obs2))
            point.actualValueUsd shouldBeEqualComparingTo BigDecimal("292.00")
            point.holdValueUsd shouldBeEqualComparingTo BigDecimal("290.00")
            point.differenceUsd shouldBeEqualComparingTo BigDecimal("2.00")
            point.differencePercent shouldBeEqualComparingTo BigDecimal("0.69")
        }
    })
