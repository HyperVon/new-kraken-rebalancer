package com.gemini.krakenbot.service.impl

import com.gemini.krakenbot.config.Settings
import com.gemini.krakenbot.model.Asset
import com.gemini.krakenbot.model.OrderResult
import com.gemini.krakenbot.model.OrderSide
import com.gemini.krakenbot.model.OrderSubmissionState
import com.gemini.krakenbot.model.OrderType
import com.gemini.krakenbot.model.TradeRecord
import com.gemini.krakenbot.service.AssetPrices
import com.gemini.krakenbot.service.AssetValues
import com.gemini.krakenbot.service.KrakenService
import com.gemini.krakenbot.service.OrderExecutor
import com.gemini.krakenbot.service.RawBalances
import com.gemini.krakenbot.service.RebalanceOrders
import com.gemini.krakenbot.service.TradeHistoryService
import com.gemini.krakenbot.service.getTradeHistoryUntil
import com.gemini.krakenbot.util.ActionLogFormatter
import com.gemini.krakenbot.util.CASH_RESERVE_FACTOR
import com.gemini.krakenbot.util.PrecisionConstants
import com.gemini.krakenbot.util.TradeCalculator
import com.gemini.krakenbot.util.resolveBalance
import com.gemini.krakenbot.util.resolveBalanceOrNull
import com.gemini.krakenbot.util.toUsdScale
import com.gemini.krakenbot.view.util.ViewText
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.math.BigDecimal
import java.math.RoundingMode
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.milliseconds

private val ERROR_LIVE_ORDERS_BLOCKED = ViewText.ERROR_LIVE_ORDERS_BLOCKED
private val ORDER_SUBMISSION_PENDING = ViewText.ORDER_SUBMISSION_PENDING
private val ORDER_SUBMISSION_FAILED = ViewText.ORDER_SUBMISSION_FAILED
private val ORDER_SUBMISSION_FAILED_UNCERTAIN = ViewText.ORDER_SUBMISSION_FAILED_UNCERTAIN

class OrderExecutorImpl(
    private val krakenService: KrakenService,
    private val tradeHistoryService: TradeHistoryService,
) : OrderExecutor {
    private val log = LoggerFactory.getLogger(OrderExecutorImpl::class.java)

    companion object {
        private const val MAX_REFRESH_ATTEMPTS = 3
        private const val REFRESH_DELAY_MS = 250L

        /** Early-accept threshold: settle once the observed value is >= 95% of projected. */
        private val EARLY_ACCEPT_PROPORTION = BigDecimal("0.95")

        /** Backoff cap (milliseconds) for cold settle polls. */
        private const val MAX_POLL_BACKOFF_MS = 32000L
        private const val MAX_FILL_HISTORY_PAGES = 5

        /**
         * Deterministic Kraken `cl_ord_id` (UUID form) for a cycle/symbol/side.
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
        availableBalances: RawBalances?,
    ) {
        if (!settings.dryRun && !settings.simulation && tradeHistoryService.hasPendingSubmissions()) {
            log.error("Refusing live orders while an unresolved submission intent exists")
            actionLog.add(ERROR_LIVE_ORDERS_BLOCKED)
            return
        }
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
                        availableBalances?.let { resolveBalance(symbol, it) },
                    )
                if (result?.success == true) {
                    projectedCash = projectedCash.add(result.volume.multiply(prices.getValue(symbol)))
                    executedSells = true
                    result.orderTxid?.let { sellOrderTxids.add(it) }
                }
                if (shouldAbortAfterFailure(result)) return@withStableBackend
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
            val cycleBuyBudget =
                actualCash
                    .multiply(PrecisionConstants.CASH_RESERVE_FACTOR)
                    .setScale(PrecisionConstants.SCALE_USD, RoundingMode.DOWN)
            var remainingBuyBudget = cycleBuyBudget

            for ((symbol, originalCost) in buyOrders) {
                val maxAffordable =
                    remainingBuyBudget
                        .min(actualCash)
                        .setScale(PrecisionConstants.SCALE_USD, RoundingMode.DOWN)
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
                        null,
                    )
                if (result?.success == true) {
                    actualCash = actualCash.subtract(cost)
                    remainingBuyBudget =
                        remainingBuyBudget
                            .subtract(cost)
                            .setScale(PrecisionConstants.SCALE_USD, RoundingMode.DOWN)
                }
                if (shouldAbortAfterFailure(result)) return@withStableBackend
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
        availableVolume: BigDecimal?,
    ): OrderResult? {
        val price = prices[symbol] ?: BigDecimal.ZERO
        if (price.signum() == 0) return null

        // Never place a zero/negative-value order (e.g. dustThresholdUSD=0 lets a $0 amount past
        // the dust guard, or a budget-trimmed buy lands at $0). A zero volume would still hit the
        // exchange and persist a $0 TradeRecord otherwise (CQ-3-23 / #74).
        if (usdAmount.signum() <= 0) return null

        val requestedVolume = usdAmount.divide(price, PrecisionConstants.SCALE_CRYPTO, RoundingMode.DOWN)
        // Portfolio values are cent-rounded, so a full liquidation intent can round up to one
        // crypto quantum more than the entry balance. Kraken volume must never exceed holdings.
        val volume =
            if (side == OrderSide.SELL && availableVolume != null) {
                requestedVolume.min(
                    availableVolume.max(BigDecimal.ZERO).setScale(
                        PrecisionConstants.SCALE_CRYPTO,
                        RoundingMode.DOWN,
                    ),
                )
            } else {
                requestedVolume
            }
        if (volume.signum() <= 0) return null
        // Compare dust against the notional actually submitted after crypto-volume flooring.
        val effectiveUsdAmount = volume.multiply(price)
        if (effectiveUsdAmount < BigDecimal.valueOf(settings.dustThresholdUSD)) {
            log.info("Skipping dust {} for {} after volume sizing ($ {})", side.apiValue, symbol, effectiveUsdAmount)
            actionLog.add(ActionLogFormatter.formatSkippedDust(side, symbol, effectiveUsdAmount))
            return null
        }
        val pair = Asset.tradingPair(symbol)
        val clOrdId = clientOrderId(cycleId, symbol, side.apiValue)
        val isLiveSubmission = !settings.dryRun && !settings.simulation
        fun createJournalRecord(
            result: OrderResult,
            id: Int? = null,
            submissionState: OrderSubmissionState? = null,
        ): TradeRecord = TradeCalculator.createTradeRecord(
            result = result,
            symbol = symbol,
            pair = pair,
            side = side.uppercaseName,
            volume = volume,
            usdAmount = effectiveUsdAmount,
            prices = prices,
            cycleId = cycleId.ifBlank { null },
        ).copy(id = id, clientOrderId = clOrdId, submissionState = submissionState)

        val pending = createJournalRecord(
            result = OrderResult(false, pair, side.apiValue, volume, settings.dryRun, ORDER_SUBMISSION_PENDING),
            submissionState = if (isLiveSubmission) OrderSubmissionState.PENDING else null,
        )
        val pendingId = tradeHistoryService.saveTrade(pending)
        val result = try {
            backend.executeOrder(
                pair = pair,
                type = OrderType.MARKET.apiValue,
                side = side.apiValue,
                volume = volume,
                dryRun = settings.dryRun,
                clOrdId = clOrdId,
            )
        } catch (e: CancellationException) {
            // Persist the durable outcome even when the surrounding cycle has already been cancelled.
            withContext(NonCancellable) {
                markSubmissionFailureWithoutMasking(pending, pendingId, e)
            }
            throw e
        } catch (e: Exception) {
            markSubmissionFailureWithoutMasking(pending, pendingId, e)
            throw e
        }
        val resolvedResult = if (isLiveSubmission && result.success && result.orderTxid.isNullOrBlank()) {
            OrderResult(
                success = false,
                pair = result.pair,
                side = result.side,
                volume = result.volume,
                dryRun = result.dryRun,
                errorMessage = ORDER_SUBMISSION_FAILED_UNCERTAIN,
                submissionUncertain = true,
            )
        } else {
            result
        }
        logOrderResult(
            result = resolvedResult,
            actionLog = actionLog,
            symbol = symbol,
            volume = volume,
            usdAmount = effectiveUsdAmount,
            side = side,
        )
        val resolved = createJournalRecord(
            result = resolvedResult,
            id = pendingId,
            submissionState = if (isLiveSubmission && resolvedResult.submissionUncertain) {
                OrderSubmissionState.UNCERTAIN
            } else {
                null
            },
        )
        tradeHistoryService.updateTrade(pending.copy(id = pendingId), resolved)
        cycleTradeIds.add(pendingId)
        return resolvedResult
    }

    private fun shouldAbortAfterFailure(result: OrderResult?): Boolean = result?.submissionUncertain == true

    private suspend fun markSubmissionFailure(pending: TradeRecord, id: Int, message: String?) {
        tradeHistoryService.updateTrade(
            pending.copy(id = id),
            pending.copy(
                id = id,
                errorMessage = message ?: if (pending.submissionState == null) {
                    ORDER_SUBMISSION_FAILED
                } else {
                    ORDER_SUBMISSION_FAILED_UNCERTAIN
                },
                submissionState = pending.submissionState?.let { OrderSubmissionState.UNCERTAIN },
            ),
        )
    }

    private suspend fun markSubmissionFailureWithoutMasking(pending: TradeRecord, id: Int, cause: Exception) {
        try {
            markSubmissionFailure(pending, id, cause.message)
        } catch (ce: CancellationException) {
            throw ce
        } catch (persistenceFailure: Exception) {
            cause.addSuppressed(persistenceFailure)
            log.error("Failed to persist order submission failure state", persistenceFailure)
        }
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
                if (balancePeek != null) {
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
                // No spendable balance peek available due to API exception: fallback to projected cash cap
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
        return pollUsdBalanceAfterSells(backend, projectedCash).last()
    }

    private suspend fun peekUsdBalance(backend: KrakenService): BigDecimal? = try {
        val balances = backend.getBalances()
        resolveBalanceOrNull(Asset.USD, balances)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.warn("USD balance peek after fill confirmation failed", e)
        null
    }

    private fun pollFillConfirmedUsd(
        backend: KrakenService,
        openingUsd: BigDecimal,
        projectedCash: BigDecimal,
        sellOrderTxids: List<String>,
        targetThreshold: BigDecimal = projectedCash.multiply(EARLY_ACCEPT_PROPORTION),
    ): Flow<BigDecimal> = flow {
        val endSec = Instant.now().epochSecond
        val startSec = endSec - 600
        val txidSet = sellOrderTxids.toSet()
        emitAll(
            coldPollBackoff(
                actionName = "Fill confirmation",
                targetThreshold = targetThreshold,
                bestLog = "Using best fill-confirmed USD after sell refresh: {}",
                noneLog = "No fill-confirmed USD observed after sell refresh",
                resolve = { attempt ->
                    val matchedProceeds = sumMatchedSellProceeds(backend, startSec, endSec, txidSet)
                    if (matchedProceeds > BigDecimal.ZERO) {
                        val cash = openingUsd.add(matchedProceeds).toUsdScale()
                        log.info(
                            "Fill-confirmed USD after sells (attempt {}): {} (proceeds {})",
                            attempt + 1,
                            cash,
                            matchedProceeds,
                        )
                        cash
                    } else {
                        null
                    }
                },
            ),
        )
    }

    /**
     * Sum net-of-fee USD proceeds for sells whose [TradeRecord.orderTxid] is in [txidSet],
     * paginating newest-first until the exchange reports no more raw rows or
     * [MAX_FILL_HISTORY_PAGES].
     * Does not stop early when every txid has been seen once — one AddOrder can
     * produce multiple fill legs across page boundaries. Shifting pages may repeat an identified
     * fill, while identical id-less rows remain conservatively distinct legs.
     */
    private suspend fun sumMatchedSellProceeds(
        backend: KrakenService,
        startSec: Long,
        endSec: Long,
        txidSet: Set<String>,
    ): BigDecimal {
        var offset = 0
        var matchedProceeds = BigDecimal.ZERO
        val seenTradeIds = mutableSetOf<String>()
        for (page in 0 until MAX_FILL_HISTORY_PAGES) {
            val fills = backend.getTradeHistoryUntil(startSec = startSec, offset = offset, endSec = endSec)
            val totalCount = backend.getLastTradeHistoryTotalCount()
            for (fill in fills) {
                val txid = fill.orderTxid ?: continue
                if (!fill.success || !OrderSide.isSell(fill.side) || txid !in txidSet) continue
                val tradeId = fill.tradeId?.takeIf { it.isNotBlank() }
                if (tradeId != null && !seenTradeIds.add(tradeId)) continue
                val netProceeds = fill.usdAmount.subtract(fill.fee).max(BigDecimal.ZERO)
                matchedProceeds = matchedProceeds.add(netProceeds)
            }
            val nextOffset = offset + KrakenApiConstants.TRADE_HISTORY_PAGE_SIZE
            val hasMorePages = if (totalCount > 0) {
                nextOffset < totalCount
            } else {
                fills.size >= KrakenApiConstants.TRADE_HISTORY_PAGE_SIZE
            }
            if (!hasMorePages) break
            offset = nextOffset
        }
        return matchedProceeds
    }

    /**
     * Post-sell USD settle: up to [MAX_REFRESH_ATTEMPTS] polls, each waiting first and doubling the
     * [REFRESH_DELAY_MS] backoff. Keeps the best positive observation; early-exits once balance is
     * >=95% of [projectedCash]. Emits ZERO if nothing positive was seen.
     */
    private fun pollUsdBalanceAfterSells(
        backend: KrakenService,
        projectedCash: BigDecimal,
        targetThreshold: BigDecimal = projectedCash.multiply(EARLY_ACCEPT_PROPORTION),
    ): Flow<BigDecimal> = coldPollBackoff(
        actionName = "Balance",
        targetThreshold = targetThreshold,
        bestLog = "Using best observed USD balance after sell refresh: {}",
        noneLog = "No positive USD balance observed after sell refresh",
        resolve = { attempt ->
            val usdBalance = resolveBalance(Asset.USD, backend.getBalances())
            if (usdBalance > BigDecimal.ZERO) {
                log.info("Updated USD balance after sells (attempt {}): $$usdBalance", attempt + 1)
                usdBalance
            } else {
                null
            }
        },
    )

    /**
     * Shared cold-poll skeleton for USD settle: up to [MAX_REFRESH_ATTEMPTS] polls, each waiting
     * [REFRESH_DELAY_MS] then doubling with a [MAX_POLL_BACKOFF_MS] cap. Keeps the best positive
     * [resolve] result; early-exits once it reaches [targetThreshold]. Emits ZERO if nothing positive.
     */
    private fun coldPollBackoff(
        actionName: String,
        targetThreshold: BigDecimal,
        bestLog: String,
        noneLog: String,
        resolve: suspend (Int) -> BigDecimal?,
    ): Flow<BigDecimal> = flow {
        var best = BigDecimal.ZERO
        var backoffMs = REFRESH_DELAY_MS

        repeat(MAX_REFRESH_ATTEMPTS) { attempt ->
            delay(backoffMs.milliseconds)
            try {
                val value = resolve(attempt)
                if (value != null) {
                    best = best.max(value)
                    emit(best)
                    if (value >= targetThreshold) return@flow
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn("$actionName poll failed (attempt {})", attempt + 1, e)
            }
            backoffMs = (backoffMs * 2).coerceAtMost(MAX_POLL_BACKOFF_MS)
        }
        emit(best)
        if (best > BigDecimal.ZERO) {
            log.warn(bestLog, best)
        } else {
            log.error(noneLog)
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
}
