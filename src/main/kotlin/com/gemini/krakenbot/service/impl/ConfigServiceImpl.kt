package com.gemini.krakenbot.service.impl

import com.fasterxml.jackson.databind.ObjectMapper
import com.gemini.krakenbot.config.AppConfig
import com.gemini.krakenbot.config.InvalidConfigurationException
import com.gemini.krakenbot.service.ConfigService
import com.gemini.krakenbot.util.AtomicJsonFile
import com.gemini.krakenbot.util.KrakenSymbols
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
            AtomicJsonFile.write(objectMapper, File(configFilePath), newConfig)
        } catch (e: IOException) {
            throw RuntimeException("Failed to save configuration", e)
        }
    }

    private fun validateConfig(config: AppConfig) {
        val settings = config.settings

        when {
            settings.loopDelaySeconds <= 0 -> {
                throw InvalidConfigurationException("Loop delay must be a positive integer.")
            }

            settings.deviationTriggerPercent < 0 -> {
                throw InvalidConfigurationException("Deviation trigger percent must be non-negative.")
            }

            settings.dustThresholdUSD < 0 -> {
                throw InvalidConfigurationException("Dust threshold USD must be non-negative.")
            }

            settings.fiatMaxDrawdown !in 0.0..100.0 -> {
                throw InvalidConfigurationException("Fiat max drawdown must be between 0% and 100%.")
            }

            settings.fiatDeploymentExponent <= 0 -> {
                throw InvalidConfigurationException("Fiat deployment exponent must be positive.")
            }
        }

        if (config.allocations.isEmpty()) {
            throw InvalidConfigurationException("At least one allocation is required.")
        }

        val symbols = config.allocations.map { it.symbol.uppercase() }
        val duplicateSymbols =
            symbols.groupingBy { it }.eachCount().filter { it.value > 1 }.keys
        if (duplicateSymbols.isNotEmpty()) {
            throw InvalidConfigurationException(
                "Duplicate allocation symbols are not allowed: ${
                    duplicateSymbols.joinToString(
                        ", "
                    )
                }"
            )
        }

        config.allocations.forEach { allocation ->
            if (allocation.symbol.isBlank()) {
                throw InvalidConfigurationException("Allocation symbols cannot be blank.")
            }
            if (allocation.targetPercent < 0) {
                throw InvalidConfigurationException(
                    "Target percent for ${allocation.symbol} cannot be negative."
                )
            }
        }

        val totalPercent = config.allocations.sumOf { it.targetPercent }

        if (abs(totalPercent - 100.0) > 0.001) {
            throw InvalidConfigurationException(
                "Total allocation percentage must be exactly 100%. Current sum: $totalPercent"
            )
        }

        val hasUsd = config.allocations.any {
            KrakenSymbols.USD.equals(
                it.symbol,
                ignoreCase = true
            )
        }
        if (!hasUsd) {
            throw InvalidConfigurationException("One asset must be USD.")
        }
    }
}
