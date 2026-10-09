package com.gemini.krakenbot.service

fun interface ExecutionAccountBindingVerifier {
    suspend fun ensureVerifiedForSubmission()
}
