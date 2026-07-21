package com.gemini.krakenbot

import com.gemini.krakenbot.config.Allocation
import com.gemini.krakenbot.config.AppConfig
import com.gemini.krakenbot.config.KrakenCredentials
import com.gemini.krakenbot.config.Settings
import com.gemini.krakenbot.model.Asset
import com.gemini.krakenbot.test.TestConstants
import java.math.BigDecimal

fun Map<String, Double>.toBigDecimalMap(): Map<String, BigDecimal> = this.mapValues { BigDecimal.valueOf(it.value) }

/**
 * Centralizes repeated string literals and default config objects used across test fixtures
 * so that no test credential, token string, or boilerplate config is hardcoded more than once.
 */
object TestFixtures {

    /** Longer-format API key used in integration/E2E/fuzz tests. */
    const val TEST_API_KEY = "apiKey"

    /** Server-side credentials stub used in DashboardController integration tests. */
    const val TEST_SERVER_API_KEY = "server-key"
    const val TEST_SERVER_API_SECRET = "server-secret"

    val DEFAULT_TEST_SETTINGS = Settings(
        loopDelaySeconds = 60,
        deviationTriggerPercent = 2.0,
        dustThresholdUSD = 1.0,
        dryRun = false,
        simulation = true
    )

    val DEFAULT_TEST_ALLOCATIONS = listOf(
        Allocation(Asset.BTC, 50.0),
        Allocation(Asset.ETH, 30.0),
        Allocation(Asset.USD, 20.0)
    )

    val DEFAULT_TEST_CONFIG = AppConfig(
        kraken = KrakenCredentials(TestConstants.API_KEY, TestConstants.API_SECRET),
        settings = DEFAULT_TEST_SETTINGS,
        allocations = DEFAULT_TEST_ALLOCATIONS
    )
}
