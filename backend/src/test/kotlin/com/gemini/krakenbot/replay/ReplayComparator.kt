package com.gemini.krakenbot.replay

import com.gemini.krakenbot.config.Allocation
import com.gemini.krakenbot.config.Settings
import com.gemini.krakenbot.domain.RebalanceEvent
import com.gemini.krakenbot.domain.RebalancePlan
import com.gemini.krakenbot.domain.RebalancerEngine
import com.gemini.krakenbot.model.Result
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Replays a historical price path and capital flow schedule through the **production**
 * decision logic ([RebalancerEngine]) and reports the outcome against a buy-and-hold book
 * on identical capital.
 *
 * This exists so policy changes can be measured on real history instead of argued about. It
 * calls the same engine the live cycle calls — valuation, deviation gates and order sizing are
 * not reimplemented here — so a replay result describes production behaviour.
 *
 * Both books see the same contributions and withdrawals at the same times and pay the same fee
 * rate on every leg they actually trade, so the only difference is the rebalancing policy.
 */
class ReplayComparator(
    private val allocations: List<Allocation>,
    private val settings: Settings,
    private val feeRate: BigDecimal,
) {
    /** A capital movement: USD in (positive) or out (negative) at a day index. */
    data class Flow(val dayIndex: Int, val usd: BigDecimal)

    data class Outcome(
        val nav: BigDecimal,
        val buyAndHoldNav: BigDecimal,
        val buyAndHoldFees: BigDecimal,
        val fees: BigDecimal,
        val tradeCount: Int,
        val suppressedSells: Int,
    )

    /**
     * [closes] maps a symbol to one close per day; every series must cover the same days.
     * [trendingSymbols] supplies the sell-suppression set for a given day.
     */
    fun run(
        closes: Map<String, List<BigDecimal>>,
        flows: List<Flow>,
        openingCapital: BigDecimal,
        trendingSymbols: (dayIndex: Int, prices: Map<String, BigDecimal>) -> Set<String> = { _, _ -> emptySet() },
        lastDay: Int? = null,
    ): Outcome {
        require(closes.isNotEmpty()) { "replay needs at least one price series" }
        val days = closes.values.first().size
        require(days > 0) { "replay needs at least one day" }
        require(closes.values.all { it.size == days }) { "all price series must cover the same days" }

        // [lastDay] bounds the run so a half-window walk-forward can be measured on the same
        // opening capital and the same flows, stopping before the full history.
        val end = lastDay ?: days - 1
        require(end in 0 until days) { "lastDay must fall inside the price history" }

        val rebalanced = Book(closes, days)
        val hold = Book(closes, days)
        rebalanced.deposit(0, openingCapital, allocateAtTarget = true)
        hold.deposit(0, openingCapital, allocateAtTarget = true)

        val byDay = flows.groupBy { it.dayIndex }
        for (day in 0..end) {
            for (flow in byDay[day].orEmpty()) {
                if (flow.usd.signum() > 0) {
                    rebalanced.deposit(day, flow.usd, allocateAtTarget = true)
                    hold.deposit(day, flow.usd, allocateAtTarget = true)
                } else {
                    rebalanced.withdraw(day, flow.usd.negate())
                    hold.withdraw(day, flow.usd.negate())
                }
            }
            if (day == end) break

            val plan = rebalanced.plan(day, trendingSymbols(day, rebalanced.pricesFor(day)))
            rebalanced.suppressedSells += plan.events.count { it is RebalanceEvent.TrendSuppressedSell }
            rebalanced.execute(day, plan.buyOrders, plan.sellOrders)
        }

        return Outcome(
            nav = rebalanced.nav(end),
            buyAndHoldNav = hold.nav(end),
            buyAndHoldFees = hold.fees,
            fees = rebalanced.fees,
            tradeCount = rebalanced.trades,
            suppressedSells = rebalanced.suppressedSells,
        )
    }

    private inner class Book(private val closes: Map<String, List<BigDecimal>>, private val days: Int) {
        private val units = mutableMapOf<String, BigDecimal>()
        private var usd: BigDecimal = BigDecimal.ZERO
        var fees: BigDecimal = BigDecimal.ZERO
        var trades: Int = 0
        var suppressedSells: Int = 0

        fun pricesFor(day: Int): Map<String, BigDecimal> = closes.mapValues { it.value[day] }

        private fun valueOf(day: Int, symbol: String): BigDecimal =
            (units[symbol] ?: BigDecimal.ZERO).multiply(closes.getValue(symbol)[day])

        fun nav(day: Int): BigDecimal {
            val total = closes.keys.fold(usd) { acc, s -> acc.add(valueOf(day, s)) }
            return total.setScale(SCALE_USD, RoundingMode.HALF_UP)
        }

        fun deposit(day: Int, amount: BigDecimal, allocateAtTarget: Boolean) {
            if (!allocateAtTarget) {
                usd = usd.add(amount)
                return
            }
            for (allocation in allocations) {
                val share = amount
                    .multiply(BigDecimal.valueOf(allocation.targetPercent))
                    .divide(HUNDRED, SCALE_WORK, RoundingMode.HALF_UP)
                if (allocation.symbol.isUsd) {
                    usd = usd.add(share)
                    continue
                }
                val price = closes.getValue(allocation.symbol.value)[day]
                if (price.signum() <= 0) continue
                val fee = share.multiply(feeRate)
                addUnits(allocation.symbol.value, share.subtract(fee).divide(price, SCALE_WORK, RoundingMode.HALF_UP))
                fees = fees.add(fee)
                trades++
            }
        }

        fun withdraw(day: Int, amount: BigDecimal) {
            var need = amount
            val fromCash = usd.min(need)
            usd = usd.subtract(fromCash)
            need = need.subtract(fromCash)
            if (need.signum() <= 0) return

            val cryptoValue = closes.keys.fold(BigDecimal.ZERO) { acc, s -> acc.add(valueOf(day, s)) }
            if (cryptoValue.signum() <= 0) return

            for (symbol in closes.keys) {
                if (need.signum() <= 0) break
                val price = closes.getValue(symbol)[day]
                if (price.signum() <= 0) continue
                val share = need.multiply(valueOf(day, symbol)).divide(cryptoValue, SCALE_WORK, RoundingMode.HALF_UP)
                val desired = share.divide(
                    price.multiply(BigDecimal.ONE.subtract(feeRate)),
                    SCALE_WORK,
                    RoundingMode.HALF_UP,
                )
                // Never sell more than is held: a near-total liquidation would otherwise round
                // into a short position and inflate terminal value.
                val unitsSold = desired.min(units[symbol] ?: BigDecimal.ZERO)
                if (unitsSold.signum() <= 0) continue
                addUnits(symbol, unitsSold.negate())
                fees = fees.add(unitsSold.multiply(price).multiply(feeRate))
                trades++
                need = need.subtract(
                    unitsSold.multiply(price).multiply(BigDecimal.ONE.subtract(feeRate)),
                )
            }
        }

        fun plan(day: Int, trending: Set<String>): RebalancePlan {
            val balances = closes.keys.associateWith { units[it] ?: BigDecimal.ZERO }.plus(USD to usd)
            val values = when (
                val calculated = RebalancerEngine.calculatePortfolioValues(
                    balances = balances,
                    prices = pricesFor(day),
                    allocations = allocations,
                )
            ) {
                is Result.Failure -> return RebalancePlan(emptyMap(), emptyMap(), emptyList())
                is Result.Success -> calculated.value
            }
            val usdTarget = allocations.firstOrNull { it.symbol.isUsd }?.targetPercent ?: 0.0
            return RebalancerEngine.analyzeDeviationsPlan(
                totalPortfolioValueUSD = values.totalValueUSD,
                currentValuesUSD = values.currentValuesUSD,
                effectiveUsdTarget = BigDecimal.valueOf(usdTarget),
                cryptoScaleFactor = BigDecimal.ONE,
                allocations = allocations,
                settings = settings,
                trendingAssets = trending,
            )
        }

        fun execute(day: Int, buys: Map<String, BigDecimal>, sells: Map<String, BigDecimal>) {
            val prices = pricesFor(day)
            for ((symbol, usdAmount) in sells) {
                val price = prices[symbol] ?: continue
                if (price.signum() <= 0 || usdAmount.signum() <= 0) continue
                val desired = usdAmount.divide(
                    price.multiply(BigDecimal.ONE.subtract(feeRate)),
                    SCALE_WORK,
                    RoundingMode.HALF_UP,
                )
                val unitsSold = desired.min(units[symbol] ?: BigDecimal.ZERO)
                if (unitsSold.signum() <= 0) continue
                addUnits(symbol, unitsSold.negate())
                val proceeds = unitsSold.multiply(price).multiply(BigDecimal.ONE.subtract(feeRate))
                usd = usd.add(proceeds)
                fees = fees.add(unitsSold.multiply(price).multiply(feeRate))
                trades++
            }
            for ((symbol, usdAmount) in buys) {
                val price = prices[symbol] ?: continue
                if (price.signum() <= 0 || usdAmount.signum() <= 0) continue
                val affordable = usdAmount.min(usd)
                if (affordable.signum() <= 0) continue
                val fee = affordable.multiply(feeRate)
                addUnits(symbol, affordable.subtract(fee).divide(price, SCALE_WORK, RoundingMode.HALF_UP))
                usd = usd.subtract(affordable)
                fees = fees.add(fee)
                trades++
            }
        }

        private fun addUnits(symbol: String, delta: BigDecimal) {
            units.merge(symbol, delta, BigDecimal::add)
        }
    }

    private companion object {
        const val USD = "USD"
        val HUNDRED: BigDecimal = BigDecimal("100")
        const val SCALE_USD = 2
        const val SCALE_WORK = 12
    }
}
