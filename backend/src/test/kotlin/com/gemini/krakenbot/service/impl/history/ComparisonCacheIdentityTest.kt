package com.gemini.krakenbot.service.impl.history

import com.gemini.krakenbot.model.BenchmarkMethod
import com.gemini.krakenbot.model.PortfolioSnapshot
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.mockk
import java.math.BigDecimal
import java.time.Instant

/**
 * Cache-identity regression.
 *
 * A comparison cache row holds one benchmark method's result, so the fingerprint that keys it must
 * distinguish methods. A regression that drops the method from either the read or the write path
 * does not fail loudly: the second request simply matches the first request's row and is served the
 * wrong benchmark. These tests pin that invariant directly.
 */
class ComparisonCacheIdentityTest : StringSpec() {

    private val service = TradeHistoryQueryService(
        repository = mockk(relaxed = true),
        portfolioStatsRepository = mockk(relaxed = true),
        ledgerRepository = mockk(relaxed = true),
        comparisonCacheRepository = mockk(relaxed = true),
        nowProvider = { Instant.EPOCH },
    )

    private val stableThrough: Instant = Instant.parse("2025-12-06T00:00:00Z")

    private fun snapshot() = PortfolioSnapshot(
        timestamp = stableThrough,
        totalValueUSD = BigDecimal("1000.00"),
        assets = mapOf(
            "USD" to PortfolioSnapshot.AssetSnapshot(
                symbol = "USD",
                balance = BigDecimal("1000.00"),
                price = BigDecimal.ONE,
                valueUSD = BigDecimal("1000.00"),
                targetPercent = BigDecimal("100"),
                currentPercent = BigDecimal("100"),
                deviationPercent = BigDecimal.ZERO,
                deviationUSD = BigDecimal.ZERO,
            ),
        ),
        actions = emptyList(),
        drawdownPercent = BigDecimal.ZERO,
        fiatDeploymentPercent = BigDecimal.ZERO,
        effectiveUsdTargetPercent = BigDecimal("100"),
    )

    init {
        "benchmark method is part of the comparison cache identity" {
            val fixed = service.comparisonCacheFingerprintForTest(
                benchmarkMethod = BenchmarkMethod.FIXED_INCEPTION_HOLD,
                stableThrough = stableThrough,
                inceptionResolution = null,
                snapshots = listOf(snapshot()),
                assetMetadataDigest = "digest",
            )
            val inferred = service.comparisonCacheFingerprintForTest(
                benchmarkMethod = BenchmarkMethod.INFERRED_CONFIGURATION_MATCHED_HOLD,
                stableThrough = stableThrough,
                inceptionResolution = null,
                snapshots = listOf(snapshot()),
                assetMetadataDigest = "digest",
            )

            // An unavailable fingerprint can never satisfy a lookup, so the contract is only
            // meaningful when one was produced; a null here means the fixture is not exercising
            // the identity at all.
            (fixed != null) shouldBe true
            (inferred != null) shouldBe true
            fixed shouldNotBe inferred
        }
    }
}
