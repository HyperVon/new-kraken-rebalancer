package com.gemini.krakenbot

import com.gemini.krakenbot.config.Allocation
import com.gemini.krakenbot.config.AppConfig
import com.gemini.krakenbot.config.KrakenCredentials
import com.gemini.krakenbot.config.Settings
import com.gemini.krakenbot.model.Asset
import com.gemini.krakenbot.model.PortfolioSnapshot
import com.gemini.krakenbot.test.TestConstants
import java.math.BigDecimal
import java.time.Instant

fun Map<String, Double>.toBigDecimalMap(): Map<String, BigDecimal> = this.mapValues { BigDecimal.valueOf(it.value) }

/**
 * Centralizes repeated string literals and default config objects used across test fixtures
 * so that no test credential, token string, or boilerplate config is hardcoded more than once.
 */
object TestFixtures {

    /** Base64 of `test-private-key` — Kraken private keys must decode as Base64. */
    const val KRAKEN_TEST_API_SECRET = "dGVzdC1wcml2YXRlLWtleQ=="

    /** Trading side constants (lowercase matching OrderSide.apiValue, uppercase for display/models). */
    const val BUY = "buy"
    const val SELL = "sell"

    /** Order type constants. */
    const val MARKET = "market"

    /** Asset pair / symbol constants. */
    const val BTCUSD = "BTCUSD"
    const val XXBTZUSD = "XXBTZUSD"
    const val XBTUSD = "XBTUSD"
    const val ETHUSD = "ETHUSD"
    const val USD = "USD"
    const val XETHZUSD = "XETHZUSD"
    const val ADAEUR = "ADAEUR"
    const val ETH = "ETH"
    const val BTC_LOWER = "btc"
    const val ETH_LOWER = "eth"

    /** Synthetic asset symbols used in PortfolioManagerComprehensiveTest. */
    const val AUSD = "AUSD"
    const val BUSD = "BUSD"
    const val A = "A"
    const val B = "B"

    /** HTTP content type constants. */
    const val APPLICATION_JSON = "application/json"

    /** Generic credential / key constants. */
    const val SECRET = "secret"

    @Suppress("unused")
    const val INVALID = "invalid"
    const val GZIP = "gzip"
    const val TEST = "test"
    const val HELLO = "hello"

    /**
     * Note the flag pair: `simulation = true` picks the offline emulator backend while `dryRun = false`
     * still lets that backend "place" orders. Specs asserting `[DRY RUN]` behavior must override it.
     */
    val DEFAULT_TEST_SETTINGS = Settings(
        loopDelaySeconds = 60,
        deviationTriggerPercent = 2.0,
        minimumOrderSizeUSD = 5.0,
        dryRun = false,
        simulation = true,
    )

    val DEFAULT_TEST_ALLOCATIONS = listOf(
        Allocation(Asset.BTC, 50.0),
        Allocation(Asset.ETH, 30.0),
        Allocation(Asset.USD, 20.0),
    )

    val DEFAULT_TEST_CONFIG = AppConfig(
        kraken = KrakenCredentials(TestConstants.API_KEY, TestConstants.API_SECRET),
        settings = DEFAULT_TEST_SETTINGS,
        allocations = DEFAULT_TEST_ALLOCATIONS,
    )

    fun settings(
        dryRun: Boolean = true,
        minimumOrderSizeUSD: Double = 5.0,
        simulation: Boolean = !dryRun,
        loopDelaySeconds: Long = 0L,
        deviationTriggerPercent: Double = 2.0,
    ): Settings = Settings(
        loopDelaySeconds = loopDelaySeconds,
        deviationTriggerPercent = deviationTriggerPercent,
        minimumOrderSizeUSD = minimumOrderSizeUSD,
        dryRun = dryRun,
        simulation = simulation,
    )

    fun config(
        settings: Settings = settings(),
        allocations: List<Allocation> = emptyList(),
        kraken: KrakenCredentials = KrakenCredentials("k", "s"),
    ): AppConfig = AppConfig(kraken = kraken, settings = settings, allocations = allocations)

    fun emptySnapshot(timestamp: Instant, totalValueUSD: BigDecimal): PortfolioSnapshot = PortfolioSnapshot(
        timestamp = timestamp,
        totalValueUSD = totalValueUSD,
        assets = emptyMap(),
        actions = emptyList(),
        effectiveUsdTargetPercent = BigDecimal.ZERO,
    )

    fun assetSnapshot(
        symbol: String,
        balance: BigDecimal,
        price: BigDecimal,
        valueUSD: BigDecimal,
        targetPercent: BigDecimal,
        currentPercent: BigDecimal = targetPercent,
        deviationPercent: BigDecimal = BigDecimal.ZERO,
        deviationUSD: BigDecimal = BigDecimal.ZERO,
    ): PortfolioSnapshot.AssetSnapshot = PortfolioSnapshot.AssetSnapshot(
        symbol = Asset(symbol),
        balance = balance,
        price = price,
        valueUSD = valueUSD,
        targetPercent = targetPercent,
        currentPercent = currentPercent,
        deviationPercent = deviationPercent,
        deviationUSD = deviationUSD,
    )
}
