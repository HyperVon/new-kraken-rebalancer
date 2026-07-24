package com.gemini.krakenbot.service

import com.gemini.krakenbot.config.AppConfig
import com.gemini.krakenbot.config.Settings
import kotlinx.coroutines.flow.Flow
import java.io.IOException

interface ConfigService {
    @Throws(IOException::class)
    fun loadConfig()

    fun getConfig(): AppConfig

    fun updateConfig(newConfig: AppConfig)

    fun watchConfigChanges(): Flow<Settings>
}
