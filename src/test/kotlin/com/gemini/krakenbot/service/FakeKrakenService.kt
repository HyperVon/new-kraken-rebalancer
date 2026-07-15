package com.gemini.krakenbot.service

import com.gemini.krakenbot.model.OrderResult
import com.gemini.krakenbot.model.TradeRecord
import java.math.BigDecimal

/**
 * A simple in-process fake for [KrakenService] that allows tests to control returned
 * balances and prices via supplier lambdas, and to inspect recorded [executeOrder] calls.
 *
 * All suppliers and the optional [executeOrderAction] can be reassigned between tests.
 */
class FakeKrakenService : KrakenService {
    var balanceSupplier: () -> Map<String, Any> = { emptyMap() }
    var pricesSupplier: (String) -> Map<String, Any> = { emptyMap() }
    var tradeHistorySupplier: (Long?, Int?) -> List<TradeRecord> = { _, _ -> emptyList() }

    /** If set, invoked after recording the order (may throw for legacy tests). */
    var executeOrderAction: ((String, String, String, BigDecimal) -> Unit)? =
        null

    /** When set, overrides the default successful [OrderResult]. */
    var orderResultFactory: ((String, String, String, BigDecimal) -> OrderResult)? =
        null

    var executedOrders = mutableListOf<OrderCall>()
    var getBalancesCallCount = 0

    data class OrderCall(
        val pair: String,
        val type: String,
        val side: String,
        val volume: BigDecimal
    )

    override suspend fun getBalances(): RawBalances {
        getBalancesCallCount++
        return balanceSupplier().mapValues { (_, value) ->
            when (value) {
                is BigDecimal -> value
                is Double -> BigDecimal.valueOf(value)
                is Number -> BigDecimal(value.toString())
                else -> BigDecimal.ZERO
            }
        }
    }

    override suspend fun getTickerPrices(pairs: String): RawPrices {
        return pricesSupplier(pairs).mapValues { (_, value) ->
            when (value) {
                is BigDecimal -> value
                is Double -> BigDecimal.valueOf(value)
                is Number -> BigDecimal(value.toString())
                else -> BigDecimal.ZERO
            }
        }
    }

    override suspend fun getTradeHistory(startSec: Long?, offset: Int?): List<TradeRecord> {
        return tradeHistorySupplier(startSec, offset)
    }

    override suspend fun executeOrder(
        pair: String,
        type: String,
        side: String,
        volume: BigDecimal
    ): OrderResult {
        executedOrders.add(OrderCall(pair, type, side, volume))
        executeOrderAction?.invoke(pair, type, side, volume)
        return orderResultFactory?.invoke(pair, type, side, volume)
            ?: OrderResult(
                success = true,
                pair = pair,
                side = side,
                volume = volume
            )
    }

    override suspend fun getOHLC(pair: String, interval: Int, since: Long?): List<Pair<Long, BigDecimal>> {
        return emptyList()
    }
}
