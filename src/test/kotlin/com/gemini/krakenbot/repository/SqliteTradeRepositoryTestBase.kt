package com.gemini.krakenbot.repository

import com.gemini.krakenbot.TestFixtures
import com.gemini.krakenbot.config.DatabaseConfig
import com.gemini.krakenbot.repository.impl.SqliteTradeRepositoryImpl
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.transactions.JdbcTransactionManager
import java.io.IOException

class TradeThrowingTransactionManager(private val delegate: JdbcTransactionManager) :
    JdbcTransactionManager by delegate {
    override fun newTransaction(
        isolation: Int,
        readOnly: Boolean,
        outerTransaction: JdbcTransaction?,
    ): JdbcTransaction = throw IOException("Direct IO failure")
}

abstract class SqliteTradeRepositoryTestBase : StringSpec() {
    override fun isolationMode() = IsolationMode.InstancePerTest

    protected val db = DatabaseConfig.init(TestFixtures.MEMORY_)
    protected val repository = SqliteTradeRepositoryImpl(db)
}
