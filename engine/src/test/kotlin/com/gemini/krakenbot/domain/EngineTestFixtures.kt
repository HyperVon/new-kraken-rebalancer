package com.gemini.krakenbot.domain

import com.gemini.krakenbot.config.Allocation
import com.gemini.krakenbot.config.Settings
import com.gemini.krakenbot.model.Asset
import com.gemini.krakenbot.model.TradeRecord
import com.gemini.krakenbot.model.TradeSource
import java.math.BigDecimal
import java.time.Instant

object EngineTestFixtures {
    fun settings(
        loopDelaySeconds: Long = 60L,
        deviationTriggerPercent: Double = 5.0,
        minimumOrderSizeUSD: Double = 5.0,
        dryRun: Boolean = false,
        fiatMaxDrawdown: Double = 0.0,
        fiatDeploymentExponent: Double = 1.0,
        simulation: Boolean = true,
    ): Settings = Settings(
        loopDelaySeconds = loopDelaySeconds,
        deviationTriggerPercent = deviationTriggerPercent,
        minimumOrderSizeUSD = minimumOrderSizeUSD,
        dryRun = dryRun,
        fiatMaxDrawdown = fiatMaxDrawdown,
        fiatDeploymentExponent = fiatDeploymentExponent,
        simulation = simulation,
    )

    fun defaultAllocations(): List<Allocation> = listOf(
        Allocation(Asset.BTC, 50.0),
        Allocation(Asset.ETH, 30.0),
        Allocation(Asset.USD, 20.0),
    )

    fun tradeRecord(
        timestamp: Instant = Instant.now(),
        pair: String = "XBTUSD",
        side: String = "BUY",
        symbol: String = "BTC",
        volume: BigDecimal = BigDecimal.ONE,
        usdAmount: BigDecimal = BigDecimal("50000.00"),
        price: BigDecimal = BigDecimal("50000.00"),
        fee: BigDecimal = BigDecimal("80.00"),
        success: Boolean = true,
        source: TradeSource? = TradeSource.LOCAL_ESTIMATE,
        errorMessage: String? = null,
        id: Int? = null,
        dryRun: Boolean = false,
        slippagePercent: BigDecimal? = null,
        tradeId: String? = null,
        orderTxid: String? = null,
        cycleId: String = "test-cycle",
    ): TradeRecord = TradeRecord(
        id = id,
        timestamp = timestamp,
        pair = pair,
        side = side,
        symbol = symbol,
        volume = volume,
        usdAmount = usdAmount,
        price = price,
        fee = fee,
        success = success,
        source = source,
        errorMessage = errorMessage,
        dryRun = dryRun,
        slippagePercent = slippagePercent,
        tradeId = tradeId,
        orderTxid = orderTxid,
        cycleId = cycleId,
    )
}
