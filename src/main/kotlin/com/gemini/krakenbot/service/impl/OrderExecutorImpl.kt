package com.gemini.krakenbot.service.impl

import com.gemini.krakenbot.config.Settings
import com.gemini.krakenbot.domain.AssetPrices
import com.gemini.krakenbot.domain.AssetValues
import com.gemini.krakenbot.domain.CASH_RESERVE_FACTOR
import com.gemini.krakenbot.domain.OrderResult
import com.gemini.krakenbot.domain.RawBalances
import com.gemini.krakenbot.domain.RebalanceOrders
import com.gemini.krakenbot.domain.TradeCalculator
import com.gemini.krakenbot.domain.resolveBalance
import com.gemini.krakenbot.model.Asset
import com.gemini.krakenbot.model.OrderIntent
import com.gemini.krakenbot.model.OrderIntentState
import com.gemini.krakenbot.model.OrderSide
import com.gemini.krakenbot.model.OrderSubmissionState
import com.gemini.krakenbot.model.OrderType
import com.gemini.krakenbot.model.TradeRecord
import com.gemini.krakenbot.service.KrakenService
import com.gemini.krakenbot.service.OrderExecutor
import com.gemini.krakenbot.service.OrderIntentService
import com.gemini.krakenbot.service.TradeHistoryService
import com.gemini.krakenbot.util.ActionLogFormatter
import com.gemini.krakenbot.util.PrecisionConstants
import com.gemini.krakenbot.view.util.ViewText
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.math.BigDecimal
import java.math.RoundingMode
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException

class OrderExecutorImpl(
    private val krakenService: KrakenService,
    private val tradeHistoryService: TradeHistoryService,
    private val orderIntentService: OrderIntentService? = null,
) : OrderExecutor {
    private val log = LoggerFactory.getLogger(OrderExecutorImpl::class.java)

    companion object {
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
        val hasLegacyPending = !settings.dryRun && !settings.simulation && tradeHistoryService.hasPendingSubmissions()
        val hasJournalPending = !settings.dryRun && !settings.simulation &&
            orderIntentService?.hasUnresolvedIntents() == true
        if (!settings.dryRun && !settings.simulation && (hasLegacyPending || hasJournalPending)) {
            log.error("Refusing live orders while an unresolved submission intent exists")
            actionLog.add(ViewText.ERROR_LIVE_ORDERS_BLOCKED)
            return
        }
        // Pin live vs simulation for the whole sell→buy sequence; pass settings.dryRun into
        // each placement so a mid-cycle config flip cannot change backend or dry-run mode.
        krakenService.withStableBackend { backend ->
            val cycleTradeIds = mutableListOf<Int>()
            val context = RebalanceSessionContext(
                cycleId = cycleId,
                backend = backend,
                prices = prices,
                settings = settings,
                actionLog = actionLog,
                cycleTradeIds = cycleTradeIds,
            )
            val openingUsd = currentValuesUSD[Asset.USD] ?: BigDecimal.ZERO
            var projectedCash = openingUsd
            var executedSells = false
            val sellOrderTxids = mutableListOf<String>()

            for ((symbol, usdToSell) in sellOrders) {
                if (usdToSell < BigDecimal.valueOf(settings.minimumOrderSizeUSD)) {
                    log.info("Skipping dust sell for {} ($ {})", symbol, usdToSell)
                    actionLog.add(ActionLogFormatter.formatSkippedDust(OrderSide.SELL, symbol, usdToSell))
                    continue
                }

                val result =
                    executeSingleOrder(
                        context = context,
                        symbol = symbol,
                        usdAmount = usdToSell,
                        side = OrderSide.SELL,
                        availableVolume = availableBalances?.let { resolveBalance(symbol, it) },
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
                    OrderSettleHelper.settleUsdAfterSells(
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

                if (cost < BigDecimal.valueOf(settings.minimumOrderSizeUSD)) {
                    log.info("Skipping dust buy for {} ($ {})", symbol, cost)
                    actionLog.add(ActionLogFormatter.formatSkippedDust(OrderSide.BUY, symbol, cost))
                    continue
                }

                val result =
                    executeSingleOrder(
                        context = context,
                        symbol = symbol,
                        usdAmount = cost,
                        side = OrderSide.BUY,
                        availableVolume = null,
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
        context: RebalanceSessionContext,
        symbol: String,
        usdAmount: BigDecimal,
        side: OrderSide,
        availableVolume: BigDecimal?,
    ): OrderResult? {
        val price = context.prices[symbol] ?: BigDecimal.ZERO
        if (price.signum() == 0) {
            context.actionLog.add(ActionLogFormatter.formatSkippedMissingPrice(side, symbol))
            return null
        }

        // Never place a zero/negative-value order (e.g. minimumOrderSizeUSD=0 lets a $0 amount past
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
        if (volume.signum() <= 0) {
            log.info(
                "Skipping dust {} for {} after volume floor to 0 (usdAmount {})",
                side.apiValue,
                symbol,
                usdAmount,
            )
            context.actionLog.add(ActionLogFormatter.formatSkippedDust(side, symbol, usdAmount))
            return null
        }
        // Compare dust against the notional actually submitted after crypto-volume flooring.
        val effectiveUsdAmount = volume.multiply(price)
        if (effectiveUsdAmount < BigDecimal.valueOf(context.settings.minimumOrderSizeUSD)) {
            log.info(
                "Skipping dust {} for {} after volume sizing ($ {})",
                side.apiValue,
                symbol,
                effectiveUsdAmount,
            )
            context.actionLog.add(ActionLogFormatter.formatSkippedDust(side, symbol, effectiveUsdAmount))
            return null
        }
        val pair = Asset.tradingPair(symbol)
        val clOrdId = clientOrderId(context.cycleId, symbol, side.apiValue)
        val isLiveSubmission = !context.settings.dryRun && !context.settings.simulation
        fun createJournalRecord(
            result: OrderResult,
            id: Int? = null,
            submissionState: OrderSubmissionState? = null,
            timestamp: Instant = Instant.now(),
        ): TradeRecord = TradeCalculator.createTradeRecord(
            result = result,
            symbol = symbol,
            pair = pair,
            side = side.uppercaseName,
            volume = volume,
            usdAmount = effectiveUsdAmount,
            prices = context.prices,
            timestamp = timestamp,
            cycleId = context.cycleId.ifBlank { null },
        ).copy(id = id, clientOrderId = clOrdId, submissionState = submissionState)

        val pendingTimestamp = Instant.now()
        val pending = createJournalRecord(
            result = OrderResult(
                false,
                pair,
                side.apiValue,
                volume,
                context.settings.dryRun,
                ViewText.ORDER_SUBMISSION_PENDING,
            ),
            submissionState = if (isLiveSubmission) {
                OrderSubmissionState.PENDING
            } else {
                null
            },
            timestamp = pendingTimestamp,
        )
        val pendingId = tradeHistoryService.saveTrade(pending)
        if (isLiveSubmission && orderIntentService != null && clOrdId == null) {
            val invalidIdentity = IllegalStateException("Live order requires a non-blank cycle id")
            markSubmissionFailureWithoutMasking(pending, pendingId, invalidIdentity)
            throw invalidIdentity
        }
        val intentId = if (isLiveSubmission) {
            val intent = OrderIntent(
                cycleId = context.cycleId.ifBlank { null },
                clientOrderId = clOrdId,
                pair = pair,
                symbol = symbol,
                side = side.uppercaseName,
                volume = volume,
                usdAmount = effectiveUsdAmount,
                expectedPrice = context.prices[symbol],
                createdAt = pending.timestamp,
                state = OrderIntentState.PENDING,
                localTradeId = pendingId,
            )
            try {
                orderIntentService?.savePending(intent)
            } catch (e: Exception) {
                withContext(NonCancellable) {
                    markSubmissionFailureWithoutMasking(
                        pending.copy(submissionState = OrderSubmissionState.PENDING),
                        pendingId,
                        e,
                    )
                }
                throw e
            }
        } else {
            null
        }
        val result = try {
            context.backend.executeOrder(
                pair = pair,
                type = OrderType.MARKET.apiValue,
                side = side.apiValue,
                volume = volume,
                dryRun = context.settings.dryRun,
                clOrdId = clOrdId,
            )
        } catch (e: CancellationException) {
            // Persist the durable outcome even when the surrounding cycle has already been cancelled.
            withContext(NonCancellable) {
                val outcomeStatus = recordIntentOutcomeWithoutMasking(
                    intentId,
                    uncertainResult(pair, side, volume, context.settings, e.message),
                    e,
                )
                when (outcomeStatus) {
                    IntentOutcomeStatus.FAILED -> markSubmissionFailureWithoutMasking(
                        pending.copy(submissionState = OrderSubmissionState.UNCERTAIN),
                        pendingId,
                        e,
                    )

                    IntentOutcomeStatus.NOT_CONFIGURED -> markSubmissionFailureWithoutMasking(pending, pendingId, e)

                    IntentOutcomeStatus.APPLIED,
                    IntentOutcomeStatus.ALREADY_RESOLVED,
                    -> Unit
                }
            }
            throw e
        } catch (e: Exception) {
            val outcomeStatus = recordIntentOutcomeWithoutMasking(
                intentId,
                uncertainResult(pair, side, volume, context.settings, e.message),
                e,
            )
            when (outcomeStatus) {
                IntentOutcomeStatus.FAILED -> markSubmissionFailureWithoutMasking(
                    pending.copy(submissionState = OrderSubmissionState.UNCERTAIN),
                    pendingId,
                    e,
                )

                IntentOutcomeStatus.NOT_CONFIGURED -> markSubmissionFailureWithoutMasking(pending, pendingId, e)

                IntentOutcomeStatus.APPLIED,
                IntentOutcomeStatus.ALREADY_RESOLVED,
                -> Unit
            }
            throw e
        }
        val resolvedResult = if (isLiveSubmission && result.success && result.orderTxid.isNullOrBlank()) {
            OrderResult(
                success = false,
                pair = result.pair,
                side = result.side,
                volume = result.volume,
                dryRun = result.dryRun,
                errorMessage = ViewText.ORDER_SUBMISSION_FAILED_UNCERTAIN,
                submissionUncertain = true,
            )
        } else {
            result
        }
        if (intentId != null) {
            val outcomeApplied = orderIntentService?.recordOutcome(intentId, resolvedResult) != false
            if (!outcomeApplied) {
                val staleOutcome = uncertainResult(
                    pair = pair,
                    side = side,
                    volume = volume,
                    settings = context.settings,
                    message = "Order intent was resolved before the exchange outcome was recorded",
                )
                log.error("Order intent {} was already resolved; aborting the remaining order batch", intentId)
                context.actionLog.add(
                    ViewText.ERROR_ORDER_INTENT_ALREADY_RESOLVED_PREFIX +
                        intentId +
                        ViewText.ERROR_ORDER_INTENT_ALREADY_RESOLVED_SUFFIX,
                )
                return staleOutcome
            }
        }
        logOrderResult(
            result = resolvedResult,
            actionLog = context.actionLog,
            symbol = symbol,
            volume = volume,
            usdAmount = effectiveUsdAmount,
            side = side,
        )
        val resolved = createJournalRecord(
            result = resolvedResult,
            id = pendingId,
            submissionState = if (isLiveSubmission && orderIntentService == null &&
                resolvedResult.submissionUncertain
            ) {
                OrderSubmissionState.UNCERTAIN
            } else {
                null
            },
        )
        if (intentId == null) {
            tradeHistoryService.updateTrade(pending.copy(id = pendingId), resolved)
        }
        context.cycleTradeIds.add(pendingId)
        return resolvedResult
    }

    private fun uncertainResult(
        pair: String,
        side: OrderSide,
        volume: BigDecimal,
        settings: Settings,
        message: String?,
    ): OrderResult = OrderResult(
        success = false,
        pair = pair,
        side = side.apiValue,
        volume = volume,
        dryRun = settings.dryRun,
        errorMessage = message ?: ViewText.ORDER_SUBMISSION_FAILED_UNCERTAIN,
        submissionUncertain = true,
    )

    private suspend fun recordIntentOutcomeWithoutMasking(
        intentId: Int?,
        result: OrderResult,
        cause: Exception,
    ): IntentOutcomeStatus {
        if (intentId == null) return IntentOutcomeStatus.NOT_CONFIGURED
        try {
            return if (orderIntentService?.recordOutcome(intentId, result) == true) {
                IntentOutcomeStatus.APPLIED
            } else {
                IntentOutcomeStatus.ALREADY_RESOLVED
            }
        } catch (e: CancellationException) {
            throw e
        } catch (persistenceFailure: Exception) {
            cause.addSuppressed(persistenceFailure)
            log.error("Failed to persist order intent outcome", persistenceFailure)
            return IntentOutcomeStatus.FAILED
        }
    }

    private enum class IntentOutcomeStatus {
        APPLIED,
        ALREADY_RESOLVED,
        FAILED,
        NOT_CONFIGURED,
    }

    private fun shouldAbortAfterFailure(result: OrderResult?): Boolean = result?.submissionUncertain == true

    private suspend fun markSubmissionFailure(pending: TradeRecord, id: Int, message: String?) {
        tradeHistoryService.updateTrade(
            pending.copy(id = id),
            pending.copy(
                id = id,
                errorMessage = message ?: if (pending.submissionState == null) {
                    ViewText.ORDER_SUBMISSION_FAILED
                } else {
                    ViewText.ORDER_SUBMISSION_FAILED_UNCERTAIN
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
