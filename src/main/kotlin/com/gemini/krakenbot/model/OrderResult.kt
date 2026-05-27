package com.gemini.krakenbot.model

import java.math.BigDecimal

data class OrderResult(
    val success: Boolean,
    val pair: String,
    val side: String,
    val volume: BigDecimal,
    val dryRun: Boolean = false,
    val errorMessage: String? = null
)
