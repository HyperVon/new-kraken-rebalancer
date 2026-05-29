package com.gemini.krakenbot

/**
 * Centralizes repeated string literals used across test fixtures so that no test credential
 * or token string is hardcoded more than once.
 */
object TestFixtures {
    /** Short placeholder credentials used in unit tests that mock out the Kraken service. */
    const val DUMMY_API_KEY = "k"
    const val DUMMY_API_SECRET = "s"

    /** Longer-format API key used in integration/E2E/fuzz tests. */
    const val TEST_API_KEY = "apiKey"

    /** Server-side credentials stub used in DashboardController integration tests. */
    const val TEST_SERVER_API_KEY = "server-key"
    const val TEST_SERVER_API_SECRET = "server-secret"

    /** Key/secret pair used in KrakenService signing tests. */
    const val TEST_SIGNING_PUBLIC_KEY = "public-key"
    const val TEST_SIGNING_SECRET_KEY = "secret-key"
}
