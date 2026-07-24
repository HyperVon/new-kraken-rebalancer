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

    /** Credentials used in TradeHistoryServiceTest. */
    const val TRADE_HISTORY_API_KEY = "test-api-key"
    const val TRADE_HISTORY_API_SECRET = "test-private-key"

    /** Trading side constants (lowercase, matching OrderSide.apiValue). */
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
    const val DOGEUSD = "DOGEUSD"
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
    const val TEXT_HTML = "text/html"

    /** Generic credential / key constants. */
    const val SECRET = "secret"
    const val KEY = "key"

    /** In-memory database path. */
    const val MEMORY_ = ":memory:"

    /** SQL drop statements for test tables. */
    const val DROP_TABLE_IF_EXISTS_PORTFOLIO_SNAPSHOTS = "DROP TABLE IF EXISTS portfolio_snapshots"
    const val DROP_TABLE_IF_EXISTS_PORTFOLIO_STATS = "DROP TABLE IF EXISTS portfolio_stats"

    /** Sync metadata keys. */
    const val SYNC_KEY = "sync_key"
    const val SYNC_VAL = "sync_val"
    const val SYNC_VAL_UPDATED = "sync_val_updated"
    const val SYNC_OFFSET = "sync_offset"
    const val SYNC_TOTAL = "sync_total"

    /** Generic test key/value constants. */
    const val TEST_KEY = "test_key"
    const val TEST_VALUE = "test_value"
    const val TEST_VALUE_2 = "test_value2"

    /** Test resource file names. */
    const val TEST_TRADE_HISTORY_JSON = "test-trade-history.json"

    /** Misc test string constants. */
    const val PASS = "PASS"
    const val INVALID = "invalid"
    const val GZIP = "gzip"
    const val TEST = "test"
    const val HELLO = "hello"

    /** Fully-qualified class name used in mockk verification. */
    const val ORG_JETBRAINS_EXPOSED_SQL_TRANSACTIONS_TRANSACTION_API_KT =
        "org.jetbrains.exposed.v1.jdbc.transactions.JdbcTransactionInterfaceKt"

    val DEFAULT_TEST_SETTINGS = Settings(
        loopDelaySeconds = 60,
        deviationTriggerPercent = 2.0,
        dustThresholdUSD = 1.0,
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
}
