package com.gemini.krakenbot.domain

import com.gemini.krakenbot.model.Asset
import com.gemini.krakenbot.model.OrderSide
import com.gemini.krakenbot.model.TradeRecord
import com.gemini.krakenbot.model.TradeSource
import java.math.BigDecimal
import java.time.Instant
import kotlin.math.abs

/**
 * Reconciles order-level execution intents and local estimates with fill-level Kraken trade history.
 *
 * Supports 1-to-N relationships where a single local order execution intent or estimate corresponds
 * to one or more authoritative Kraken fills (complete multi-fill), while keeping distinct fill legs
 * intact, preventing double-counting of local placeholders, and distinguishing complete execution
 * from incomplete partial execution.
 */
object OrderFillReconciler {
    data class AggregatedFills(
        val fills: List<TradeRecord>,
        val totalVolume: BigDecimal,
        val totalUsd: BigDecimal,
        val totalFee: BigDecimal,
        val isComplete: Boolean,
    )

    /**
     * Verifies whether an API fill's symbol, side, and trading pair are compatible with the order.
     */
    fun isInstrumentCompatible(
        orderSymbol: String,
        orderSide: String,
        orderPair: String,
        apiFill: TradeRecord,
        allocations: List<String> = emptyList(),
    ): Boolean {
        if (!OrderSide.normalize(apiFill.side).equals(OrderSide.normalize(orderSide), ignoreCase = true)) {
            return false
        }
        val canonicalOrderSymbol = Asset.canonicalSymbol(orderSymbol)
        val canonicalFillSymbol = Asset.canonicalSymbol(apiFill.symbol)
        if (canonicalOrderSymbol.isNotEmpty() && canonicalFillSymbol.isNotEmpty()) {
            if (!canonicalOrderSymbol.equals(canonicalFillSymbol, ignoreCase = true)) return false
        }
        val orderAllocSymbol = Asset.fromTradingPair(orderPair, allocations) ?: orderSymbol
        val fillAllocSymbol = Asset.fromTradingPair(apiFill.pair, allocations) ?: apiFill.symbol
        if (!orderAllocSymbol.equals(fillAllocSymbol, ignoreCase = true)) return false

        return apiFill.pair.equals(orderPair, ignoreCase = true) ||
            (
                Asset.matchesUsdQuotedPair(orderPair, orderSymbol) &&
                    Asset.matchesUsdQuotedPair(apiFill.pair, orderSymbol)
                )
    }

    /**
     * Evaluates a collection of API fills against an order with an exact authoritative orderTxid (Path A).
     *
     * Returns null if no fills match [orderTxid], or if any fill matching [orderTxid] has an incompatible
     * symbol, side, or pair.
     */
    fun evaluateAuthoritativeFills(
        orderSymbol: String,
        orderSide: String,
        orderPair: String,
        orderVolume: BigDecimal,
        orderUsdAmount: BigDecimal,
        orderTxid: String,
        candidateFills: List<TradeRecord>,
        allocations: List<String> = emptyList(),
        tolerance: BigDecimal = BigDecimal("0.01"),
    ): AggregatedFills? {
        val normalizedOrderTxid = orderTxid.trim()
        if (normalizedOrderTxid.isEmpty()) return null

        val orderFills = candidateFills.filter { fill ->
            val fillTxid = fill.orderTxid?.trim()
            fillTxid == normalizedOrderTxid && fill.success && !fill.dryRun
        }
        if (orderFills.isEmpty()) return null

        val hasIncompatible = orderFills.any { fill ->
            !isInstrumentCompatible(orderSymbol, orderSide, orderPair, fill, allocations)
        }
        if (hasIncompatible) return null

        val totalVolume = orderFills.fold(BigDecimal.ZERO) { acc, fill -> acc.add(fill.volume) }
        val totalUsd = orderFills.fold(BigDecimal.ZERO) { acc, fill -> acc.add(fill.usdAmount) }
        val totalFee = orderFills.fold(BigDecimal.ZERO) { acc, fill -> acc.add(fill.fee) }

        val volumeMatches = isWithinRelativeTolerance(totalVolume, orderVolume, tolerance)
        val usdMatches = isWithinRelativeTolerance(totalUsd, orderUsdAmount, tolerance)

        val isComplete = volumeMatches || (orderVolume.signum() == 0 && usdMatches)

        return AggregatedFills(
            fills = orderFills,
            totalVolume = totalVolume,
            totalUsd = totalUsd,
            totalFee = totalFee,
            isComplete = isComplete,
        )
    }

    /**
     * Evaluates whether an API fill matches an order via legacy ID-less heuristics (Path B).
     */
    fun matchesHeuristic(
        orderSymbol: String,
        orderSide: String,
        orderPair: String,
        orderVolume: BigDecimal,
        orderUsdAmount: BigDecimal,
        orderExpectedPrice: BigDecimal?,
        orderTimestamp: Instant,
        apiFill: TradeRecord,
        allocations: List<String> = emptyList(),
        windowMillis: Long = 10_000L,
        tolerance: BigDecimal = BigDecimal("0.01"),
    ): Boolean {
        if (!isInstrumentCompatible(orderSymbol, orderSide, orderPair, apiFill, allocations)) {
            return false
        }
        val timeDiff = abs(orderTimestamp.toEpochMilli() - apiFill.timestamp.toEpochMilli())
        if (timeDiff > windowMillis) return false

        val volumeMatches = isWithinRelativeTolerance(orderVolume, apiFill.volume, tolerance)
        if (!volumeMatches) return false

        if (orderVolume.compareTo(apiFill.volume) != 0 &&
            !isWithinRelativeTolerance(orderUsdAmount, apiFill.usdAmount, tolerance)
        ) {
            return false
        }

        val expectedPrice = orderExpectedPrice ?: return true
        val priceTolerance = expectedPrice.abs().multiply(tolerance)
        return apiFill.price >= expectedPrice.subtract(priceTolerance) &&
            apiFill.price <= expectedPrice.add(priceTolerance)
    }

    /**
     * Enriches an API fill with order metadata (cycleId, clientOrderId, expectedPrice, slippage).
     */
    fun enrichApiFill(
        apiFill: TradeRecord,
        expectedPrice: BigDecimal?,
        cycleId: String?,
        clientOrderId: String?,
        orderTxid: String?,
    ): TradeRecord {
        val fillExpectedPrice = apiFill.expectedPrice ?: expectedPrice
        val slippage = if (fillExpectedPrice != null) {
            TradeCalculator.calculateSlippage(apiFill.side, apiFill.price, fillExpectedPrice)
        } else {
            apiFill.slippagePercent
        }

        return apiFill.copy(
            expectedPrice = fillExpectedPrice,
            slippagePercent = slippage,
            source = TradeSource.API_FILL,
            cycleId = apiFill.cycleId ?: cycleId,
            clientOrderId = apiFill.clientOrderId ?: clientOrderId,
            orderTxid = apiFill.orderTxid ?: orderTxid,
        )
    }
}
