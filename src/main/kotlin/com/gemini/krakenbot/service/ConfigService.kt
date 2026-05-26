package com.gemini.krakenbot.service

import com.gemini.krakenbot.config.AppConfig
import java.io.IOException

interface ConfigService {
    @Throws(IOException::class)
    fun loadConfig()
    fun getConfig(): AppConfig
    fun updateConfig(newConfig: AppConfig)
}
