@file:OptIn(ExperimentalCoroutinesApi::class)

package com.gemini.krakenbot.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.ObjectWriter
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.gemini.krakenbot.TestFixtures
import com.gemini.krakenbot.config.Allocation
import com.gemini.krakenbot.config.AppConfig
import com.gemini.krakenbot.config.InvalidConfigurationException
import com.gemini.krakenbot.config.KrakenCredentials
import com.gemini.krakenbot.config.Settings
import com.gemini.krakenbot.model.Asset
import com.gemini.krakenbot.service.impl.ConfigFileAttributeViews
import com.gemini.krakenbot.service.impl.ConfigFilePermissionStrategy
import com.gemini.krakenbot.service.impl.ConfigFileSecurityException
import com.gemini.krakenbot.service.impl.ConfigServiceImpl
import com.gemini.krakenbot.service.impl.NioConfigFilePermissionStrategy
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.AclEntry
import java.nio.file.attribute.AclEntryPermission
import java.nio.file.attribute.AclEntryType
import java.nio.file.attribute.AclFileAttributeView
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.UserPrincipal

class ConfigServiceTest : StringSpec() {
    override fun isolationMode() = IsolationMode.InstancePerTest

    private lateinit var configService: ConfigService
    private lateinit var objectMapper: ObjectMapper
    private lateinit var tempFile: File

    private val ownerOnlyPermissions = setOf(
        PosixFilePermission.OWNER_READ,
        PosixFilePermission.OWNER_WRITE,
    )
    private val permissivePermissions = ownerOnlyPermissions + setOf(
        PosixFilePermission.GROUP_READ,
        PosixFilePermission.OTHERS_READ,
    )

    private val nonFiniteSettingMutations =
        listOf<Pair<String, (Settings, Double) -> Settings>>(
            "deviationTriggerPercent" to { settings, value -> settings.copy(deviationTriggerPercent = value) },
            "minimumOrderSizeUSD" to { settings, value -> settings.copy(minimumOrderSizeUSD = value) },
            "fiatMaxDrawdown" to { settings, value -> settings.copy(fiatMaxDrawdown = value) },
            "fiatDeploymentExponent" to { settings, value -> settings.copy(fiatDeploymentExponent = value) },
        )

    private fun createValidConfig(file: File) {
        objectMapper.writeValue(
            file,
            TestFixtures.config(
                settings = TestFixtures.settings(loopDelaySeconds = 60L),
                allocations = listOf(Allocation(Asset.USD, 100.0)),
            ),
        )
    }

    private suspend fun assertAllocationsRejected(vararg allocations: Allocation) {
        shouldThrow<InvalidConfigurationException> {
            configService.updateConfig(configService.getConfig().copy(allocations = allocations.toList()))
        }
    }

    private suspend fun assertSettingsRejected(vararg invalidSettings: Pair<String, Settings>) {
        val currentConfig = configService.getConfig()
        invalidSettings.forEach { (name, settings) ->
            withClue(name) {
                shouldThrow<InvalidConfigurationException> {
                    configService.updateConfig(currentConfig.copy(settings = settings))
                }
            }
        }
    }

    private fun writeRawConfig(apiKey: String, privateKey: String) {
        tempFile.writeText(
            """
                {
                  "kraken": {
                    "apiKey": "$apiKey",
                    "privateKey": "$privateKey"
                  },
                  "settings": {
                    "loopDelaySeconds": 60,
                    "deviationTriggerPercent": 2.0,
                    "minimumOrderSizeUSD": 5.0,
                    "dryRun": true,
                    "fiatMaxDrawdown": 0.0,
                    "fiatDeploymentExponent": 1.0
                  },
                  "allocations": [{"symbol": "USD", "targetPercent": 100.0}]
                }
            """.trimIndent(),
        )
    }

    init {
        beforeTest {
            objectMapper = jacksonObjectMapper()
            tempFile =
                Files
                    .createTempDirectory("test")
                    .resolve("test-config.json")
                    .toFile()
            createValidConfig(tempFile)
            configService =
                ConfigServiceImpl(objectMapper, tempFile.absolutePath)
        }

        "loadConfig_Success" {
            runTest {
                configService.loadConfig()
                configService.getConfig().allocations.first().symbol.value shouldBe Asset.USD
            }
        }

        "loadConfig removes a stale temporary credential file" {
            val staleTempFile = File("${tempFile.absolutePath}.tmp")
            staleTempFile.writeText("stale credential material")

            ConfigServiceImpl(objectMapper, tempFile.absolutePath)

            staleTempFile.exists() shouldBe false
        }

        "loadConfig_FileNotFound" {
            val missingFile = File(tempFile.parent, "missing.json")
            val ex = shouldThrow<RuntimeException> {
                ConfigServiceImpl(
                    objectMapper,
                    missingFile.absolutePath,
                )
            }
            ex.message!!.contains("not found").shouldBeTrue()
        }

        "updateConfig_Success" {
            runTest {
                val oldConfig = configService.getConfig()
                val newConfig =
                    oldConfig.copy(
                        allocations = listOf(
                            Allocation(Asset.USD, 50.0),
                            Allocation(Asset.BTC, 50.0),
                        ),
                    )

                configService.updateConfig(newConfig)

                configService.getConfig().allocations.size shouldBe 2
                val readBack = objectMapper.readValue(
                    tempFile,
                    AppConfig::class.java,
                )
                readBack.allocations.size shouldBe 2
                readBack.kraken.apiKey.value shouldBe "k"
                readBack.kraken.privateKey.value shouldBe "s"
                File("${tempFile.absolutePath}.tmp").exists() shouldBe false
            }
        }

        "updateConfig canonicalizes allocation case and Kraken aliases before persistence" {
            runTest {
                configService.updateConfig(
                    configService.getConfig().copy(
                        allocations = listOf(
                            Allocation("usd", 20.0),
                            Allocation("xbt", 40.0),
                            Allocation("doge", 40.0),
                        ),
                    ),
                )

                configService.getConfig().allocations.map { it.symbol.value } shouldBe
                    listOf(Asset.USD, Asset.BTC, Asset.DOGE)
                objectMapper.readValue(tempFile, AppConfig::class.java).allocations.map { it.symbol.value } shouldBe
                    listOf(Asset.USD, Asset.BTC, Asset.DOGE)
            }
        }

        "updateConfig rejects allocation aliases that collide after canonicalization" {
            runTest {
                assertAllocationsRejected(
                    Allocation(Asset.BTC, 25.0),
                    Allocation(Asset.XBT, 25.0),
                    Allocation(Asset.USD, 50.0),
                )
                assertAllocationsRejected(
                    Allocation(Asset.DOGE, 25.0),
                    Allocation(Asset.XDG, 25.0),
                    Allocation(Asset.USD, 50.0),
                )
            }
        }

        "updateConfig persists the independent simulation flag through disk reload" {
            runTest {
                val updated = configService.getConfig().copy(
                    settings = configService.getConfig().settings.copy(simulation = true, dryRun = false),
                )

                configService.updateConfig(updated)

                ConfigServiceImpl(objectMapper, tempFile.absolutePath).getConfig().settings.simulation shouldBe true
                ConfigServiceImpl(objectMapper, tempFile.absolutePath).getConfig().settings.dryRun shouldBe false
            }
        }

        "execution session defers both updates and file reloads until the session ends" {
            runTest {
                val originalConfig = configService.getConfig()
                val updatedConfig = originalConfig.copy(
                    settings = originalConfig.settings.copy(loopDelaySeconds = 120L),
                )
                val reloadedConfig = originalConfig.copy(
                    settings = originalConfig.settings.copy(loopDelaySeconds = 180L),
                )

                configService.beginExecutionSession()
                configService.updateConfig(updatedConfig)
                configService.getConfig() shouldBe originalConfig

                objectMapper.writeValue(tempFile, reloadedConfig)
                configService.loadConfig()
                configService.getConfig() shouldBe originalConfig

                configService.endExecutionSession()
                configService.getConfig().settings.loopDelaySeconds shouldBe 180L
            }
        }

        "loadConfig_AssignsMissingColors" {
            runTest {
                configService.loadConfig()
                val colors = configService.getConfig().allocations.map { it.color }
                colors.all { it != null }.shouldBeTrue()
                colors.first() shouldBe "#94a3b8"
            }
        }

        "updateConfig_PersistsAssignedColors" {
            runTest {
                val oldConfig = configService.getConfig()
                configService.updateConfig(
                    oldConfig.copy(
                        allocations = listOf(
                            Allocation(Asset.USD, 50.0),
                            Allocation(Asset.BTC, 50.0),
                        ),
                    ),
                )
                val readBack = objectMapper.readValue(tempFile, AppConfig::class.java)
                readBack.allocations.map { it.color } shouldBe listOf("#94a3b8", "#fbbf24")
            }
        }

        "updateConfig_RejectsInvalidColorByReassigning" {
            runTest {
                val oldConfig = configService.getConfig()
                configService.updateConfig(
                    oldConfig.copy(
                        allocations = listOf(Allocation(Asset.USD, 100.0, "not-a-color")),
                    ),
                )
                val color = configService.getConfig().allocations.single().color
                color.shouldNotBeNull()
                color shouldBe "#94a3b8"
            }
        }

        "updateConfig_DoesNotPersistEnvResolvedCredentials" {
            runTest {
                val secretFromEnv = System.getenv("PATH") ?: "fallback-path"
                writeRawConfig(
                    apiKey = $$"${PATH:fallback-path}",
                    privateKey = $$"${TEST_KRAKEN_PRIVATE_KEY:default-private-key}",
                )

                val service = ConfigServiceImpl(objectMapper, tempFile.absolutePath)
                service.getConfig().kraken.apiKey.value shouldBe secretFromEnv

                val updated = service.getConfig().copy(
                    settings = service.getConfig().settings.copy(dryRun = false),
                )
                service.updateConfig(updated)

                val savedContent = tempFile.readText()
                savedContent shouldContain $$"${PATH:fallback-path}"
                savedContent shouldContain $$"${TEST_KRAKEN_PRIVATE_KEY:default-private-key}"
                savedContent shouldNotContain secretFromEnv

                val reloaded = ConfigServiceImpl(objectMapper, tempFile.absolutePath)
                reloaded.getConfig().kraken.apiKey.value shouldBe secretFromEnv
                reloaded.getConfig().settings.dryRun shouldBe false
            }
        }

        "updateConfig makes the final credential config owner-only on POSIX" {
            runTest {
                if (Files.getFileAttributeView(tempFile.toPath(), PosixFileAttributeView::class.java) != null) {
                    Files.setPosixFilePermissions(tempFile.toPath(), permissivePermissions)
                    configService.updateConfig(
                        configService.getConfig().copy(
                            settings = configService.getConfig().settings.copy(loopDelaySeconds = 61L),
                        ),
                    )

                    Files.getPosixFilePermissions(tempFile.toPath()) shouldBe ownerOnlyPermissions
                }
            }
        }

        "loadConfig hardens an existing credential config owner-only on POSIX" {
            if (Files.getFileAttributeView(tempFile.toPath(), PosixFileAttributeView::class.java) != null) {
                Files.setPosixFilePermissions(tempFile.toPath(), permissivePermissions)
                ConfigServiceImpl(objectMapper, tempFile.absolutePath)

                Files.getPosixFilePermissions(tempFile.toPath()) shouldBe ownerOnlyPermissions
            }
        }

        "loadConfig continues when existing config permissions cannot be hardened" {
            val service = ConfigServiceImpl(
                objectMapper,
                tempFile.absolutePath,
                object : ConfigFilePermissionStrategy {
                    override fun createOwnerOnlyFile(path: Path) = error("write path must not run")

                    override fun enforceOwnerOnly(path: Path): Unit = throw ConfigFileSecurityException()
                },
            )

            service.getConfig().allocations.single().symbol.value shouldBe Asset.USD
        }

        "NIO permission strategy creates POSIX files with owner-only permissions" {
            val path = tempFile.toPath().resolveSibling("posix-config.tmp")
            path.toFile().deleteOnExit()
            if (Files.getFileAttributeView(path, PosixFileAttributeView::class.java) != null) {
                try {
                    NioConfigFilePermissionStrategy().createOwnerOnlyFile(path)

                    Files.getPosixFilePermissions(path) shouldBe ownerOnlyPermissions
                } finally {
                    Files.deleteIfExists(path)
                }
            }
        }

        "NIO permission strategy uses an owner ACL when POSIX permissions are unavailable" {
            val aclView = mockk<AclFileAttributeView>(relaxed = true)
            val owner = object : UserPrincipal {
                override fun getName(): String = "test-owner"
            }
            var observedAcl: List<AclEntry>? = null
            every { aclView.setAcl(any()) } answers {
                observedAcl = firstArg()
            }

            val strategy = NioConfigFilePermissionStrategy(
                attributeViews = object : ConfigFileAttributeViews {
                    override fun posix(path: Path): PosixFileAttributeView? = null

                    override fun acl(path: Path): AclFileAttributeView = aclView

                    override fun owner(path: Path): UserPrincipal = owner
                },
                createWithPosixPermissions = { throw UnsupportedOperationException("POSIX unavailable") },
                createWithDefaultPermissions = {},
            )

            strategy.createOwnerOnlyFile(tempFile.toPath().resolveSibling("acl-config.tmp"))

            observedAcl!!.single().principal() shouldBe owner
            observedAcl!!.single().type() shouldBe AclEntryType.ALLOW
            observedAcl!!.single().permissions() shouldBe AclEntryPermission.values().toSet()
        }

        "NIO permission strategy fails safely when no secure permission mechanism is available" {
            val strategy = NioConfigFilePermissionStrategy(
                attributeViews = object : ConfigFileAttributeViews {
                    override fun posix(path: Path): PosixFileAttributeView? = null

                    override fun acl(path: Path): AclFileAttributeView? = null

                    override fun owner(path: Path): UserPrincipal = error("owner lookup must not run")
                },
                createWithPosixPermissions = { throw UnsupportedOperationException("POSIX unavailable") },
                createWithDefaultPermissions = {},
            )

            val exception = shouldThrow<ConfigFileSecurityException> {
                strategy.createOwnerOnlyFile(tempFile.toPath().resolveSibling("unsupported-config.tmp"))
            }

            exception.message shouldNotContain "credential"
            exception.message shouldNotContain "secret"
        }

        "config permission failures never expose credential values" {
            val secretApiKey = "api-key-that-must-not-appear"
            val secretPrivateKey = "private-key-that-must-not-appear"
            val failingStrategy = object : ConfigFilePermissionStrategy {
                override fun createOwnerOnlyFile(path: Path): Unit = throw ConfigFileSecurityException()

                override fun enforceOwnerOnly(path: Path): Unit = throw ConfigFileSecurityException()
            }
            val service = ConfigServiceImpl(objectMapper, tempFile.absolutePath, failingStrategy)

            val exception = shouldThrow<RuntimeException> {
                service.updateConfig(
                    service.getConfig().copy(
                        kraken = KrakenCredentials(secretApiKey, secretPrivateKey),
                    ),
                )
            }

            exception.stackTraceToString() shouldNotContain secretApiKey
            exception.stackTraceToString() shouldNotContain secretPrivateKey
        }

        "updateConfig_PersistsUserChangedCredentials" {
            runTest {
                val oldConfig = configService.getConfig()
                val updated = oldConfig.copy(
                    kraken = KrakenCredentials("new-api-key", "new-private-key"),
                )

                configService.updateConfig(updated)

                val readBack = objectMapper.readValue(tempFile, AppConfig::class.java)
                readBack.kraken.apiKey.value shouldBe "new-api-key"
                readBack.kraken.privateKey.value shouldBe "new-private-key"
                configService.getConfig().kraken.apiKey.value shouldBe "new-api-key"
            }
        }

        "updateConfig preserves each unchanged env credential during partial rotation" {
            runTest {
                listOf("api", "private").forEach { rotatedField ->
                    writeRawConfig(apiKey = $$"${PATH:api-default}", privateKey = $$"${PATH:private-default}")
                    val service = ConfigServiceImpl(objectMapper, tempFile.absolutePath)
                    val current = service.getConfig()
                    val rotated =
                        if (rotatedField == "api") {
                            KrakenCredentials("new-api-key", current.kraken.privateKey.value)
                        } else {
                            KrakenCredentials(current.kraken.apiKey.value, "new-private-key")
                        }

                    service.updateConfig(current.copy(kraken = rotated))

                    val rawSaved = objectMapper.readValue(tempFile, AppConfig::class.java)
                    if (rotatedField == "api") {
                        rawSaved.kraken.apiKey.value shouldBe "new-api-key"
                        rawSaved.kraken.privateKey.value shouldBe $$"${PATH:private-default}"
                    } else {
                        rawSaved.kraken.apiKey.value shouldBe $$"${PATH:api-default}"
                        rawSaved.kraken.privateKey.value shouldBe "new-private-key"
                    }
                }
            }
        }

        "updateConfig preserves a staged credential rotation across a stale second update" {
            runTest {
                val apiPlaceholder = $$"${MISSING_API_KEY:api-default}"
                val privatePlaceholder = $$"${MISSING_PRIVATE_KEY:private-default}"
                writeRawConfig(apiKey = apiPlaceholder, privateKey = privatePlaceholder)
                val service = ConfigServiceImpl(objectMapper, tempFile.absolutePath)
                val activeConfig = service.getConfig()

                service.beginExecutionSession()
                service.updateConfig(
                    activeConfig.copy(
                        kraken = KrakenCredentials("rotated-api-key", activeConfig.kraken.privateKey.value),
                    ),
                )
                service.updateConfig(
                    activeConfig.copy(
                        settings = activeConfig.settings.copy(loopDelaySeconds = 61L),
                    ),
                )

                val rawSaved = objectMapper.readValue(tempFile, AppConfig::class.java)
                rawSaved.kraken.apiKey.value shouldBe "rotated-api-key"
                rawSaved.kraken.privateKey.value shouldBe privatePlaceholder
                service.endExecutionSession()
            }
        }

        "validateConfig_InvalidTotal" {
            runTest {
                assertAllocationsRejected(Allocation(Asset.USD, 90.0))
            }
        }

        "validateConfig_UsesSharedAllocationTolerance" {
            runTest {
                configService.updateConfig(
                    configService.getConfig().copy(
                        allocations = listOf(
                            Allocation(Asset.USD, 49.995),
                            Allocation(Asset.BTC, 50.0),
                        ),
                    ),
                )

                assertAllocationsRejected(
                    Allocation(Asset.USD, 49.989),
                    Allocation(Asset.BTC, 50.0),
                )
            }
        }

        "validateConfig_NoUSD" {
            runTest {
                assertAllocationsRejected(Allocation(Asset.BTC, 100.0))
            }
        }

        "validateConfig_DuplicateSymbols" {
            runTest {
                assertAllocationsRejected(
                    Allocation(Asset.BTC, 50.0),
                    Allocation(Asset.BTC.lowercase(), 50.0),
                )
            }
        }

        "validateConfig_NegativeTargetPercent" {
            runTest {
                assertAllocationsRejected(
                    Allocation(Asset.USD, 110.0),
                    Allocation(Asset.BTC, -10.0),
                )
            }
        }

        "validateConfig_EmptyAllocations" {
            runTest {
                assertAllocationsRejected()
            }
        }

        "validateConfig_BlankSymbol" {
            runTest {
                assertAllocationsRejected(
                    Allocation(Asset.USD, 50.0),
                    Allocation("  ", 50.0),
                )
            }
        }

        "validateConfig_InvalidSymbolPattern" {
            runTest {
                assertAllocationsRejected(
                    Allocation(Asset.USD, 50.0),
                    Allocation("BTC-USD", 50.0),
                )
            }
        }

        "validateConfig_RejectsNonFiniteAllocationTargets" {
            runTest {
                val originalConfig = configService.getConfig()
                val originalDiskContent = tempFile.readText()
                listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY).forEach { value ->
                    withClue("targetPercent=$value") {
                        shouldThrow<InvalidConfigurationException> {
                            configService.updateConfig(
                                originalConfig.copy(
                                    allocations = listOf(Allocation(Asset.USD, value)),
                                ),
                            )
                        }
                    }
                    configService.getConfig() shouldBe originalConfig
                    tempFile.readText() shouldBe originalDiskContent
                }
            }
        }

        "validateConfig_BadSettings" {
            runTest {
                val settings = configService.getConfig().settings
                assertSettingsRejected(
                    "loopDelaySeconds" to settings.copy(loopDelaySeconds = 0),
                    "deviationTriggerPercent" to settings.copy(deviationTriggerPercent = -1.0),
                    "minimumOrderSizeUSD" to settings.copy(minimumOrderSizeUSD = -1.0),
                    "minimumOrderSizeUSD below floor" to settings.copy(minimumOrderSizeUSD = 1.0),
                    "minimumOrderSizeUSD below floor 1.9" to settings.copy(minimumOrderSizeUSD = 1.9),
                    "minimum fiatMaxDrawdown" to settings.copy(fiatMaxDrawdown = -1.0),
                    "maximum fiatMaxDrawdown" to settings.copy(fiatMaxDrawdown = 101.0),
                    "fiatDeploymentExponent" to settings.copy(fiatDeploymentExponent = 0.0),
                    "fiatDeploymentExponent ceiling" to settings.copy(fiatDeploymentExponent = 101.0),
                    "deviationTriggerPercent ceiling" to settings.copy(deviationTriggerPercent = 101.0),
                )
            }
        }

        "saveConfig_Exception" {
            runTest {
                val mockMapper = mockk<ObjectMapper>(relaxed = true)
                val mockWriter = mockk<ObjectWriter>(relaxed = true)
                val validConfig =
                    TestFixtures.config(
                        kraken = KrakenCredentials("a", "b"),
                        settings = TestFixtures.settings(loopDelaySeconds = 1, deviationTriggerPercent = 1.0),
                        allocations = listOf(Allocation(Asset.USD, 100.0)),
                    )
                every { mockMapper.writerWithDefaultPrettyPrinter() } returns mockWriter
                every {
                    mockMapper.readValue(
                        any<String>(),
                        AppConfig::class.java,
                    )
                } returns validConfig
                every {
                    mockWriter.writeValue(
                        any<File>(),
                        any<Any>(),
                    )
                } throws IOException("Write error")

                configService = ConfigServiceImpl(
                    mockMapper,
                    tempFile.absolutePath,
                )

                shouldThrow<RuntimeException> {
                    configService.updateConfig(validConfig)
                }
            }
        }

        "updateConfig removes the temp file after a non-IO serialization failure" {
            runTest {
                val mockMapper = mockk<ObjectMapper>(relaxed = true)
                val mockWriter = mockk<ObjectWriter>(relaxed = true)
                val validConfig =
                    TestFixtures.config(
                        kraken = KrakenCredentials("a", "b"),
                        settings = TestFixtures.settings(loopDelaySeconds = 1, deviationTriggerPercent = 1.0),
                        allocations = listOf(Allocation(Asset.USD, 100.0)),
                    )
                every { mockMapper.writerWithDefaultPrettyPrinter() } returns mockWriter
                every {
                    mockMapper.readValue(
                        any<String>(),
                        AppConfig::class.java,
                    )
                } returns validConfig
                every {
                    mockWriter.writeValue(
                        any<File>(),
                        any<Any>(),
                    )
                } answers {
                    firstArg<File>().writeText("temporary credential material")
                    throw IllegalStateException("serialization failed")
                }

                configService = ConfigServiceImpl(mockMapper, tempFile.absolutePath)

                shouldThrow<IllegalStateException> {
                    configService.updateConfig(validConfig)
                }
                File("${tempFile.absolutePath}.tmp").exists() shouldBe false
            }
        }

        "writeConfigAtomically uses owner-only temp permissions before serialization on POSIX" {
            runTest {
                if (Files.getFileAttributeView(tempFile.toPath(), PosixFileAttributeView::class.java) != null) {
                    val mockMapper = mockk<ObjectMapper>(relaxed = true)
                    val mockWriter = mockk<ObjectWriter>(relaxed = true)
                    val validConfig =
                        TestFixtures.config(
                            kraken = KrakenCredentials("a", "b"),
                            settings = TestFixtures.settings(loopDelaySeconds = 1, deviationTriggerPercent = 1.0),
                            allocations = listOf(Allocation(Asset.USD, 100.0)),
                        )
                    var observedPermissions: Set<PosixFilePermission>? = null
                    every { mockMapper.writerWithDefaultPrettyPrinter() } returns mockWriter
                    every {
                        mockMapper.readValue(
                            any<String>(),
                            AppConfig::class.java,
                        )
                    } returns validConfig
                    every {
                        mockWriter.writeValue(
                            any<File>(),
                            any<Any>(),
                        )
                    } answers {
                        observedPermissions = Files.getPosixFilePermissions(firstArg<File>().toPath())
                        throw IOException("write failed")
                    }

                    configService = ConfigServiceImpl(mockMapper, tempFile.absolutePath)

                    shouldThrow<RuntimeException> {
                        configService.updateConfig(validConfig)
                    }
                    observedPermissions shouldBe ownerOnlyPermissions
                    File("${tempFile.absolutePath}.tmp").exists() shouldBe false
                }
            }
        }

        "updateConfig write failure leaves runtime disk and settings flow unchanged" {
            runTest {
                val originalDiskContent = tempFile.readText()
                val originalConfig = configService.getConfig()
                val mockMapper = mockk<ObjectMapper>(relaxed = true)
                val mockWriter = mockk<ObjectWriter>(relaxed = true)
                every { mockMapper.writerWithDefaultPrettyPrinter() } returns mockWriter
                every {
                    mockMapper.readValue(
                        any<String>(),
                        AppConfig::class.java,
                    )
                } returns originalConfig
                every {
                    mockWriter.writeValue(
                        any<File>(),
                        any<Any>(),
                    )
                } answers {
                    firstArg<File>().writeText("temporary credential material")
                    throw IOException("Write error")
                }

                val service = ConfigServiceImpl(mockMapper, tempFile.absolutePath)
                val settingsEvents = mutableListOf<Settings>()
                val collector = launch {
                    service.watchConfigChanges().collect { settingsEvents.add(it) }
                }
                advanceUntilIdle()

                shouldThrow<RuntimeException> {
                    service.updateConfig(
                        originalConfig.copy(
                            settings = originalConfig.settings.copy(dryRun = false),
                        ),
                    )
                }
                advanceUntilIdle()

                service.getConfig() shouldBe originalConfig
                tempFile.readText() shouldBe originalDiskContent
                File("${tempFile.absolutePath}.tmp").exists() shouldBe false
                settingsEvents shouldBe listOf(originalConfig.settings)
                collector.cancel()
            }
        }

        "updateConfig rejects every non-finite numeric setting without changing runtime or disk" {
            runTest {
                val originalConfig = configService.getConfig()
                val originalDiskContent = tempFile.readText()
                val nonFiniteValues = listOf(Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NaN)

                nonFiniteSettingMutations.forEach { (name, mutate) ->
                    nonFiniteValues.forEach { value ->
                        withClue("$name=$value") {
                            shouldThrow<InvalidConfigurationException> {
                                configService.updateConfig(
                                    originalConfig.copy(settings = mutate(originalConfig.settings, value)),
                                )
                            }
                        }
                        configService.getConfig() shouldBe originalConfig
                        tempFile.readText() shouldBe originalDiskContent
                    }
                }
            }
        }

        "loadConfig rejects every non-finite numeric setting and preserves the active config" {
            runTest {
                val originalConfig = configService.getConfig()
                val nonFiniteValues = listOf(Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NaN)

                nonFiniteSettingMutations.forEach { (name, mutate) ->
                    nonFiniteValues.forEach { value ->
                        withClue("$name=$value") {
                            objectMapper.writeValue(
                                tempFile,
                                originalConfig.copy(settings = mutate(originalConfig.settings, value)),
                            )
                            shouldThrow<InvalidConfigurationException> {
                                configService.loadConfig()
                            }
                        }
                        configService.getConfig() shouldBe originalConfig
                    }
                }
            }
        }

        "failed load does not publish rejected raw credentials into a later save" {
            runTest {
                val originalConfig = configService.getConfig()
                objectMapper.writeValue(
                    tempFile,
                    originalConfig.copy(
                        kraken = KrakenCredentials("rejected-key", "rejected-secret"),
                        settings = originalConfig.settings.copy(fiatDeploymentExponent = Double.POSITIVE_INFINITY),
                    ),
                )

                shouldThrow<InvalidConfigurationException> {
                    configService.loadConfig()
                }
                configService.updateConfig(
                    originalConfig.copy(
                        settings = originalConfig.settings.copy(
                            loopDelaySeconds =
                            originalConfig.settings.loopDelaySeconds + 1,
                        ),
                    ),
                )

                val savedConfig = objectMapper.readValue(tempFile, AppConfig::class.java)
                savedConfig.kraken shouldBe originalConfig.kraken
            }
        }

        "loadConfig_InvalidConfig" {
            val invalidConfig = TestFixtures.config(
                settings = TestFixtures.settings(loopDelaySeconds = 60L),
                allocations = listOf(
                    Allocation(
                        symbol = Asset.USD,
                        targetPercent = 90.0,
                    ),
                ),
            )
            objectMapper.writeValue(tempFile, invalidConfig)
            shouldThrow<InvalidConfigurationException> {
                ConfigServiceImpl(objectMapper, tempFile.absolutePath)
            }
        }

        "loadConfig_ResolveEnvVars" {
            writeRawConfig(
                apiKey = $$"${TEST_KRAKEN_API_KEY:default-api-key}",
                privateKey = $$"${TEST_KRAKEN_PRIVATE_KEY:default-private-key}",
            )

            val service = ConfigServiceImpl(objectMapper, tempFile.absolutePath)
            service.getConfig().kraken.apiKey.value shouldBe "default-api-key"
            service.getConfig().kraken.privateKey.value shouldBe "default-private-key"
        }

        "loadConfig_ResolveEnvVars_WithActualEnvValue" {
            val pathValue = System.getenv("PATH") ?: "fallback"
            writeRawConfig(apiKey = $$"${PATH:fallback-path}", privateKey = "some-private-key")

            val service = ConfigServiceImpl(objectMapper, tempFile.absolutePath)
            service.getConfig().kraken.apiKey.value shouldBe pathValue
        }

        "loadConfig_ResolveEnvVars_NoDefaultValue" {
            writeRawConfig(apiKey = $$"${NON_EXISTENT_VAR_NO_DEFAULT}", privateKey = "some-private-key")

            val service = ConfigServiceImpl(objectMapper, tempFile.absolutePath)
            service.getConfig().kraken.apiKey.value shouldBe ""
        }

        "loadConfig_ResolveEnvVars_BlankEnvVar" {
            mockkStatic(System::class)
            try {
                every { System.getenv("SOME_BLANK_VAR") } returns "  "

                writeRawConfig(apiKey = $$"${SOME_BLANK_VAR:default-val}", privateKey = "some-private-key")

                val service = ConfigServiceImpl(objectMapper, tempFile.absolutePath)
                service.getConfig().kraken.apiKey.value shouldBe "default-val"
            } finally {
                unmockkStatic(System::class)
            }
        }

        "watchConfigChanges emits current settings on subscribe and on update" {
            runTest {
                createValidConfig(tempFile)
                val service = ConfigServiceImpl(objectMapper, tempFile.absolutePath)
                val events = mutableListOf<Settings>()
                val job = launch {
                    service.watchConfigChanges().collect { events.add(it) }
                }
                advanceUntilIdle()
                events.size shouldBe 1
                events[0].dryRun shouldBe service.getConfig().settings.dryRun

                val updated = service.getConfig().copy(
                    settings = service.getConfig().settings.copy(dryRun = !service.getConfig().settings.dryRun),
                )
                service.updateConfig(updated)
                advanceUntilIdle()
                events.size shouldBe 2
                events[1].dryRun shouldBe updated.settings.dryRun

                job.cancel()
            }
        }

        "withExecutionSession executes block and closes session even when block throws exception" {
            runTest {
                createValidConfig(tempFile)
                val service = ConfigServiceImpl(objectMapper, tempFile.absolutePath)
                shouldThrow<IllegalStateException> {
                    service.withExecutionSession {
                        error("simulation thrown exception inside execution session")
                    }
                }
                // Verify execution session closed: updating config now immediately updates rather than pending
                val updated = service.getConfig().copy(
                    settings = service.getConfig().settings.copy(loopDelaySeconds = 120L),
                )
                service.updateConfig(updated)
                service.getConfig().settings.loopDelaySeconds shouldBe 120L
            }
        }
        "loadConfig renames legacy dustThresholdUSD to minimumOrderSizeUSD when only legacy exists" {
            tempFile.writeText(
                """
                {
                  "kraken": { "apiKey": "k", "privateKey": "s" },
                  "settings": {
                    "loopDelaySeconds": 60,
                    "deviationTriggerPercent": 2.0,
                    "dustThresholdUSD": 5.0,
                    "dryRun": true
                  },
                  "allocations": [{"symbol": "USD", "targetPercent": 100.0}]
                }
                """.trimIndent(),
            )

            val service = ConfigServiceImpl(objectMapper, tempFile.absolutePath)
            service.getConfig().settings.minimumOrderSizeUSD shouldBe 5.0
        }

        "loadConfig removes legacy dustThresholdUSD when minimumOrderSizeUSD already exists" {
            tempFile.writeText(
                """
                {
                  "kraken": { "apiKey": "k", "privateKey": "s" },
                  "settings": {
                    "loopDelaySeconds": 60,
                    "deviationTriggerPercent": 2.0,
                    "minimumOrderSizeUSD": 5.0,
                    "dustThresholdUSD": 3.0,
                    "dryRun": true
                  },
                  "allocations": [{"symbol": "USD", "targetPercent": 100.0}]
                }
                """.trimIndent(),
            )

            val service = ConfigServiceImpl(objectMapper, tempFile.absolutePath)
            service.getConfig().settings.minimumOrderSizeUSD shouldBe 5.0
        }

        "loadConfig leaves config unchanged when no legacy dustThresholdUSD key is present" {
            createValidConfig(tempFile)
            val originalContent = tempFile.readText()

            val service = ConfigServiceImpl(objectMapper, tempFile.absolutePath)
            service.getConfig().settings.minimumOrderSizeUSD shouldBe 5.0
            tempFile.readText() shouldBe originalContent
        }

        "endExecutionSession completes despite cancellation while configLock is held by updateConfig" {
            runTest {
                val lockHoldGate = CompletableDeferred<Unit>()
                val writerEntered = CompletableDeferred<Unit>()
                // Delegate real parsing so construction reads the on-disk config, but block the atomic
                // write so updateConfig holds configLock for the duration of the contention.
                val mapper = mockk<ObjectMapper>()
                val writer = mockk<ObjectWriter>()
                every { mapper.writerWithDefaultPrettyPrinter() } returns writer
                every { mapper.readTree(any<String>()) } answers { objectMapper.readTree(firstArg<String>()) }
                every { mapper.readValue(any<String>(), AppConfig::class.java) } answers {
                    objectMapper.readValue(firstArg<String>(), AppConfig::class.java)
                }
                every { mapper.writeValueAsString(any()) } answers {
                    objectMapper.writeValueAsString(firstArg<Any>())
                }
                coEvery { writer.writeValue(any<File>(), any()) } coAnswers {
                    writerEntered.complete(Unit)
                    lockHoldGate.await()
                }
                val service = ConfigServiceImpl(mapper, tempFile.absolutePath)
                val original = service.getConfig()
                val updated = original.copy(
                    settings = original.settings.copy(loopDelaySeconds = 999L),
                )

                // Open a session before contention so depth is already 1 (as a live rebalance would).
                service.beginExecutionSession()
                val blocker = launch {
                    service.updateConfig(updated)
                }
                writerEntered.await()

                // Cleanup contends with the lock held by blocker and is then cancelled, as a rebalance
                // worker cancelled mid-cycle would be.
                val sessionJob = launch {
                    service.endExecutionSession()
                }
                runCurrent()
                sessionJob.cancel()
                runCurrent()

                // While the lock is held, the staged config must not have published yet.
                service.getConfig().settings.loopDelaySeconds shouldBe 60L

                // Release the lock: with the NonCancellable cleanup guarantee the pending decrement must
                // still run, publishing the staged config instead of stranding depth above zero.
                lockHoldGate.complete(Unit)
                blocker.join()
                sessionJob.join()

                service.getConfig().settings.loopDelaySeconds shouldBe 999L

                // No permanent depth leak: a fresh begin/end session must not throw "No execution session".
                service.beginExecutionSession()
                service.endExecutionSession()
            }
        }

        "resolveEnvVars escapes JSON control characters in substituted env values" {
            mockkStatic(System::class)
            try {
                val cases = listOf(
                    "ESC_QUOTE" to "\"quoted\"",
                    "ESC_BACKSLASH" to "back\\slash",
                    "ESC_NEWLINE" to "line1\nline2",
                    "ESC_CR" to "line1\rline2",
                    "ESC_TAB" to "col1\tcol2",
                    "ESC_BACKSPACE" to "a\b",
                )
                cases.forEach { (key, value) ->
                    every { System.getenv(key) } returns value
                    writeRawConfig(apiKey = $$"${$$key}", privateKey = "static-private")
                    val service = ConfigServiceImpl(objectMapper, tempFile.absolutePath)
                    withClue(key) {
                        service.getConfig().kraken.apiKey.value shouldBe value
                    }
                }
            } finally {
                unmockkStatic(System::class)
            }
        }
    }
}
