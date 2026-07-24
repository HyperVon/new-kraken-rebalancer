@file:OptIn(ExperimentalCoroutinesApi::class)

package com.gemini.krakenbot.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.ObjectWriter
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.gemini.krakenbot.config.*
import com.gemini.krakenbot.model.Asset
import com.gemini.krakenbot.service.impl.ConfigServiceImpl
import io.kotest.assertions.throwables.shouldThrow
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

    private fun createValidConfig(file: File) {
        val settings = Settings(
            loopDelaySeconds = 60L,
            deviationTriggerPercent = 2.0,
            dustThresholdUSD = 1.0,
            dryRun = true,
            fiatMaxDrawdown = 0.0,
            fiatDeploymentExponent = 1.0,
        )
        val config = AppConfig(
            kraken = KrakenCredentials("k", "s"),
            settings = settings,
            allocations = listOf(
                element = Allocation(
                    symbol = Asset.USD,
                    targetPercent = 100.0,
                ),
            ),
        )
        objectMapper.writeValue(file, config)
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
            configService.loadConfig()
            val oldConfig = configService.getConfig()
            val newConfig = AppConfig(
                oldConfig.kraken,
                oldConfig.settings,
                listOf(
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

        "updateConfig_DoesNotPersistEnvResolvedCredentials" {
            val secretFromEnv = System.getenv("PATH") ?: "fallback-path"
            val content = """
                {
                  "kraken": {
                    "apiKey": "${'$'}{PATH:fallback-path}",
                    "privateKey": "${'$'}{TEST_KRAKEN_PRIVATE_KEY:default-private-key}"
                  },
                  "settings": {
                    "loopDelaySeconds": 60,
                    "deviationTriggerPercent": 2.0,
                    "dustThresholdUSD": 1.0,
                    "dryRun": true,
                    "fiatMaxDrawdown": 0.0,
                    "fiatDeploymentExponent": 1.0
                  },
                  "allocations": [
                    {
                      "symbol": "USD",
                      "targetPercent": 100.0
                    }
                  ]
                }
            """.trimIndent()
            tempFile.writeText(content)

            val service = ConfigServiceImpl(objectMapper, tempFile.absolutePath)
            service.getConfig().kraken.apiKey.value shouldBe secretFromEnv

            val updated = service.getConfig().copy(
                settings = service.getConfig().settings.copy(dryRun = false),
            )
            service.updateConfig(updated)

            val savedContent = tempFile.readText()
            savedContent shouldContain "\${PATH:fallback-path}"
            savedContent shouldContain "\${TEST_KRAKEN_PRIVATE_KEY:default-private-key}"
            savedContent shouldNotContain secretFromEnv

            val reloaded = ConfigServiceImpl(objectMapper, tempFile.absolutePath)
            reloaded.getConfig().kraken.apiKey.value shouldBe secretFromEnv
            reloaded.getConfig().settings.dryRun shouldBe false
        }

        "updateConfig_PersistsUserChangedCredentials" {
            configService.loadConfig()
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

        "validateConfig_InvalidTotal" {
            configService.loadConfig()
            val oldConfig = configService.getConfig()
            val invalidConfig = AppConfig(
                kraken = oldConfig.kraken,
                settings = oldConfig.settings,
                allocations = listOf(
                    Allocation(
                        symbol = Asset.USD,
                        targetPercent = 90.0,
                    ),
                ),
            )

            shouldThrow<InvalidConfigurationException> {
                configService.updateConfig(
                    invalidConfig,
                )
            }
        }

        "validateConfig_NoUSD" {
            configService.loadConfig()
            val oldConfig = configService.getConfig()
            val invalidConfig = AppConfig(
                kraken = oldConfig.kraken,
                settings = oldConfig.settings,
                allocations = listOf(
                    Allocation(
                        symbol = Asset.BTC,
                        targetPercent = 100.0,
                    ),
                ),
            )

            shouldThrow<InvalidConfigurationException> {
                configService.updateConfig(
                    invalidConfig,
                )
            }
        }

        "validateConfig_DuplicateSymbols" {
            configService.loadConfig()
            val oldConfig = configService.getConfig()
            val invalidConfig = AppConfig(
                kraken = oldConfig.kraken,
                settings = oldConfig.settings,
                allocations = listOf(
                    Allocation(
                        symbol = Asset.BTC,
                        targetPercent = 50.0,
                    ),
                    Allocation(
                        symbol = Asset.BTC.lowercase(),
                        targetPercent = 50.0,
                    ),
                ),
            )

            shouldThrow<InvalidConfigurationException> {
                configService.updateConfig(
                    invalidConfig,
                )
            }
        }

        "validateConfig_NegativeTargetPercent" {
            configService.loadConfig()
            val oldConfig = configService.getConfig()
            val invalidConfig = AppConfig(
                kraken = oldConfig.kraken,
                settings = oldConfig.settings,
                allocations = listOf(
                    Allocation(
                        symbol = Asset.USD,
                        targetPercent = 110.0,
                    ),
                    Allocation(
                        symbol = Asset.BTC,
                        targetPercent = -10.0,
                    ),
                ),
            )

            shouldThrow<InvalidConfigurationException> {
                configService.updateConfig(
                    invalidConfig,
                )
            }
        }

        "validateConfig_EmptyAllocations" {
            configService.loadConfig()
            val oldConfig = configService.getConfig()
            val invalidConfig = AppConfig(
                kraken = oldConfig.kraken,
                settings = oldConfig.settings,
                allocations = emptyList(),
            )

            shouldThrow<InvalidConfigurationException> {
                configService.updateConfig(
                    invalidConfig,
                )
            }
        }

        "validateConfig_BlankSymbol" {
            configService.loadConfig()
            val oldConfig = configService.getConfig()
            val invalidConfig = AppConfig(
                kraken = oldConfig.kraken,
                settings = oldConfig.settings,
                allocations = listOf(
                    Allocation(
                        symbol = Asset.USD,
                        targetPercent = 50.0,
                    ),
                    Allocation(
                        symbol = "  ",
                        targetPercent = 50.0,
                    ),
                ),
            )

            shouldThrow<InvalidConfigurationException> {
                configService.updateConfig(
                    invalidConfig,
                )
            }
        }

        "validateConfig_BadSettings" {
            configService.loadConfig()
            val oldConfig = configService.getConfig()
            val badLoopDelay = AppConfig(
                kraken = oldConfig.kraken,
                settings = oldConfig.settings.copy(loopDelaySeconds = 0),
                allocations = oldConfig.allocations,
            )
            shouldThrow<InvalidConfigurationException> {
                configService.updateConfig(
                    badLoopDelay,
                )
            }

            val badDev = AppConfig(
                kraken = oldConfig.kraken,
                settings = oldConfig.settings.copy(deviationTriggerPercent = -1.0),
                allocations = oldConfig.allocations,
            )
            shouldThrow<InvalidConfigurationException> {
                configService.updateConfig(
                    badDev,
                )
            }

            val badDust = AppConfig(
                kraken = oldConfig.kraken,
                settings = oldConfig.settings.copy(dustThresholdUSD = -1.0),
                allocations = oldConfig.allocations,
            )
            shouldThrow<InvalidConfigurationException> {
                configService.updateConfig(
                    badDust,
                )
            }

            val badFiatDrawdown1 = AppConfig(
                kraken = oldConfig.kraken,
                settings = oldConfig.settings.copy(fiatMaxDrawdown = -1.0),
                allocations = oldConfig.allocations,
            )
            shouldThrow<InvalidConfigurationException> {
                configService.updateConfig(
                    badFiatDrawdown1,
                )
            }

            val badFiatDrawdown2 = AppConfig(
                kraken = oldConfig.kraken,
                settings = oldConfig.settings.copy(fiatMaxDrawdown = 101.0),
                allocations = oldConfig.allocations,
            )
            shouldThrow<InvalidConfigurationException> {
                configService.updateConfig(
                    badFiatDrawdown2,
                )
            }

            val badFiatExp = AppConfig(
                kraken = oldConfig.kraken,
                settings = oldConfig.settings.copy(fiatDeploymentExponent = 0.0),
                allocations = oldConfig.allocations,
            )
            shouldThrow<InvalidConfigurationException> {
                configService.updateConfig(
                    badFiatExp,
                )
            }
        }

        "saveConfig_Exception" {
            val mockMapper = mockk<ObjectMapper>(relaxed = true)
            val mockWriter = mockk<ObjectWriter>(relaxed = true)
            every { mockMapper.writerWithDefaultPrettyPrinter() } returns mockWriter
            every {
                mockMapper.readValue(
                    any<String>(),
                    AppConfig::class.java,
                )
            } returns AppConfig(
                kraken = KrakenCredentials("a", "b"),
                settings = Settings(
                    loopDelaySeconds = 1,
                    deviationTriggerPercent = 1.0,
                    dustThresholdUSD = 1.0,
                    dryRun = true,
                    fiatMaxDrawdown = 0.0,
                    fiatDeploymentExponent = 1.0,
                ),
                allocations = listOf(
                    Allocation(
                        symbol = Asset.USD,
                        targetPercent = 100.0,
                    ),
                ),
            )
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
                configService.updateConfig(
                    AppConfig(
                        kraken = KrakenCredentials(
                            apiKey = "a",
                            privateKey = "b",
                        ),
                        settings = Settings(
                            loopDelaySeconds = 1,
                            deviationTriggerPercent = 1.0,
                            dustThresholdUSD = 1.0,
                            dryRun = true,
                            fiatMaxDrawdown = 0.0,
                            fiatDeploymentExponent = 1.0,
                        ),
                        allocations = listOf(
                            Allocation(
                                symbol = Asset.USD,
                                targetPercent = 100.0,
                            ),
                        ),
                    ),
                )
            }
        }

        "loadConfig_InvalidConfig" {
            val invalidConfig = AppConfig(
                kraken = KrakenCredentials("k", "s"),
                settings = Settings(
                    loopDelaySeconds = 60L,
                    deviationTriggerPercent = 2.0,
                    dustThresholdUSD = 1.0,
                    dryRun = true,
                    fiatMaxDrawdown = 0.0,
                    fiatDeploymentExponent = 1.0,
                ),
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
            val content = """
                {
                  "kraken": {
                    "apiKey": "${'$'}{TEST_KRAKEN_API_KEY:default-api-key}",
                    "privateKey": "${'$'}{TEST_KRAKEN_PRIVATE_KEY:default-private-key}"
                  },
                  "settings": {
                    "loopDelaySeconds": 60,
                    "deviationTriggerPercent": 2.0,
                    "dustThresholdUSD": 1.0,
                    "dryRun": true,
                    "fiatMaxDrawdown": 0.0,
                    "fiatDeploymentExponent": 1.0
                  },
                  "allocations": [
                    {
                      "symbol": "USD",
                      "targetPercent": 100.0
                    }
                  ]
                }
            """.trimIndent()
            tempFile.writeText(content)

            val service = ConfigServiceImpl(objectMapper, tempFile.absolutePath)
            service.getConfig().kraken.apiKey.value shouldBe "default-api-key"
            service.getConfig().kraken.privateKey.value shouldBe "default-private-key"
        }

        "loadConfig_ResolveEnvVars_WithActualEnvValue" {
            val pathValue = System.getenv("PATH") ?: "fallback"
            val content = """
                {
                  "kraken": {
                    "apiKey": "${'$'}{PATH:fallback-path}",
                    "privateKey": "some-private-key"
                  },
                  "settings": {
                    "loopDelaySeconds": 60,
                    "deviationTriggerPercent": 2.0,
                    "dustThresholdUSD": 1.0,
                    "dryRun": true,
                    "fiatMaxDrawdown": 0.0,
                    "fiatDeploymentExponent": 1.0
                  },
                  "allocations": [
                    {
                      "symbol": "USD",
                      "targetPercent": 100.0
                    }
                  ]
                }
            """.trimIndent()
            tempFile.writeText(content)

            val service = ConfigServiceImpl(objectMapper, tempFile.absolutePath)
            service.getConfig().kraken.apiKey.value shouldBe pathValue
        }

        "loadConfig_ResolveEnvVars_NoDefaultValue" {
            val content = """
                {
                  "kraken": {
                    "apiKey": "${'$'}{NON_EXISTENT_VAR_NO_DEFAULT}",
                    "privateKey": "some-private-key"
                  },
                  "settings": {
                    "loopDelaySeconds": 60,
                    "deviationTriggerPercent": 2.0,
                    "dustThresholdUSD": 1.0,
                    "dryRun": true,
                    "fiatMaxDrawdown": 0.0,
                    "fiatDeploymentExponent": 1.0
                  },
                  "allocations": [
                    {
                      "symbol": "USD",
                      "targetPercent": 100.0
                    }
                  ]
                }
            """.trimIndent()
            tempFile.writeText(content)

            val service = ConfigServiceImpl(objectMapper, tempFile.absolutePath)
            service.getConfig().kraken.apiKey.value shouldBe ""
        }

        "loadConfig_ResolveEnvVars_BlankEnvVar" {
            mockkStatic(System::class)
            every { System.getenv("SOME_BLANK_VAR") } returns "  "

            val content = """
                {
                  "kraken": {
                    "apiKey": "${'$'}{SOME_BLANK_VAR:default-val}",
                    "privateKey": "some-private-key"
                  },
                  "settings": {
                    "loopDelaySeconds": 60,
                    "deviationTriggerPercent": 2.0,
                    "dustThresholdUSD": 1.0,
                    "dryRun": true,
                    "fiatMaxDrawdown": 0.0,
                    "fiatDeploymentExponent": 1.0
                  },
                  "allocations": [
                    {
                      "symbol": "USD",
                      "targetPercent": 100.0
                    }
                  ]
                }
            """.trimIndent()
            tempFile.writeText(content)

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
