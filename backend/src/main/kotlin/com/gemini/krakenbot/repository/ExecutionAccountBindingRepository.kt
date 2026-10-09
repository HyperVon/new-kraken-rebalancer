package com.gemini.krakenbot.repository

data class ExecutionAccountBinding(
    val accountIdentityDigest: String,
    val credentialGenerationDigest: String,
    val bindingVersion: Int,
    val verificationMethod: String,
    val verifiedAtMillis: Long,
    val auditVerified: Boolean,
)

enum class ExecutionAccountBindingWriteResult {
    CREATED,
    UPDATED,
    ALREADY_PRESENT,
    BLOCKED_BY_LIVE_ORDER_HISTORY,
    BLOCKED_BY_UNRESOLVED_INTENT,
    BLOCKED_BY_PRIOR_BINDING_EVIDENCE,
    STALE_BINDING,
}

interface ExecutionAccountBindingRepository {
    suspend fun load(): ExecutionAccountBinding?

    suspend fun hasBindingAnchor(): Boolean

    /** Creates the first binding only when no prior binding evidence or post-cutover orders exist. */
    suspend fun createIfPristine(binding: ExecutionAccountBinding): ExecutionAccountBindingWriteResult

    /** Updates only the credential generation after the authenticated account identity is unchanged. */
    suspend fun updateCredentialGeneration(
        expectedAccountIdentityDigest: String,
        expectedCredentialGenerationDigest: String,
        updated: ExecutionAccountBinding,
    ): ExecutionAccountBindingWriteResult
}
