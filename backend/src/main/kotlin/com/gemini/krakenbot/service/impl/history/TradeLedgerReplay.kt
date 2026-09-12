package com.gemini.krakenbot.service.impl.history

import com.gemini.krakenbot.model.Asset
import com.gemini.krakenbot.model.LedgerEvent
import com.gemini.krakenbot.model.OrderSide
import com.gemini.krakenbot.model.TradeRecord
import java.math.BigDecimal

/**
 * Shared trade replay contract for historical reconstruction.
 *
 * TradesHistory owns trade economics (pair, side, volume, cost). The trade ledger legs own the
 * wallet balance effect: Kraken can charge the fee in the base asset even though TradesHistory
 * reports only its quote-equivalent fee, and ledger amounts can round differently from the
 * reported volume. When authoritative legs exist for a trade identity they therefore become the
 * wallet effect that reconstruction inverts; the TradeRecord economics remain the fallback for
 * trades whose legs are absent from the retained evidence.
 */
internal object TradeLedgerReplay {
    /** Authoritative wallet movement of one fill, net of each leg's own fee. */
    data class LedgerEffect(
        val baseNetDelta: BigDecimal,
        val quoteNetDelta: BigDecimal,
        val baseCheckpoint: BigDecimal?,
        val quoteCheckpoint: BigDecimal?,
    )

    sealed interface Classification {
        data class Replayable(
            val base: String,
            val quote: String,
            val isBuy: Boolean,
            val volume: BigDecimal,
            val quoteCost: BigDecimal,
            val fee: BigDecimal,
            val ledgerEffect: LedgerEffect? = null,
        ) : Classification

        data class Unsupported(val reason: String) : Classification
    }

    /**
     * Classify one retained trade. [tradeLegsByRefid] maps a trade identity (the ledger `refid`)
     * to its retained trade-type ledger legs; a trade whose identity has no entry keeps the
     * legacy TradeRecord economics.
     */
    fun classify(trade: TradeRecord, tradeLegsByRefid: Map<String, List<LedgerEvent>>): Classification {
        val split = Asset.splitTradingPair(trade.pair)
            ?: return Classification.Unsupported(
                "unsupported historical market ${trade.pair.trim().uppercase()}",
            )
        if (!OrderSide.isBuy(trade.side) && !OrderSide.isSell(trade.side)) {
            return Classification.Unsupported("unsupported historical trade side")
        }
        if (trade.volume.signum() < 0 || trade.fee.signum() < 0) {
            return Classification.Unsupported("malformed historical trade economics")
        }
        val isBuy = OrderSide.isBuy(trade.side)
        val quoteCost = when {
            trade.volume.signum() == 0 -> BigDecimal.ZERO

            split.quote == Asset.USD && trade.usdAmount.signum() < 0 ->
                return Classification.Unsupported("malformed historical trade economics")

            split.quote == Asset.USD && trade.usdAmount.signum() > 0 -> trade.usdAmount

            trade.price.signum() > 0 -> trade.price.multiply(trade.volume)

            else -> return Classification.Unsupported("missing historical trade cost")
        }
        val legs = trade.tradeId?.trim()?.takeIf(String::isNotEmpty)?.let(tradeLegsByRefid::get)
        val effect: LedgerEffect?
        if (legs == null) {
            effect = null
        } else {
            when (val outcome = resolveLedgerEffect(split.base, split.quote, isBuy, trade, quoteCost, legs)) {
                is EffectOutcome.Resolved -> effect = outcome.effect
                is EffectOutcome.Rejected -> return Classification.Unsupported(outcome.reason)
            }
        }
        return Classification.Replayable(
            base = split.base,
            quote = split.quote,
            isBuy = isBuy,
            volume = trade.volume,
            quoteCost = quoteCost,
            fee = trade.fee,
            ledgerEffect = effect,
        )
    }

    /**
     * Inverse of one fill. With authoritative legs the recorded post-entry balances restore each
     * wallet before the leg's own net delta is inverted; without them the legacy TradeRecord
     * economics are inverted. Returns false when a required tracked balance is missing.
     */
    fun reverseApply(replay: Classification.Replayable, balances: MutableMap<String, BigDecimal>): Boolean {
        if (replay.volume.signum() < 0 || replay.quoteCost.signum() < 0 || replay.fee.signum() < 0) return false
        val baseBalance = balances[replay.base] ?: return false
        val quoteBalance = balances[replay.quote] ?: return false
        val effect = replay.ledgerEffect
        if (effect != null) {
            val basePost = effect.baseCheckpoint ?: baseBalance
            val quotePost = effect.quoteCheckpoint ?: quoteBalance
            balances[replay.base] = basePost.subtract(effect.baseNetDelta)
            balances[replay.quote] = quotePost.subtract(effect.quoteNetDelta)
            return true
        }
        if (replay.isBuy) {
            balances[replay.base] = baseBalance.subtract(replay.volume)
            balances[replay.quote] = quoteBalance.add(replay.quoteCost).add(replay.fee)
        } else {
            balances[replay.base] = baseBalance.add(replay.volume)
            balances[replay.quote] = quoteBalance.subtract(replay.quoteCost).add(replay.fee)
        }
        return true
    }

    private sealed interface EffectOutcome {
        data class Resolved(val effect: LedgerEffect) : EffectOutcome

        data class Rejected(val reason: String) : EffectOutcome
    }

    private fun resolveLedgerEffect(
        base: String,
        quote: String,
        isBuy: Boolean,
        trade: TradeRecord,
        quoteCost: BigDecimal,
        legs: List<LedgerEvent>,
    ): EffectOutcome {
        val normalized = legs.map { leg -> Asset.normalizeLedgerAsset(leg.asset).uppercase() to leg }
        val baseLegs = normalized.filter { (asset, _) -> asset == base }
        val quoteLegs = normalized.filter { (asset, _) -> asset == quote }
        val unknown = normalized.count { (asset, _) -> asset != base && asset != quote }
        if (unknown > 0 || baseLegs.size > 1 || quoteLegs.size > 1 || legs.size > 2 || legs.isEmpty()) {
            return EffectOutcome.Rejected("unexpected historical trade ledger legs")
        }
        val baseLeg = baseLegs.singleOrNull()?.second
        val quoteLeg = quoteLegs.singleOrNull()?.second
        if (baseLeg == null) {
            // A leg whose movement rounds to exactly zero can be absent from the ledger entirely.
            // Only that case is complete evidence; anything else leaves the wallet effect unknown.
            if (trade.volume.signum() != 0) {
                return EffectOutcome.Rejected("missing historical trade ledger leg")
            }
        }
        if (quoteLeg == null && reportedQuoteDelta(trade, quote).signum() != 0) {
            return EffectOutcome.Rejected("missing historical trade ledger leg")
        }
        val effect = LedgerEffect(
            baseNetDelta = baseLeg?.netBalanceDelta() ?: BigDecimal.ZERO,
            quoteNetDelta = quoteLeg?.netBalanceDelta() ?: BigDecimal.ZERO,
            baseCheckpoint = baseLeg?.takeIf { it.hasAuthoritativeBalance }?.balance,
            quoteCheckpoint = quoteLeg?.takeIf { it.hasAuthoritativeBalance }?.balance,
        )
        val reason = directionFailure(effect, isBuy, trade.volume)
        return if (reason == null) EffectOutcome.Resolved(effect) else EffectOutcome.Rejected(reason)
    }

    /**
     * The quote movement the legacy TradeRecord application would invert, used only to prove that
     * a missing quote leg had no wallet effect. A USD-quoted trade reports its cost explicitly,
     * so a zero reported cost is zero movement even when an indicative price exists.
     */
    private fun reportedQuoteDelta(trade: TradeRecord, quote: String): BigDecimal {
        val cost = if (quote == Asset.USD) trade.usdAmount else trade.price.multiply(trade.volume)
        return if (OrderSide.isBuy(trade.side)) cost.add(trade.fee) else cost.subtract(trade.fee)
    }

    private fun directionFailure(effect: LedgerEffect, isBuy: Boolean, volume: BigDecimal): String? = when {
        volume.signum() == 0 && (effect.baseNetDelta.signum() != 0 || effect.quoteNetDelta.signum() != 0) ->
            "zero-volume historical trade moved a wallet balance"

        isBuy && effect.baseNetDelta.signum() < 0 -> "contradictory historical trade ledger effect"

        !isBuy && effect.baseNetDelta.signum() > 0 -> "contradictory historical trade ledger effect"

        isBuy && effect.quoteNetDelta.signum() > 0 -> "contradictory historical trade ledger effect"

        !isBuy && effect.quoteNetDelta.signum() < 0 -> "contradictory historical trade ledger effect"

        else -> null
    }
}
