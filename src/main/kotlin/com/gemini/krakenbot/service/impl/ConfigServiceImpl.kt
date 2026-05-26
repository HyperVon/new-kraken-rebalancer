package com.gemini.krakenbot.service.impl

import com.fasterxml.jackson.databind.ObjectMapper
import com.gemini.krakenbot.config.AppConfig
import com.gemini.krakenbot.config.Settings
import com.gemini.krakenbot.service.ConfigService
import java.io.File
import java.io.IOException
import kotlin.math.abs

class ConfigServiceImpl(
    private val objectMapper: ObjectMapper,
    private val configFilePath: String = "rebalancer-config.json"
) : ConfigService {

    @Volatile
    private lateinit var appConfig: AppConfig

    init {
        loadConfig()
    }

    override fun loadConfig() {
        val configFile = File(configFilePath)
        if (!configFile.exists()) {
            throw RuntimeException("Configuration file 'rebalancer-config.json' not found in the application directory.")
        }
        appConfig = objectMapper.readValue(configFile, AppConfig::class.java)
        validateConfig(appConfig)
    }

    override fun getConfig(): AppConfig {
        return appConfig
    }

    @Synchronized
    override fun updateConfig(newConfig: AppConfig) {
        validateConfig(newConfig)
        this.appConfig = newConfig
        try {
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(File(configFilePath), newConfig)
        } catch (e: IOException) {
            throw RuntimeException("Failed to save configuration", e)
        }
    }

    private fun validateConfig(config: AppConfig) {
        val settings = config.settings

        if (settings.loopDelaySeconds <= 0) {
            throw RuntimeException("Loop delay must be a positive integer.")
        }
        if (settings.deviationTriggerPercent < 0) {
            throw RuntimeException("Deviation trigger percent must be non-negative.")
        }
        if (settings.dustThresholdUSD < 0) {
            throw RuntimeException("Dust threshold USD must be non-negative.")
        }
        if (settings.fiatMaxDrawdown < 0 || settings.fiatMaxDrawdown > 100) {
            throw RuntimeException("Fiat max drawdown must be between 0% and 100%.")
        }
        if (settings.fiatDeploymentExponent <= 0) {
            throw RuntimeException("Fiat deployment exponent must be positive.")
        }

        val totalPercent = config.allocations.sumOf { it.targetPercent }

        if (abs(totalPercent - 100.0) > 0.001) {
            throw RuntimeException("Total allocation percentage must be exactly 100%. Current sum: $totalPercent")
        }

        val hasUsd = config.allocations.any { "USD".equals(it.symbol, ignoreCase = true) }
        if (!hasUsd) {
            throw RuntimeException("One asset must be USD.")
        }
    }
}
