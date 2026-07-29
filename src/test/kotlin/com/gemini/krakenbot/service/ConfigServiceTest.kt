@file:OptIn(ExperimentalCoroutinesApi::class)

package com.gemini.krakenbot.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.ObjectWriter
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.gemini.krakenbot.TestFixtures
import com.gemini.krakenbot.config.*
import com.gemini.krakenbot.model.Asset
import com.gemini.krakenbot.service.impl.ConfigServiceImpl
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import java.io.File
import java.io.IOException
import java.nio.file.Files

class ConfigServiceTest : StringSpec() {
    override fun isolationMode() = IsolationMode.InstancePerTest

    private lateinit var configService: ConfigService
    private lateinit var objectMapper: ObjectMapper
    private lateinit var tempFile: File

    private val nonFiniteSettingMutations =
        listOf<Pair<String, (Settings, Double) -> Settings>>(
            "deviationTriggerPercent" to { settings, value -> settings.copy(deviationTriggerPercent = value) },
            "dustThresholdUSD" to { settings, value -> settings.copy(dustThresholdUSD = value) },
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

    private fun assertAllocationsRejected(vararg allocations: Allocation) {
        shouldThrow<InvalidConfigurationException> {
            configService.updateConfig(configService.getConfig().copy(allocations = allocations.toList()))
        }
    }

    private fun assertSettingsRejected(vararg invalidSettings: Pair<String, Settings>) {
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
                    "dustThresholdUSD": 1.0,
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
            configService.loadConfig()
            configService.getConfig().shouldNotBeNull()
            configService.getConfig().allocations.first().symbol.value shouldBe Asset.USD
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
        }

        "execution session defers both updates and file reloads until the session ends" {
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

        "loadConfig_AssignsMissingColors" {
            configService.loadConfig()
            val colors = configService.getConfig().allocations.map { it.color }
            colors.all { it != null }.shouldBeTrue()
            colors.first() shouldBe "#94a3b8"
        }

        "updateConfig_PersistsAssignedColors" {
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

        "updateConfig_RejectsInvalidColorByReassigning" {
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

        "updateConfig_DoesNotPersistEnvResolvedCredentials" {
            val secretFromEnv = System.getenv("PATH") ?: "fallback-path"
            writeRawConfig(
                apiKey = "\${PATH:fallback-path}",
                privateKey = "\${TEST_KRAKEN_PRIVATE_KEY:default-private-key}",
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

        "updateConfig_PersistsUserChangedCredentials" {
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

        "updateConfig preserves each unchanged env credential during partial rotation" {
            listOf("api", "private").forEach { rotatedField ->
                writeRawConfig(apiKey = "\${PATH:api-default}", privateKey = "\${PATH:private-default}")
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

        "validateConfig_InvalidTotal" {
            assertAllocationsRejected(Allocation(Asset.USD, 90.0))
        }

        "validateConfig_NoUSD" {
            assertAllocationsRejected(Allocation(Asset.BTC, 100.0))
        }

        "validateConfig_DuplicateSymbols" {
            assertAllocationsRejected(
                Allocation(Asset.BTC, 50.0),
                Allocation(Asset.BTC.lowercase(), 50.0),
            )
        }

        "validateConfig_NegativeTargetPercent" {
            assertAllocationsRejected(
                Allocation(Asset.USD, 110.0),
                Allocation(Asset.BTC, -10.0),
            )
        }

        "validateConfig_EmptyAllocations" {
            assertAllocationsRejected()
        }

        "validateConfig_BlankSymbol" {
            assertAllocationsRejected(
                Allocation(Asset.USD, 50.0),
                Allocation("  ", 50.0),
            )
        }

        "validateConfig_InvalidSymbolPattern" {
            assertAllocationsRejected(
                Allocation(Asset.USD, 50.0),
                Allocation("BTC-USD", 50.0),
            )
        }

        "validateConfig_BadSettings" {
            val settings = configService.getConfig().settings
            assertSettingsRejected(
                "loopDelaySeconds" to settings.copy(loopDelaySeconds = 0),
                "deviationTriggerPercent" to settings.copy(deviationTriggerPercent = -1.0),
                "dustThresholdUSD" to settings.copy(dustThresholdUSD = -1.0),
                "minimum fiatMaxDrawdown" to settings.copy(fiatMaxDrawdown = -1.0),
                "maximum fiatMaxDrawdown" to settings.copy(fiatMaxDrawdown = 101.0),
                "fiatDeploymentExponent" to settings.copy(fiatDeploymentExponent = 0.0),
            )
        }

        "saveConfig_Exception" {
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

        "loadConfig rejects every non-finite numeric setting and preserves the active config" {
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

        "failed load does not publish rejected raw credentials into a later save" {
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
                apiKey = "\${TEST_KRAKEN_API_KEY:default-api-key}",
                privateKey = "\${TEST_KRAKEN_PRIVATE_KEY:default-private-key}",
            )

            val service = ConfigServiceImpl(objectMapper, tempFile.absolutePath)
            service.getConfig().kraken.apiKey.value shouldBe "default-api-key"
            service.getConfig().kraken.privateKey.value shouldBe "default-private-key"
        }

        "loadConfig_ResolveEnvVars_WithActualEnvValue" {
            val pathValue = System.getenv("PATH") ?: "fallback"
            writeRawConfig(apiKey = "\${PATH:fallback-path}", privateKey = "some-private-key")

            val service = ConfigServiceImpl(objectMapper, tempFile.absolutePath)
            service.getConfig().kraken.apiKey.value shouldBe pathValue
        }

        "loadConfig_ResolveEnvVars_NoDefaultValue" {
            writeRawConfig(apiKey = "\${NON_EXISTENT_VAR_NO_DEFAULT}", privateKey = "some-private-key")

            val service = ConfigServiceImpl(objectMapper, tempFile.absolutePath)
            service.getConfig().kraken.apiKey.value shouldBe ""
        }

        "loadConfig_ResolveEnvVars_BlankEnvVar" {
            mockkStatic(System::class)
            every { System.getenv("SOME_BLANK_VAR") } returns "  "

            writeRawConfig(apiKey = "\${SOME_BLANK_VAR:default-val}", privateKey = "some-private-key")

            val service = ConfigServiceImpl(objectMapper, tempFile.absolutePath)
            service.getConfig().kraken.apiKey.value shouldBe "default-val"

            unmockkStatic(System::class)
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
    }
}
