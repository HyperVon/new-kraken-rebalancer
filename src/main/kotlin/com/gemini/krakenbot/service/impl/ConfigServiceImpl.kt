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
        check(configFile.exists()) {
            "Configuration file 'rebalancer-config.json' " +
                "not found in the application directory."
        }
        appConfig = objectMapper.readValue(
            configFile,
            AppConfig::class.java
        )
        try {
            validateConfig(appConfig)
        } catch (e: IllegalArgumentException) {
            throw InvalidConfigurationException(e.message)
        }
    }

    override fun getConfig(): AppConfig = appConfig

    @Synchronized
    override fun updateConfig(newConfig: AppConfig) {
        try {
            validateConfig(newConfig)
        } catch (e: IllegalArgumentException) {
            throw InvalidConfigurationException(e.message)
        }
        this.appConfig = newConfig
        try {
            AtomicJsonFile.write(
                objectMapper,
                File(configFilePath),
                newConfig
            )
        } catch (e: IOException) {
            throw RuntimeException("Failed to save configuration", e)
        }
    }

    private fun validateConfig(config: AppConfig) {
        val settings = config.settings

        require(settings.loopDelaySeconds > 0) {
            "Loop delay must be a positive integer."
        }
        require(settings.deviationTriggerPercent >= 0) {
            "Deviation trigger percent must be non-negative."
        }
        require(settings.dustThresholdUSD >= 0) {
            "Dust threshold USD must be non-negative."
        }
        require(settings.fiatMaxDrawdown in 0.0..100.0) {
            "Fiat max drawdown must be between 0% and 100%."
        }
        require(settings.fiatDeploymentExponent > 0) {
            "Fiat deployment exponent must be positive."
        }

        require(config.allocations.isNotEmpty()) {
            "At least one allocation is required."
        }

        val symbols = config.allocations.map { it.symbol.value.uppercase() }
        val duplicateSymbols =
            symbols.groupingBy { it }
                .eachCount()
                .filter { it.value > 1 }
                .keys
        require(duplicateSymbols.isEmpty()) {
            "Duplicate allocation symbols are not allowed: ${
                duplicateSymbols.joinToString(", ")
            }"
        }

        config.allocations.forEach { allocation ->
            require(allocation.symbol.value.isNotBlank()) {
                "Allocation symbols cannot be blank."
            }
            require(allocation.targetPercent >= 0) {
                "Target percent for ${allocation.symbol} cannot be negative."
            }
        }

        val totalPercent = config.allocations.sumOf { it.targetPercent }

        require(abs(totalPercent - 100.0) <= 0.001) {
            "Total allocation percentage must be exactly 100%. Current sum: $totalPercent"
        }

        val hasUsd = config.allocations.any {
            KrakenSymbols.USD.equals(
                it.symbol.value,
                ignoreCase = true
            )
        }
        require(hasUsd) { "One asset must be USD." }
    }
}
