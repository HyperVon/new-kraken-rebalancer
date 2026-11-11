package com.gemini.krakenbot.view.util

/** Classification shared by the server retention policy and browser filters. */
object ActivityClassification {
    const val RETENTION_PER_CLASS = 120

    fun orderSide(action: String): String? {
        val normalized = action.uppercase().removePrefix(ActionLogFormat.DRY_RUN_PREFIX.uppercase()).trim()
        return when {
            normalized.startsWith("BUY") -> "BUY"
            normalized.startsWith("SELL") -> "SELL"
            else -> null
        }
    }

    fun isProblemAction(action: String): Boolean {
        val normalized = action.uppercase().trimStart()
        return normalized.startsWith("ERROR") || normalized.startsWith("FAILED") || normalized.startsWith("WARNING")
    }

    fun hasOrders(outcome: String?, actions: List<String>): Boolean =
        outcome?.uppercase() in setOf("DRY_RUN_PLAN", "ORDERS_ATTEMPTED") || actions.any { orderSide(it) != null }

    fun hasProblem(outcome: String?, actions: List<String>): Boolean =
        outcome?.uppercase() == "PROBLEM" || actions.any(::isProblemAction)

    fun isProtected(outcome: String?, actions: List<String>): Boolean =
        hasOrders(outcome, actions) || hasProblem(outcome, actions)

    fun <T> retain(sorted: List<T>, isProtected: (T) -> Boolean): List<T> {
        var routineCount = 0
        var protectedCount = 0
        return sorted.filter {
            if (isProtected(it)) ++protectedCount <= RETENTION_PER_CLASS else ++routineCount <= RETENTION_PER_CLASS
        }
    }
}
