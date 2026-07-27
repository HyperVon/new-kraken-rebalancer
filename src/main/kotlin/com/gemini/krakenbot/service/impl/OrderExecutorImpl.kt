package com.gemini.krakenbot.service.impl

import com.gemini.krakenbot.config.Settings
import com.gemini.krakenbot.model.Asset
import com.gemini.krakenbot.model.OrderResult
import com.gemini.krakenbot.model.OrderSide
import com.gemini.krakenbot.model.OrderType
import com.gemini.krakenbot.service.AssetPrices
import com.gemini.krakenbot.service.AssetValues
import com.gemini.krakenbot.service.KrakenService
import com.gemini.krakenbot.service.OrderExecutor
import com.gemini.krakenbot.service.RebalanceOrders
import com.gemini.krakenbot.service.TradeHistoryService
import com.gemini.krakenbot.util.ActionLogFormatter
import com.gemini.krakenbot.util.CASH_RESERVE_FACTOR
import com.gemini.krakenbot.util.FEE_RATE_ESTIMATE
import com.gemini.krakenbot.util.PrecisionConstants
import com.gemini.krakenbot.util.TradeCalculator
import com.gemini.krakenbot.util.resolveBalance
import com.gemini.krakenbot.util.toUsdScale
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.last
import org.slf4j.LoggerFactory
import java.math.BigDecimal
import java.math.RoundingMode
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.milliseconds

class OrderExecutorImpl(
    private val krakenService: KrakenService,
    private val tradeHistoryService: TradeHistoryService,
) : OrderExecutor {
    private val log = LoggerFactory.getLogger(OrderExecutorImpl::class.java)

    companion object {
        val CASH_RESERVE_FACTOR: BigDecimal = PrecisionConstants.CASH_RESERVE_FACTOR
        const val MAX_REFRESH_ATTEMPTS = 3
        const val REFRESH_DELAY_MS = 250L

        /** Kraken TradesHistory page size; used to decide when to stop paginating fill polls. */
        const val TRADE_HISTORY_PAGE_SIZE = 50
        const val MAX_FILL_HISTORY_PAGES = 5
        val FEE_RATE_ESTIMATE: BigDecimal = PrecisionConstants.FEE_RATE_ESTIMATE

        /**
         * Deterministic Kraken `cl_ord_id` (UUID form) for a cycle/symbol/side so
         * `retryWithFlow` re-POSTs of AddOrder reuse the same client order id.
         * Uniqueness is enforced by Kraken among *open* orders only.
         */
        fun clientOrderId(cycleId: String, symbol: String, side: String): String? {
            if (cycleId.isBlank()) return null
            return UUID.nameUUIDFromBytes(
                "$cycleId|$symbol|$side".toByteArray(StandardCharsets.UTF_8),
            ).toString()
        }
    }

    override suspend fun executeOrders(
        buyOrders: RebalanceOrders,
        sellOrders: RebalanceOrders,
        currentValuesUSD: AssetValues,
        prices: AssetPrices,
        settings: Settings,
        actionLog: MutableList<String>,
        cycleId: String,
    ) {
        // Pin live vs simulation for the whole sell→buy sequence; pass settings.dryRun into
        // each placement so a mid-cycle config flip cannot change backend or dry-run mode.
        krakenService.withStableBackend { backend ->
            val openingUsd = currentValuesUSD[Asset.USD] ?: BigDecimal.ZERO
            var projectedCash = openingUsd
            var executedSells = false
            val sellOrderTxids = mutableListOf<String>()
            val cycleTradeIds = mutableListOf<Int>()

            for ((symbol, usdToSell) in sellOrders) {
                if (usdToSell < BigDecimal.valueOf(settings.dustThresholdUSD)) {
                    log.info("Skipping dust sell for {} ($ {})", symbol, usdToSell)
                    actionLog.add(ActionLogFormatter.formatSkippedDust(OrderSide.SELL, symbol, usdToSell))
                    continue
                }

                val result =
                    executeSingleOrder(
                        backend,
                        symbol,
                        usdToSell,
                        OrderSide.SELL,
                        prices,
                        settings,
                        actionLog,
                        cycleId,
                        cycleTradeIds,
                    )
                if (result?.success == true) {
                    projectedCash = projectedCash.add(usdToSell)
                    executedSells = true
                    result.orderTxid?.let { sellOrderTxids.add(it) }
                }
            }

            var actualCash = projectedCash
            // Live/sim only: confirm sell fills (or balance poll fallback); dry-run keeps projected.
            if (executedSells && !settings.dryRun) {
                actualCash =
                    settleUsdAfterSells(
                        backend = backend,
                        openingUsd = openingUsd,
                        projectedCash = projectedCash,
                        sellOrderTxids = sellOrderTxids,
                    )
                // Fail-closed: abort the buy phase if no positive USD was observed after sells.
                if (actualCash <= BigDecimal.ZERO) {
                    log.error("Aborting buys because no positive USD was confirmed after sells")
                    return@withStableBackend
                }
            }

            if (cycleId.isNotBlank() && cycleTradeIds.isNotEmpty()) {
                log.info(
                    "Cycle {} recorded trade ids: {}",
                    cycleId,
                    cycleTradeIds.joinToString(","),
                )
            }

            // Cycle-level budget: 99% of post-sell settled USD so multi-buy batches cannot erode the reserve.
            val cycleBuyBudget = actualCash.multiply(CASH_RESERVE_FACTOR).toUsdScale()
            var remainingBuyBudget = cycleBuyBudget

            for ((symbol, originalCost) in buyOrders) {
                val maxAffordable = remainingBuyBudget.min(actualCash).toUsdScale()
                var cost = originalCost
                if (cost > maxAffordable) {
                    log.warn(
                        "Buy {} exceeds cycle 99% cash reserve. Cost: {}, Max affordable: {}, " +
                            "Remaining budget: {}, Cash: {}. Reducing.",
                        symbol,
                        cost,
                        maxAffordable,
                        remainingBuyBudget,
                        actualCash,
                    )
                    cost = maxAffordable
                }

                if (cost < BigDecimal.valueOf(settings.dustThresholdUSD)) {
                    log.info("Skipping dust buy for {} ($ {})", symbol, cost)
                    actionLog.add(ActionLogFormatter.formatSkippedDust(OrderSide.BUY, symbol, cost))
                    continue
                }

                val result =
                    executeSingleOrder(
                        backend,
                        symbol,
                        cost,
                        OrderSide.BUY,
                        prices,
                        settings,
                        actionLog,
                        cycleId,
                        cycleTradeIds,
                    )
                if (result?.success == true) {
                    actualCash = actualCash.subtract(cost)
                    remainingBuyBudget = remainingBuyBudget.subtract(cost).toUsdScale()
                }
            }
        }
    }

    private suspend fun executeSingleOrder(
        backend: KrakenService,
        symbol: String,
        usdAmount: BigDecimal,
        side: OrderSide,
        prices: AssetPrices,
        settings: Settings,
        actionLog: MutableList<String>,
        cycleId: String,
        cycleTradeIds: MutableList<Int>,
    ): OrderResult? {
        val price = prices[symbol] ?: BigDecimal.ZERO
        if (price.signum() == 0) return null

        // Never place a zero/negative-value order (e.g. dustThresholdUSD=0 lets a $0 amount past
        // the dust guard, or a budget-trimmed buy lands at $0). A zero volume would still hit the
        // exchange and persist a $0 TradeRecord otherwise (CQ-3-23 / #74).
        if (usdAmount.signum() <= 0) return null

        val volume = usdAmount.divide(price, PrecisionConstants.SCALE_CRYPTO, RoundingMode.HALF_UP)
        if (volume.signum() <= 0) return null
        val pair = Asset.tradingPair(symbol)
        val clOrdId = clientOrderId(cycleId, symbol, side.apiValue)
        val result =
            backend.executeOrder(
                pair = pair,
                type = OrderType.MARKET.apiValue,
                side = side.apiValue,
                volume = volume,
                dryRun = settings.dryRun,
                clOrdId = clOrdId,
            )
        logOrderResult(
            result = result,
            actionLog = actionLog,
            symbol = symbol,
            volume = volume,
            usdAmount = usdAmount,
            side = side,
        )
        recordTrade(result, symbol, pair, side, volume, usdAmount, prices, cycleId, cycleTradeIds)
        return result
    }

    /**
     * Prefer fill-confirmed sell proceeds (trade history matched by order txid). When no txids
     * are available (tests / backends that omit them), fall back to the balance-poll heuristic.
     * When fill confirmation succeeds and a positive balance is already visible, take the min so
     * history that leads spendable cash cannot inflate the buy budget.
     */
    private suspend fun settleUsdAfterSells(
        backend: KrakenService,
        openingUsd: BigDecimal,
        projectedCash: BigDecimal,
        sellOrderTxids: List<String>,
    ): BigDecimal {
        if (sellOrderTxids.isNotEmpty()) {
            val fillConfirmed = pollFillConfirmedUsd(backend, openingUsd, projectedCash, sellOrderTxids).last()
            if (fillConfirmed > BigDecimal.ZERO) {
                val balancePeek = peekUsdBalance(backend)
                if (balancePeek > BigDecimal.ZERO) {
                    val capped = fillConfirmed.min(balancePeek)
                    if (capped < fillConfirmed) {
                        log.info(
                            "Capping fill-confirmed USD {} to observed balance {}",
                            fillConfirmed,
                            balancePeek,
                        )
                    }
                    return capped
                }
                // No spendable balance yet: never invent cash beyond this cycle's projected sells.
                val cappedToProjected = fillConfirmed.min(projectedCash)
                if (cappedToProjected < fillConfirmed) {
                    log.info(
                        "Capping fill-confirmed USD {} to projected cash {}",
                        fillConfirmed,
                        projectedCash,
                    )
                }
                return cappedToProjected
            }
            log.warn("Fill confirmation returned no positive USD; falling back to balance poll")
        }
        return refreshUsdBalanceAfterSells(backend, projectedCash)
    }

    private suspend fun refreshUsdBalanceAfterSells(backend: KrakenService, projectedCash: BigDecimal): BigDecimal =
        pollUsdBalanceAfterSells(backend, projectedCash).last()

    private suspend fun peekUsdBalance(backend: KrakenService): BigDecimal = try {
        val balances = backend.getBalances()
        if (balances.isEmpty()) {
            BigDecimal.ZERO
        } else {
            resolveBalance(Asset.USD, balances)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.warn("USD balance peek after fill confirmation failed", e)
        BigDecimal.ZERO
    }

    private fun pollFillConfirmedUsd(
        backend: KrakenService,
        openingUsd: BigDecimal,
        projectedCash: BigDecimal,
        sellOrderTxids: List<String>,
        targetThreshold: BigDecimal = projectedCash.multiply(BigDecimal("0.95")),
    ): Flow<BigDecimal> = flow {
        var bestCash = BigDecimal.ZERO
        var backoffMs = REFRESH_DELAY_MS
        val startSec = Instant.now().minusSeconds(600).epochSecond
        val txidSet = sellOrderTxids.toSet()

        repeat(MAX_REFRESH_ATTEMPTS) { attempt ->
            delay(backoffMs.milliseconds)
            try {
                val matchedProceeds = sumMatchedSellProceeds(backend, startSec, txidSet)
                if (matchedProceeds > BigDecimal.ZERO) {
                    val cash = openingUsd.add(matchedProceeds).toUsdScale()
                    bestCash = bestCash.max(cash)
                    log.info(
                        "Fill-confirmed USD after sells (attempt {}): {} (proceeds {})",
                        attempt + 1,
                        cash,
                        matchedProceeds,
                    )
                    emit(bestCash)
                    if (cash >= targetThreshold) return@flow
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn("Fill confirmation poll failed (attempt {})", attempt + 1, e)
            }
            backoffMs = (backoffMs * 2).coerceAtMost(32000L)
        }
        emit(bestCash)
        if (bestCash > BigDecimal.ZERO) {
            log.warn("Using best fill-confirmed USD after sell refresh: $$bestCash")
        } else {
            log.error("No fill-confirmed USD observed after sell refresh")
        }
    }

    /**
     * Sum net-of-fee USD proceeds for sells whose [com.gemini.krakenbot.model.TradeRecord.orderTxid] is in [txidSet],
     * paginating newest-first until a short/empty page or [MAX_FILL_HISTORY_PAGES].
     * Does not stop early when every txid has been seen once — one AddOrder can
     * produce multiple fill legs across page boundaries.
     */
    private suspend fun sumMatchedSellProceeds(
        backend: KrakenService,
        startSec: Long,
        txidSet: Set<String>,
    ): BigDecimal {
        var offset = 0
        var matchedProceeds = BigDecimal.ZERO
        for (page in 0 until MAX_FILL_HISTORY_PAGES) {
            val fills = backend.getTradeHistory(startSec = startSec, offset = offset)
            if (fills.isEmpty()) break
            for (fill in fills) {
                val txid = fill.orderTxid ?: continue
                if (!fill.success || !OrderSide.isSell(fill.side) || txid !in txidSet) continue
                val netProceeds = fill.usdAmount.subtract(fill.fee).max(BigDecimal.ZERO)
                matchedProceeds = matchedProceeds.add(netProceeds)
            }
            offset += fills.size
            if (fills.size < TRADE_HISTORY_PAGE_SIZE) break
        }
        return matchedProceeds
    }

    /**
     * Post-sell USD settle: up to [MAX_REFRESH_ATTEMPTS] polls, each waiting first and doubling the
     * [REFRESH_DELAY_MS] backoff. Keeps the best positive observation; early-exits once balance is
     * ≥95% of [projectedCash]. Emits ZERO if nothing positive was seen.
     */
    private fun pollUsdBalanceAfterSells(
        backend: KrakenService,
        projectedCash: BigDecimal,
        targetThreshold: BigDecimal = projectedCash.multiply(BigDecimal("0.95")),
    ): Flow<BigDecimal> = flow {
        var bestObservedBalance = BigDecimal.ZERO
        var backoffMs = REFRESH_DELAY_MS
        val maxAttempts = MAX_REFRESH_ATTEMPTS

        repeat(maxAttempts) { attempt ->
            delay(backoffMs.milliseconds)
            try {
                val updatedBalances = backend.getBalances()
                if (updatedBalances.isNotEmpty()) {
                    val usdBalance = resolveBalance(Asset.USD, updatedBalances)
                    if (usdBalance > BigDecimal.ZERO) {
                        bestObservedBalance = bestObservedBalance.max(usdBalance)
                        log.info("Updated USD balance after sells (attempt {}): $$usdBalance", attempt + 1)
                        emit(bestObservedBalance)
                        if (usdBalance >= targetThreshold) return@flow
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn("Balance poll failed (attempt {})", attempt + 1, e)
            }
            backoffMs = (backoffMs * 2).coerceAtMost(32000L)
        }
        emit(bestObservedBalance)
        if (bestObservedBalance > BigDecimal.ZERO) {
            log.warn("Using best observed USD balance after sell refresh: $$bestObservedBalance")
        } else {
            log.error("No positive USD balance observed after sell refresh")
        }
    }

    internal fun logOrderResult(
        result: OrderResult,
        actionLog: MutableList<String>,
        symbol: String,
        volume: BigDecimal,
        usdAmount: BigDecimal,
        side: OrderSide,
    ) {
        if (result.success) {
            actionLog.add(
                ActionLogFormatter.formatOrderExecution(
                    side = side,
                    symbol = symbol,
                    volume = volume,
                    usdAmount = usdAmount,
                    isDryRun = result.dryRun,
                ),
            )
        } else {
            actionLog.add(
                ActionLogFormatter.formatOrderFailure(
                    side = side,
                    symbol = symbol,
                    errorMessage = result.errorMessage,
                ),
            )
        }
    }

    internal suspend fun recordTrade(
        result: OrderResult,
        symbol: String,
        pair: String,
        side: OrderSide,
        volume: BigDecimal,
        usdAmount: BigDecimal,
        prices: AssetPrices,
        cycleId: String = "",
        cycleTradeIds: MutableList<Int>? = null,
    ) {
        val trade =
            TradeCalculator.createTradeRecord(
                result = result,
                symbol = symbol,
                pair = pair,
                side = side.uppercaseName,
                volume = volume,
                usdAmount = usdAmount,
                prices = prices,
                cycleId = cycleId.ifBlank { null },
            )
        val tradeId = tradeHistoryService.saveTrade(trade)
        cycleTradeIds?.add(tradeId)
    }
}
