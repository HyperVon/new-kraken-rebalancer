package com.gemini.krakenbot.service

/**
 * A simple in-process fake for [KrakenService] that allows tests to control returned
 * balances and prices via supplier lambdas, and to inspect recorded [executeOrder] calls.
 *
 * All suppliers and the optional [executeOrderAction] can be reassigned between tests.
 */
class FakeKrakenService : KrakenService {
    var balanceSupplier: () -> Map<String, Double> = { emptyMap() }
    var pricesSupplier: (String) -> Map<String, Double> = { emptyMap() }

    /** If set, replaces the default record-and-return behavior of [executeOrder]. */
    var executeOrderAction: ((String, String, String, Double) -> Unit)? = null

    var executedOrders = mutableListOf<OrderCall>()
    var getBalancesCallCount = 0

    data class OrderCall(val pair: String, val type: String, val side: String, val volume: Double)

    override suspend fun getBalances(): Map<String, Double> {
        getBalancesCallCount++
        return balanceSupplier()
    }

    override suspend fun getTickerPrices(pairs: String): Map<String, Double> {
        return pricesSupplier(pairs)
    }

    override suspend fun executeOrder(pair: String, type: String, side: String, volume: Double) {
        executedOrders.add(OrderCall(pair, type, side, volume))
        executeOrderAction?.invoke(pair, type, side, volume)
    }
}
