package com.gemini.krakenbot.service

import com.gemini.krakenbot.config.AppConfig
import com.gemini.krakenbot.config.Settings
import kotlinx.coroutines.flow.Flow
import java.io.IOException

interface ConfigService {
    fun beginExecutionSession()
    fun endExecutionSession()

    @Throws(IOException::class)
    fun loadConfig()

    fun getConfig(): AppConfig

    /**
     * Rejects invalid numeric bounds and portfolio allocations before persistence or publication.
     * Allocations must use unique valid symbols, include USD, and total 100% within the implementation tolerance.
     */
    fun updateConfig(newConfig: AppConfig)

    fun watchConfigChanges(): Flow<Settings>
}
