package com.gemini.krakenbot.model

/** Signals an expected order-intent reconciliation conflict that is safe to report as HTTP 409. */
class OrderIntentReconciliationException(message: String) : IllegalStateException(message)
