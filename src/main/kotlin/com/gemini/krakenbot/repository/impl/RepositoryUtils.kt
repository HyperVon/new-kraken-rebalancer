package com.gemini.krakenbot.repository.impl

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.slf4j.Logger
import java.io.IOException

inline fun <T> Database.safeTransaction(
    log: Logger,
    logMessage: String,
    exceptionMessage: String = "Database write failed",
    crossinline block: JdbcTransaction.() -> T,
): T {
    try {
        return transaction(this) { block() }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.error(logMessage, e)
        if (e is IOException) throw e
        throw IOException(exceptionMessage, e)
    }
}

suspend fun <T> Database.safeTransactionIO(
    log: Logger,
    logMessage: String,
    exceptionMessage: String = "Database write failed",
    block: JdbcTransaction.() -> T,
): T = withContext(Dispatchers.IO) {
    safeTransaction(log, logMessage, exceptionMessage, block)
}

suspend fun <T> Database.readTransactionIO(block: JdbcTransaction.() -> T): T = withContext(Dispatchers.IO) {
    transaction(this@readTransactionIO) { block() }
}
