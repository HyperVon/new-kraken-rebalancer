package com.gemini.krakenbot.model

/** Signals an expected order-intent reconciliation conflict that is safe to report as HTTP 409. */
class OrderIntentReconciliationException(message: String) : IllegalStateException(message)

/** A trade reconciliation candidate was missing or not unique, so no row was mutated. */
class TradeReconciliationConflictException(message: String) : IllegalStateException(message)
