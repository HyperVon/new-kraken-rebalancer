package com.gemini.krakenbot.repository.impl

import com.gemini.krakenbot.config.ExecutionDatabase
import com.gemini.krakenbot.repository.ExecutionAccountBinding
import com.gemini.krakenbot.repository.ExecutionAccountBindingRepository
import com.gemini.krakenbot.repository.ExecutionAccountBindingWriteResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.sql.Connection

class SqliteExecutionAccountBindingRepositoryImpl(private val database: ExecutionDatabase) :
    ExecutionAccountBindingRepository {
    override suspend fun load(): ExecutionAccountBinding? = withContext(Dispatchers.IO) {
        database.connect().use(::loadBinding)
    }

    override suspend fun hasBindingAnchor(): Boolean = withContext(Dispatchers.IO) {
        database.connect().use(::bindingAnchor)
    }

    override suspend fun createIfPristine(binding: ExecutionAccountBinding): ExecutionAccountBindingWriteResult =
        withContext(Dispatchers.IO) {
            inTransaction("create execution account binding") { connection ->
                if (loadBinding(connection) != null) {
                    return@inTransaction ExecutionAccountBindingWriteResult.ALREADY_PRESENT
                }
                if (bindingAnchor(connection)) {
                    return@inTransaction ExecutionAccountBindingWriteResult.BLOCKED_BY_PRIOR_BINDING_EVIDENCE
                }
                if (exists(connection, "SELECT 1 FROM execution_account_binding_audit LIMIT 1")) {
                    return@inTransaction ExecutionAccountBindingWriteResult.BLOCKED_BY_PRIOR_BINDING_EVIDENCE
                }
                if (
                    exists(connection, "SELECT 1 FROM execution_order_intents WHERE legacy_source_id IS NULL LIMIT 1")
                ) {
                    return@inTransaction ExecutionAccountBindingWriteResult.BLOCKED_BY_LIVE_ORDER_HISTORY
                }
                if (
                    exists(
                        connection,
                        "SELECT 1 FROM execution_order_intents WHERE state IN ('PENDING', 'UNCERTAIN') LIMIT 1",
                    )
                ) {
                    return@inTransaction ExecutionAccountBindingWriteResult.BLOCKED_BY_UNRESOLVED_INTENT
                }
                connection.prepareStatement(
                    """
                INSERT INTO execution_account_binding(
                    singleton_id, account_identity_digest, credential_generation_digest,
                    binding_version, verification_method, verified_at
                ) VALUES (1, ?, ?, ?, ?, ?)
                    """.trimIndent(),
                ).use { statement ->
                    statement.setString(1, binding.accountIdentityDigest)
                    statement.setString(2, binding.credentialGenerationDigest)
                    statement.setInt(3, binding.bindingVersion)
                    statement.setString(4, binding.verificationMethod)
                    statement.setLong(5, binding.verifiedAtMillis)
                    statement.executeUpdate()
                }
                insertAudit(connection, binding, previous = null)
                val anchored = connection.prepareStatement(
                    "UPDATE execution_journal_metadata SET account_binding_initialized = 1 " +
                        "WHERE singleton_id = 1 AND account_binding_initialized = 0",
                ).use { it.executeUpdate() }
                check(anchored == 1) { "Execution journal account binding anchor could not be written." }
                ExecutionAccountBindingWriteResult.CREATED
            }
        }

    override suspend fun updateCredentialGeneration(
        expectedAccountIdentityDigest: String,
        expectedCredentialGenerationDigest: String,
        updated: ExecutionAccountBinding,
    ): ExecutionAccountBindingWriteResult = withContext(Dispatchers.IO) {
        inTransaction("update execution account credential generation") { connection ->
            if (exists(
                    connection,
                    "SELECT 1 FROM execution_order_intents WHERE state IN ('PENDING', 'UNCERTAIN') LIMIT 1",
                )
            ) {
                return@inTransaction ExecutionAccountBindingWriteResult.BLOCKED_BY_UNRESOLVED_INTENT
            }
            val current = loadBinding(connection)
                ?: return@inTransaction ExecutionAccountBindingWriteResult.STALE_BINDING
            if (!current.auditVerified || current.accountIdentityDigest != expectedAccountIdentityDigest ||
                current.credentialGenerationDigest != expectedCredentialGenerationDigest
            ) {
                return@inTransaction ExecutionAccountBindingWriteResult.STALE_BINDING
            }
            val updateCount = connection.prepareStatement(
                """
                UPDATE execution_account_binding
                SET credential_generation_digest = ?, verification_method = ?, verified_at = ?
                WHERE singleton_id = 1 AND account_identity_digest = ?
                    AND credential_generation_digest = ? AND binding_version = ?
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, updated.credentialGenerationDigest)
                statement.setString(2, updated.verificationMethod)
                statement.setLong(3, updated.verifiedAtMillis)
                statement.setString(4, expectedAccountIdentityDigest)
                statement.setString(5, expectedCredentialGenerationDigest)
                statement.setInt(6, updated.bindingVersion)
                statement.executeUpdate()
            }
            if (updateCount != 1) return@inTransaction ExecutionAccountBindingWriteResult.STALE_BINDING
            insertAudit(connection, updated, expectedCredentialGenerationDigest)
            ExecutionAccountBindingWriteResult.UPDATED
        }
    }

    private fun loadBinding(connection: Connection): ExecutionAccountBinding? = connection.prepareStatement(
        """
            SELECT account_identity_digest, credential_generation_digest, binding_version,
                verification_method, verified_at
            FROM execution_account_binding WHERE singleton_id = 1
        """.trimIndent(),
    ).use { statement ->
        statement.executeQuery().use { resultSet ->
            if (!resultSet.next()) return@use null
            val accountDigest = resultSet.getString(1)
            val credentialDigest = resultSet.getString(2)
            val version = resultSet.getInt(3)
            val method = resultSet.getString(4)
            val verifiedAt = resultSet.getLong(5)
            val audited = bindingAnchor(connection) && connection.prepareStatement(
                """
                    SELECT 1 FROM execution_account_binding_audit
                    WHERE account_identity_digest = ? AND credential_generation_digest = ?
                        AND verification_method = ? AND verified_at = ? LIMIT 1
                """.trimIndent(),
            ).use { audit ->
                audit.setString(1, accountDigest)
                audit.setString(2, credentialDigest)
                audit.setString(3, method)
                audit.setLong(4, verifiedAt)
                audit.executeQuery().use { it.next() }
            }
            ExecutionAccountBinding(
                accountIdentityDigest = accountDigest,
                credentialGenerationDigest = credentialDigest,
                bindingVersion = version,
                verificationMethod = method,
                verifiedAtMillis = verifiedAt,
                auditVerified = audited,
            )
        }
    }

    private fun bindingAnchor(connection: Connection): Boolean = connection.prepareStatement(
        "SELECT account_binding_initialized FROM execution_journal_metadata WHERE singleton_id = 1",
    ).use { statement ->
        statement.executeQuery().use { resultSet ->
            check(resultSet.next()) { "Execution journal account binding anchor is missing." }
            when (resultSet.getInt(1)) {
                0 -> false
                1 -> true
                else -> error("Execution journal account binding anchor is corrupt.")
            }
        }
    }

    private fun insertAudit(connection: Connection, binding: ExecutionAccountBinding, previous: String?) {
        connection.prepareStatement(
            """
            INSERT INTO execution_account_binding_audit(
                account_identity_digest, previous_credential_generation_digest,
                credential_generation_digest, verification_method, verified_at
            ) VALUES (?, ?, ?, ?, ?)
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, binding.accountIdentityDigest)
            statement.setString(2, previous)
            statement.setString(3, binding.credentialGenerationDigest)
            statement.setString(4, binding.verificationMethod)
            statement.setLong(5, binding.verifiedAtMillis)
            statement.executeUpdate()
        }
    }

    private fun exists(connection: Connection, sql: String): Boolean = connection.createStatement().use { statement ->
        statement.executeQuery(sql).use { it.next() }
    }

    private fun <T> inTransaction(operation: String, block: (Connection) -> T): T =
        database.connect().use { connection ->
            connection.autoCommit = false
            try {
                val result = block(connection)
                connection.commit()
                result
            } catch (e: Exception) {
                connection.rollback()
                throw IllegalStateException("Could not $operation in the execution journal.", e)
            }
        }
}
