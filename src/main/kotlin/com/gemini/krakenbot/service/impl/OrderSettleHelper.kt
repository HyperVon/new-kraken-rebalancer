package com.gemini.krakenbot.service.impl

import com.gemini.krakenbot.model.Asset
import com.gemini.krakenbot.model.OrderSide
import com.gemini.krakenbot.model.TradeRecord
import com.gemini.krakenbot.service.KrakenService
import com.gemini.krakenbot.service.getTradeHistoryUntil
import com.gemini.krakenbot.util.resolveBalance
import com.gemini.krakenbot.util.resolveBalanceOrNull
import com.gemini.krakenbot.util.toUsdScale
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
     * Prefer fill-confirmed sell proceeds (trade history matched by order txid). When no txids
     * are available (tests / backends that omit them), fall back to the balance-poll heuristic.
     * When fill confirmation succeeds and a positive balance is already visible, take the min so
     * history that leads spendable cash cannot inflate the buy budget.
     */
    suspend fun settleUsdAfterSells(
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
            val hasMorePages =
                if (totalCount > 0) {
                    nextOffset < totalCount
                } else {
                    fills.size >= KrakenApiConstants.TRADE_HISTORY_PAGE_SIZE
                }
            if (!hasMorePages) break
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
            val usdBalance = resolveBalance(Asset.USD, backend.getBalances())
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
}
