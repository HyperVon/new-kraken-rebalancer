package com.gemini.krakenbot.test

import com.gemini.krakenbot.model.Asset

object TestConstants {
    const val API_KEY = "public-key"
    const val API_SECRET = "private-key"
    const val DUMMY_SIGNATURE = "dummy-signature"
    const val TEST_CYCLE_ID = "test-cycle-1"

    val PAIR_BTC_USD: String = Asset.BTC_USD_PAIR
    val PAIR_ETH_USD: String = Asset.ETH_USD_PAIR
    val PAIR_DOGE_USD: String = Asset.DOGE_USD_PAIR
    val PAIR_SOL_USD: String = Asset.SOL_USD_PAIR
}
