package com.gemini.krakenbot.service.impl

import com.gemini.krakenbot.domain.resolveBalance
import com.gemini.krakenbot.domain.resolveBalanceOrNull
import com.gemini.krakenbot.domain.toUsdScale
import com.gemini.krakenbot.model.Asset
import com.gemini.krakenbot.model.KrakenApiConstants
import com.gemini.krakenbot.model.OrderSide
import com.gemini.krakenbot.service.KrakenService
import com.gemini.krakenbot.service.SpendableBalanceService
import com.gemini.krakenbot.service.getTradeHistoryUntil
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.last
import org.slf4j.LoggerFactory
import java.math.BigDecimal
import java.time.Instant
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.milliseconds

internal object OrderSettleHelper {
    private val log = LoggerFactory.getLogger(OrderSettleHelper::class.java)

    private const val MAX_REFRESH_ATTEMPTS = 3
    private const val REFRESH_DELAY_MS = 250L

    /** Early-accept threshold: settle once the observed value is >= 95% of projected. */
    private val EARLY_ACCEPT_PROPORTION = BigDecimal("0.95")

    /** Backoff cap (milliseconds) for cold settle polls. */
    private const val MAX_POLL_BACKOFF_MS = 32000L
    private const val MAX_FILL_HISTORY_PAGES = 5

    /**
     * Reconciles available USD cash after executing one or more sell orders.
     *
     * ### Settlement Hierarchy:
     * 1. **Primary Tier (Fill-Confirmed Proceeds)**: Polls Kraken `TradesHistory` matching [sellOrderTxids]
     *    and sums confirmed net proceeds (`openingUsd + sum(volume * price - fee)`).
     * 2. **Safety Cap 1 (Spendable Balance Peek)**: When fill confirmation succeeds, peeks at live balances
     *    and caps the settled cash to `min(fillConfirmed, balancePeek)`. This ensures that fills in trade history
     *    that have not yet settled into spendable ledger balance cannot cause downstream buys to fail for insufficient funds.
     * 3. **Safety Cap 2 (Projected Cash Cap)**: If the spendable balance peek encounters a transient API error,
     *    caps the confirmed proceeds to `min(fillConfirmed, projectedCash)` as a defensive safety upper bound.
     * 4. **Fallback Tier (Balance-Polling Heuristic)**: When [sellOrderTxids] is empty (e.g. mock/emulator paths
     *    omitting txids), fill confirmation yields no positive cash, or the capped confirmation is below 95% of
     *    [projectedCash], polls live balances up to [MAX_REFRESH_ATTEMPTS] with exponential backoff. The latter
     *    case treats a materially short fill history as potentially truncated while Kraken indexes or paginates it.
     */
    suspend fun settleUsdAfterSells(
        backend: KrakenService,
        openingUsd: BigDecimal,
        projectedCash: BigDecimal,
        sellOrderTxids: List<String>,
    ): BigDecimal {
        if (sellOrderTxids.isNotEmpty()) {
            val targetThreshold = projectedCash.multiply(EARLY_ACCEPT_PROPORTION)
            val fillConfirmed = pollFillConfirmedUsd(backend, openingUsd, projectedCash, sellOrderTxids).last()
            if (fillConfirmed > BigDecimal.ZERO) {
                val balancePeek = peekUsdBalance(backend)
                val settled =
                    if (balancePeek != null) {
                        val capped = fillConfirmed.min(balancePeek)
                        if (capped < fillConfirmed) {
                            log.info(
                                "Capping fill-confirmed USD {} to observed balance {}",
                                fillConfirmed,
                                balancePeek,
                            )
                        }
                        capped
                    } else {
                        // No spendable balance peek available due to API exception: fallback to projected cash cap
                        val cappedToProjected = fillConfirmed.min(projectedCash)
                        if (cappedToProjected < fillConfirmed) {
                            log.info(
                                "Capping fill-confirmed USD {} to projected cash {}",
                                fillConfirmed,
                                projectedCash,
                            )
                        }
                        cappedToProjected
                    }
                // Fill confirmation can quietly under-report when Kraken trade history indexing lags
                // or a deep page offset hides fills (10-minute window, 5-page cap). Only the balance
                // poll sees the full ledger effect, so treat a materially short fill total as evidence
                // of a truncated view and fall through to the balance-polling tier.
                if (settled >= targetThreshold) {
                    return settled
                }
                log.warn(
                    "Fill-confirmed USD {} is below the {} settle threshold ({}); " +
                        "falling back to balance poll",
                    settled,
                    targetThreshold,
                    EARLY_ACCEPT_PROPORTION,
                )
            } else {
                log.warn("Fill confirmation returned no positive USD; falling back to balance poll")
            }
        }
        return pollUsdBalanceAfterSells(backend, projectedCash).last()
    }

    private suspend fun peekUsdBalance(backend: KrakenService): BigDecimal? = try {
        val balances = backend.getSpendableBalancesForSettlement()
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

    private suspend fun sumMatchedSellProceeds(
        backend: KrakenService,
        startSec: Long,
        endSec: Long,
        txidSet: Set<String>,
    ): BigDecimal {
        var offset = 0
        var matchedProceeds = BigDecimal.ZERO
        val seenTradeIds = mutableSetOf<String>()

        repeat(MAX_FILL_HISTORY_PAGES) {
            val fills = backend.getTradeHistoryUntil(
                startSec = startSec,
                offset = offset,
                endSec = endSec,
            )
            val totalCount = backend.getLastTradeHistoryTotalCount()

            for (fill in fills) {
                val txid = fill.orderTxid ?: continue
                if (!fill.success || !OrderSide.isSell(fill.side) || txid !in txidSet) continue

                val tradeId = fill.tradeId?.takeIf { it.isNotBlank() }
                if (tradeId != null && !seenTradeIds.add(tradeId)) continue

                val netProceeds = fill.usdAmount
                    .subtract(fill.fee)
                    .max(BigDecimal.ZERO)

                matchedProceeds = matchedProceeds.add(netProceeds)
            }

            val nextOffset = offset + KrakenApiConstants.TRADE_HISTORY_PAGE_SIZE
            val hasMorePages =
                if (totalCount > 0) {
                    nextOffset < totalCount
                } else {
                    fills.size >= KrakenApiConstants.TRADE_HISTORY_PAGE_SIZE
                }

            if (!hasMorePages) {
                return matchedProceeds
            }

            offset = nextOffset
        }

        return matchedProceeds
    }

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
            val usdBalance = resolveBalance(Asset.USD, backend.getSpendableBalancesForSettlement())
            if (usdBalance > BigDecimal.ZERO) {
                log.info("Updated USD balance after sells (attempt {}): $$usdBalance", attempt + 1)
                usdBalance
            } else {
                null
            }
        },
    )

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

    private suspend fun KrakenService.getSpendableBalancesForSettlement() =
        (this as? SpendableBalanceService)?.getSpendableBalances() ?: getBalances()
}
