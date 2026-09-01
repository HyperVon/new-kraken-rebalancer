package com.gemini.krakenbot.service.impl.history

import com.gemini.krakenbot.model.LedgerEvent
import com.gemini.krakenbot.model.TradeOwnership
import com.gemini.krakenbot.model.TradeRecord
import java.math.BigDecimal
import java.time.Instant

/**
 * Economic events that affect either actual portfolio balances or synthetic Buy & Hold holdings.
 */
sealed class BenchmarkEvent : Comparable<BenchmarkEvent> {
    abstract val timestamp: Instant

    override fun compareTo(other: BenchmarkEvent): Int = this.timestamp.compareTo(other.timestamp)

    /**
     * Strategy-neutral external balance movement (staking reward, dividend, deposit, withdrawal, transfer).
     */
    data class ExternalBalance(
        override val timestamp: Instant,
        val asset: String,
        val netAmount: BigDecimal,
        val event: LedgerEvent,
    ) : BenchmarkEvent()

    /**
     * Trade execution (rebalancer bot, manual user trade, or unknown provenance).
     */
    data class Trade(override val timestamp: Instant, val trade: TradeRecord, val ownership: TradeOwnership) :
        BenchmarkEvent()
}
