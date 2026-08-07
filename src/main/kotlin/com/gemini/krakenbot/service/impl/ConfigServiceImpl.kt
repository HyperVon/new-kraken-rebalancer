package com.gemini.krakenbot.service.impl

import com.fasterxml.jackson.databind.ObjectMapper
import com.gemini.krakenbot.config.AppConfig
import com.gemini.krakenbot.config.InvalidConfigurationException
import com.gemini.krakenbot.config.KrakenCredentials
import com.gemini.krakenbot.config.Settings
import com.gemini.krakenbot.model.Asset
import com.gemini.krakenbot.service.AssetColorAssigner
import com.gemini.krakenbot.service.ConfigService
import com.gemini.krakenbot.util.PrecisionConstants
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import kotlin.math.abs

class ConfigServiceImpl(
    private val objectMapper: ObjectMapper,
    private val configFilePath: String = DEFAULT_CONFIG_FILE_PATH,
) : ConfigService {
    private val log = LoggerFactory.getLogger(ConfigServiceImpl::class.java)
    private val configLock = Mutex()
    private var executionSessionDepth = 0
    private var pendingConfig: AppConfig? = null

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
        loadConfigBlocking()
    }

    private fun loadConfigBlocking() {
        cleanupStaleTempFile()
        val rawContent = readRawConfigContent()
        val rawConfig = parseConfig(rawContent)
        val parsedConfig = parseConfig(resolveEnvVars(rawContent))
        val validatedConfig = validateAndNormalize(parsedConfig)
        persistedKrakenCredentials = rawConfig.kraken
        publishOrStage(validatedConfig)
    }

    @Throws(IOException::class)
    override suspend fun loadConfig() {
        withContext(Dispatchers.IO) {
            configLock.withLock {
                loadConfigBlocking()
            }
        }
    }

    override fun getConfig(): AppConfig = appConfig

    override suspend fun updateConfig(newConfig: AppConfig) {
        withContext(Dispatchers.IO) {
            configLock.withLock {
                val validatedConfig = validateAndNormalize(newConfig)
                val previousKraken = pendingConfig?.kraken ?: appConfig.kraken
                val persistedConfig = configForPersistence(validatedConfig, previousKraken)
                writeConfigAtomically(persistedConfig)
                persistedKrakenCredentials = persistedConfig.kraken
                publishOrStage(validatedConfig)
            }
        }
    }

    override suspend fun beginExecutionSession() {
        configLock.withLock {
            executionSessionDepth++
        }
    }

    override suspend fun endExecutionSession() {
        configLock.withLock {
            check(executionSessionDepth > 0) { "No execution session is active." }
            executionSessionDepth--
            if (executionSessionDepth == 0) {
                pendingConfig?.let { config ->
                    appConfig = config
                    pendingConfig = null
                    _configFlow.tryEmit(config.settings)
                }
            }
        }
    }

    override fun watchConfigChanges(): Flow<Settings> = _configFlow.asSharedFlow()

    private fun publishOrStage(config: AppConfig) {
        if (executionSessionDepth > 0) {
            pendingConfig = config
        } else {
            appConfig = config
            _configFlow.tryEmit(config.settings)
        }
    }

    private fun readRawConfigContent(): String {
        val configFile = File(configFilePath)

        check(configFile.exists()) {
            "Configuration file '$configFilePath' not found in the application directory."
        }

        // Read-path hardening must never block startup: a read-only filesystem or a config owned by
        // another user (deployed via root, run as a service account) would otherwise turn a benign
        // load into a hard boot failure. The write path still enforces owner-only permissions.
        try {
            setOwnerOnlyPermissions(configFile.toPath())
        } catch (e: IOException) {
            log.warn("Could not enforce owner-only permissions on '$configFilePath' while reading; continuing.", e)
        }
        return configFile.readText()
    }

    private fun cleanupStaleTempFile() {
        try {
            Files.deleteIfExists(File("$configFilePath.tmp").toPath())
        } catch (e: IOException) {
            log.warn("Failed to clean temporary configuration file '$configFilePath.tmp'; continuing.", e)
        }
    }

    private fun configForPersistence(config: AppConfig, previousKraken: KrakenCredentials): AppConfig {
        // Preserve each unchanged raw placeholder independently so rotating one credential does not
        // materialize the other credential's resolved environment secret.
        val krakenToPersist =
            KrakenCredentials(
                apiKey =
                if (config.kraken.apiKey.value == previousKraken.apiKey.value) {
                    persistedKrakenCredentials.apiKey
                } else {
                    config.kraken.apiKey
                },
                privateKey =
                if (config.kraken.privateKey.value == previousKraken.privateKey.value) {
                    persistedKrakenCredentials.privateKey
                } else {
                    config.kraken.privateKey
                },
            )
        return config.copy(kraken = krakenToPersist)
    }

    private fun parseConfig(content: String): AppConfig {
        val normalized = normalizeLegacyKeys(content)
        return objectMapper.readValue(normalized, AppConfig::class.java)
    }

    private fun normalizeLegacyKeys(content: String): String = if (content.contains("\"dustThresholdUSD\"")) {
        content.replace("\"dustThresholdUSD\"", "\"minimumOrderSizeUSD\"")
    } else {
        content
    }

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

    /** Canonicalizes and validates settings/allocations, then backfills missing or invalid colors. */
    private fun validateAndNormalize(config: AppConfig): AppConfig {
        try {
            val canonicalConfig = config.copy(
                allocations = config.allocations.map { allocation ->
                    allocation.copy(symbol = Asset(Asset.canonicalSymbol(allocation.symbol.value)))
                },
            )
            validateConfig(canonicalConfig)
            return canonicalConfig.copy(
                allocations = AssetColorAssigner.assignMissingColors(canonicalConfig.allocations),
            )
        } catch (e: IllegalArgumentException) {
            throw InvalidConfigurationException(e.message)
        }
    }

    private fun writeConfigAtomically(config: AppConfig) {
        val tempFile = File("$configFilePath.tmp")
        var primaryFailure: Throwable? = null
        try {
            val targetPath = File(configFilePath).toPath()
            createOwnerOnlyFile(tempFile.toPath())

            // Serialize completely before the atomic replacement so readers never observe a truncated
            // configuration if writing fails or the process exits mid-write.
            objectMapper
                .writerWithDefaultPrettyPrinter()
                .writeValue(tempFile, config)
            setOwnerOnlyPermissions(tempFile.toPath())

            Files.move(
                tempFile.toPath(),
                targetPath,
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
            setOwnerOnlyPermissions(targetPath)
        } catch (e: IOException) {
            primaryFailure = e
            throw RuntimeException("Failed to save configuration", e)
        } catch (e: RuntimeException) {
            primaryFailure = e
            throw e
        } finally {
            runCatching { Files.deleteIfExists(tempFile.toPath()) }
                .onFailure { cleanupFailure -> primaryFailure?.addSuppressed(cleanupFailure) }
        }
    }

    private fun createOwnerOnlyFile(path: Path) {
        if (Files.exists(path)) {
            setOwnerOnlyPermissions(path)
            return
        }

        try {
            Files.createFile(path, PosixFilePermissions.asFileAttribute(OWNER_ONLY_PERMISSIONS))
        } catch (_: UnsupportedOperationException) {
            Files.createFile(path)
        }
        setOwnerOnlyPermissions(path)
    }

    private fun setOwnerOnlyPermissions(path: Path) {
        Files.getFileAttributeView(path, PosixFileAttributeView::class.java)
            ?.setPermissions(OWNER_ONLY_PERMISSIONS)
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

        requireValidations(
            (settings.loopDelaySeconds > 0) to "Loop delay must be a positive integer.",
            settings.deviationTriggerPercent.isFinite() to "Deviation trigger percent must be finite.",
            (settings.deviationTriggerPercent >= 0) to "Deviation trigger percent must be non-negative.",
            settings.minimumOrderSizeUSD.isFinite() to "Minimum order size USD must be finite.",
            (settings.minimumOrderSizeUSD >= 2.0) to "Minimum order size USD must be at least \$2.",
            settings.fiatMaxDrawdown.isFinite() to "Fiat max drawdown must be finite.",
            (settings.fiatMaxDrawdown in MIN_PERCENT..MAX_PERCENT) to
                "Fiat max drawdown must be between 0% and 100%.",
            settings.fiatDeploymentExponent.isFinite() to "Fiat deployment exponent must be finite.",
            (settings.fiatDeploymentExponent > 0) to "Fiat deployment exponent must be positive.",
        )
    }

    private fun validateAllocations(config: AppConfig) {
        requireValidations(config.allocations.isNotEmpty() to "At least one allocation is required.")

        config.allocations.forEach { allocation ->
            requireValidations(
                allocation.symbol.value.isNotBlank() to "Allocation symbols cannot be blank.",
                (SYMBOL_PATTERN.matches(allocation.symbol.value.uppercase())) to
                    (
                        "Invalid allocation symbol '${allocation.symbol.value}'. " +
                            "Symbols must be uppercase alphanumeric and up to 16 characters long."
                        ),
                allocation.targetPercent.isFinite() to
                    "Target percent for ${allocation.symbol} must be finite.",
                (allocation.targetPercent >= 0) to
                    "Target percent for ${allocation.symbol} cannot be negative.",
            )
        }
    }

    private fun requireValidations(vararg validations: Pair<Boolean, String>) {
        validations.forEach { (valid, message) -> require(valid) { message } }
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

        require(abs(totalPercent - MAX_PERCENT) <= PrecisionConstants.ALLOCATION_TOLERANCE_DELTA) {
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

        private val ENV_VAR_PATTERN = "\\$\\{([^}]+)}".toRegex()
        private val SYMBOL_PATTERN = Asset.SYMBOL_PATTERN_STRING.toRegex()
        private val OWNER_ONLY_PERMISSIONS = setOf(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
        )
    }
}
