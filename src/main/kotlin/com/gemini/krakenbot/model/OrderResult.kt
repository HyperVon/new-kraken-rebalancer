package com.gemini.krakenbot.model

import java.math.BigDecimal

sealed interface OrderResult {
    val pair: String
    val side: String
    val volume: BigDecimal
    val dryRun: Boolean
    val success: Boolean
    val errorMessage: String?
    val orderTxid: String?

    companion object {
        operator fun invoke(
            success: Boolean,
            pair: String,
            side: String,
            volume: BigDecimal,
            dryRun: Boolean = false,
            errorMessage: String? = null,
            orderTxid: String? = null,
        ): OrderResult = if (success) {
            Success(pair, side, volume, dryRun, orderTxid)
        } else {
            Failure(
                pair,
                side,
                volume,
                dryRun,
                errorMessage ?: "Unknown error",
                orderTxid,
            )
        }
    }

    data class Success(
        override val pair: String,
        override val side: String,
        override val volume: BigDecimal,
        override val dryRun: Boolean = false,
        override val orderTxid: String? = null,
    ) : OrderResult {
        override val success: Boolean get() = true
        override val errorMessage: String? get() = null
    }

    data class Failure(
        override val pair: String,
        override val side: String,
        override val volume: BigDecimal,
        override val dryRun: Boolean = false,
        override val errorMessage: String,
        override val orderTxid: String? = null,
    ) : OrderResult {
        override val success: Boolean get() = false
    }
}
