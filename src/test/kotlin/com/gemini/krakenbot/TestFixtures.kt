package com.gemini.krakenbot

/**
 * Centralizes repeated string literals used across test fixtures so that no test credential
 * or token string is hardcoded more than once.
 */
object TestFixtures {

    /** Longer-format API key used in integration/E2E/fuzz tests. */
    const val TEST_API_KEY = "apiKey"

    /** Server-side credentials stub used in DashboardController integration tests. */
    const val TEST_SERVER_API_KEY = "server-key"
    const val TEST_SERVER_API_SECRET = "server-secret"

}
