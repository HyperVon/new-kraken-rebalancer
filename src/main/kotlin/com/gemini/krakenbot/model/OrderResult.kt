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
    val submissionUncertain: Boolean

    companion object {
        const val UNKNOWN_ERROR = "Unknown error"

        operator fun invoke(
            success: Boolean,
            pair: String,
            side: String,
            volume: BigDecimal,
            dryRun: Boolean = false,
            errorMessage: String? = null,
            orderTxid: String? = null,
            submissionUncertain: Boolean = false,
        ): OrderResult = if (success) {
            Success(pair, side, volume, dryRun, orderTxid)
        } else {
            Failure(
                pair,
                side,
                volume,
                dryRun,
                errorMessage ?: UNKNOWN_ERROR,
                orderTxid,
                submissionUncertain,
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
        override val submissionUncertain: Boolean get() = false
    }

    data class Failure(
        override val pair: String,
        override val side: String,
        override val volume: BigDecimal,
        override val dryRun: Boolean = false,
        override val errorMessage: String,
        override val orderTxid: String? = null,
        override val submissionUncertain: Boolean = false,
    ) : OrderResult {
        override val success: Boolean get() = false
    }
}
