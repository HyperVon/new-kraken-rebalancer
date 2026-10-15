package com.gemini.krakenbot.config

import com.gemini.krakenbot.repository.ExecutionOrderIntentRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Recovers only submissions created by this execution journal before live orders are enabled. */
class ExecutionJournalBootstrap(
    private val database: ExecutionDatabase,
    private val repository: ExecutionOrderIntentRepository,
) {
    suspend fun initialize() = withContext(Dispatchers.IO) {
        repository.recoverInterruptedSubmissions()
        database.markReadyForSubmission()
    }
}
