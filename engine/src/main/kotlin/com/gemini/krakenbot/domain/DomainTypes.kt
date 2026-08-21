package com.gemini.krakenbot.domain

import java.math.BigDecimal

typealias RawPrices = Map<String, BigDecimal>
typealias AssetPrices = Map<String, BigDecimal>
typealias RawBalances = Map<String, BigDecimal>
typealias AssetValues = Map<String, BigDecimal>
typealias AssetDeviations = Map<String, BigDecimal>
typealias RebalanceOrders = Map<String, BigDecimal>
typealias MutableRebalanceOrders = MutableMap<String, BigDecimal>

data class PortfolioValues(val totalValueUSD: BigDecimal, val currentValuesUSD: AssetValues)
