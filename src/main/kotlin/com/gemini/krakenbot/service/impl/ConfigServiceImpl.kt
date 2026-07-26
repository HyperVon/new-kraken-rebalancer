package com.gemini.krakenbot.service.impl

import com.fasterxml.jackson.databind.ObjectMapper
import com.gemini.krakenbot.config.AppConfig
import com.gemini.krakenbot.config.InvalidConfigurationException
import com.gemini.krakenbot.config.KrakenCredentials
import com.gemini.krakenbot.config.Settings
import com.gemini.krakenbot.service.ConfigService
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.math.abs

class ConfigServiceImpl(
    private val objectMapper: ObjectMapper,
    private val configFilePath: String = DEFAULT_CONFIG_FILE_PATH,
) : ConfigService {
    @Volatile
    private lateinit var appConfig: AppConfig

    /** Raw credential strings from disk (env placeholders), not resolved runtime secrets. */
    @Volatile
    private lateinit var persistedKrakenCredentials: KrakenCredentials

    // Replay gives new collectors the current settings; dropping the superseded value keeps
    // configuration updates synchronous and non-blocking.
    private val _configFlow =
        MutableSharedFlow<Settings>(
            replay = 1,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )

    init {
        loadConfig()
    }

    @Synchronized
    override fun loadConfig() {
        val rawContent = readRawConfigContent()
        persistedKrakenCredentials = parseConfig(rawContent).kraken
        val parsedConfig = parseConfig(resolveEnvVars(rawContent))
        val validatedConfig = validateOrThrowInvalidConfiguration(parsedConfig)
        appConfig = validatedConfig
        _configFlow.tryEmit(validatedConfig.settings)
    }

    override fun getConfig(): AppConfig = appConfig

    @Synchronized
    override fun updateConfig(newConfig: AppConfig) {
        val validatedConfig = validateOrThrowInvalidConfiguration(newConfig)
        val previousKraken = appConfig.kraken
        val persistedConfig = configForPersistence(validatedConfig, previousKraken)
        appConfig = validatedConfig
        writeConfigAtomically(persistedConfig)
        persistedKrakenCredentials = persistedConfig.kraken
        _configFlow.tryEmit(validatedConfig.settings)
    }

    override fun watchConfigChanges(): Flow<Settings> = _configFlow.asSharedFlow()

    private fun readRawConfigContent(): String {
        val configFile = File(configFilePath)

        check(configFile.exists()) {
            "Configuration file '$configFilePath' not found in the application directory."
        }

        return configFile.readText()
    }

    private fun configForPersistence(config: AppConfig, previousKraken: KrakenCredentials): AppConfig {
        // The settings form posts resolved credentials back unchanged. Preserve the raw placeholders
        // in that case so saving unrelated settings does not write environment secrets to disk.
        val krakenToPersist =
            if (krakenCredentialsEqual(config.kraken, previousKraken)) {
                persistedKrakenCredentials
            } else {
                config.kraken
            }
        return config.copy(kraken = krakenToPersist)
    }

    private fun krakenCredentialsEqual(left: KrakenCredentials, right: KrakenCredentials): Boolean =
        left.apiKey.value == right.apiKey.value && left.privateKey.value == right.privateKey.value

    private fun parseConfig(content: String): AppConfig = objectMapper.readValue(content, AppConfig::class.java)

    private fun resolveEnvVars(content: String): String = ENV_VAR_PATTERN.replace(content) { matchResult ->
        // A non-blank environment value wins, then the placeholder default, then an empty string.
        // Escape after substitution because replacement occurs in raw JSON rather than the parsed model.
        val placeholder = matchResult.groupValues[1]
        val parts = placeholder.split(ENV_VAR_DEFAULT_SEPARATOR, limit = 2)
        val key = parts[0]
        val defaultValue = parts.getOrElse(1) { "" }
        val resolvedValue =
            System
                .getenv(key)
                ?.takeIf { it.isNotBlank() }
                ?: defaultValue

        escapeJsonStringValue(resolvedValue)
    }

    private fun escapeJsonStringValue(value: String): String = value
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")

    private fun validateOrThrowInvalidConfiguration(config: AppConfig): AppConfig {
        try {
            validateConfig(config)
            return config
        } catch (e: IllegalArgumentException) {
            throw InvalidConfigurationException(e.message)
        }
    }

    private fun writeConfigAtomically(config: AppConfig) {
        try {
            val tempFile = File("$configFilePath.tmp")
            val targetPath = File(configFilePath).toPath()

            // Serialize completely before the atomic replacement so readers never observe a truncated
            // configuration if writing fails or the process exits mid-write.
            objectMapper
                .writerWithDefaultPrettyPrinter()
                .writeValue(tempFile, config)

            Files.move(
                tempFile.toPath(),
                targetPath,
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (e: IOException) {
            throw RuntimeException("Failed to save configuration", e)
        }
    }

    private fun validateConfig(config: AppConfig) {
        validateSettings(config)
        validateAllocations(config)
        validateDuplicateAllocationSymbols(config)
        validateTotalAllocationPercent(config)
        validateUsdAllocation(config)
    }

    private fun validateSettings(config: AppConfig) {
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
        require(settings.fiatMaxDrawdown in MIN_PERCENT..MAX_PERCENT) {
            "Fiat max drawdown must be between 0% and 100%."
        }
        require(settings.fiatDeploymentExponent > 0) {
            "Fiat deployment exponent must be positive."
        }
    }

    private fun validateAllocations(config: AppConfig) {
        require(config.allocations.isNotEmpty()) {
            "At least one allocation is required."
        }

        config.allocations.forEach { allocation ->
            require(allocation.symbol.value.isNotBlank()) {
                "Allocation symbols cannot be blank."
            }
            require(SYMBOL_PATTERN.matches(allocation.symbol.value.uppercase())) {
                "Invalid allocation symbol '${allocation.symbol.value}'. " +
                    "Symbols must be uppercase alphanumeric and up to 16 characters long."
            }
            require(allocation.targetPercent >= 0) {
                "Target percent for ${allocation.symbol} cannot be negative."
            }
        }
    }

    private fun validateDuplicateAllocationSymbols(config: AppConfig) {
        val duplicateSymbols =
            config.allocations
                .map { it.symbol.value.uppercase() }
                .groupingBy { it }
                .eachCount()
                .filterValues { count -> count > 1 }
                .keys

        require(duplicateSymbols.isEmpty()) {
            "Duplicate allocation symbols are not allowed: ${duplicateSymbols.joinToString(", ")}"
        }
    }

    private fun validateTotalAllocationPercent(config: AppConfig) {
        val totalPercent = config.allocations.sumOf { it.targetPercent }

        require(abs(totalPercent - MAX_PERCENT) <= ALLOCATION_PERCENT_TOLERANCE) {
            "Total allocation percentage must be exactly 100%. Current sum: $totalPercent"
        }
    }

    private fun validateUsdAllocation(config: AppConfig) {
        require(config.allocations.any { it.symbol.isUsd }) {
            "One asset must be USD."
        }
    }

    private companion object {
        private const val DEFAULT_CONFIG_FILE_PATH = "rebalancer-config.json"
        private const val ENV_VAR_DEFAULT_SEPARATOR = ":"
        private const val MIN_PERCENT = 0.0
        private const val MAX_PERCENT = 100.0
        private const val ALLOCATION_PERCENT_TOLERANCE = 0.001

        private val ENV_VAR_PATTERN = "\\$\\{([^}]+)}".toRegex()
        private val SYMBOL_PATTERN = "^[A-Z0-9]{1,16}$".toRegex()
    }
}
