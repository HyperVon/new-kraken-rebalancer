package com.gemini.krakenbot.repository.impl

import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.Transaction
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.Logger
import java.io.IOException

inline fun <T> Database.safeTransaction(
    log: Logger,
    logMessage: String,
    exceptionMessage: String = "Database write failed",
    crossinline block: Transaction.() -> T
): T {
    try {
        return transaction(this) { block() }
    } catch (e: Exception) {
        log.error(logMessage, e)
        if (e is IOException) throw e
        throw IOException(exceptionMessage, e)
    }
}
