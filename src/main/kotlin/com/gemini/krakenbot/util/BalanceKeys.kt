package com.gemini.krakenbot.util

import com.gemini.krakenbot.model.Asset
import com.gemini.krakenbot.service.RawBalances
import java.math.BigDecimal

/** Resolves a symbol against Kraken-style balance map keys (exact, uppercased, X/Z prefixes). */
fun resolveBalance(symbol: String, balances: RawBalances): BigDecimal =
    Asset.possibleBalanceKeys(symbol).firstNotNullOfOrNull { balances[it] } ?: BigDecimal.ZERO
