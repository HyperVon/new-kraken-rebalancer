package com.gemini.krakenbot.domain

import com.gemini.krakenbot.model.Asset
import java.math.BigDecimal

/** Resolves a symbol against Kraken-style balance map keys (exact, uppercased, X/Z prefixes). Returns null if no key is present. */
fun resolveBalanceOrNull(symbol: String, balances: RawBalances): BigDecimal? =
    Asset.possibleBalanceKeys(symbol).firstNotNullOfOrNull { balances[it] }

/** Resolves a symbol against Kraken-style balance map keys (exact, uppercased, X/Z prefixes). Defaults to ZERO. */
fun resolveBalance(symbol: String, balances: RawBalances): BigDecimal =
    resolveBalanceOrNull(symbol, balances) ?: BigDecimal.ZERO
