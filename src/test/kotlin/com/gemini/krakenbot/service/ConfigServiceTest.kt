package com.gemini.krakenbot.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.ObjectWriter
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.gemini.krakenbot.config.*
import com.gemini.krakenbot.model.Asset
import com.gemini.krakenbot.service.impl.ConfigServiceImpl
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import java.io.File
import java.io.IOException
import java.nio.file.Files

class ConfigServiceTest : StringSpec() {

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
            fiatDeploymentExponent = 1.0
        )
        val config = AppConfig(
            kraken = KrakenCredentials("k", "s"),
            settings = settings,
            allocations = listOf(
                element = Allocation(
                    symbol = Asset.USD,
                    targetPercent = 100.0
                )
            )
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
                    missingFile.absolutePath
                )
            }
            ex.message!!.contains("not found").shouldBeTrue()
        }

        "updateConfig_Success" {
            configService.loadConfig()
            val oldConfig = configService.getConfig()
            val newConfig = AppConfig(
                oldConfig.kraken, oldConfig.settings,
                listOf(
                    Allocation(Asset.USD, 50.0),
                    Allocation(Asset.BTC, 50.0)
                )
            )

            configService.updateConfig(newConfig)

            configService.getConfig().allocations.size shouldBe 2
            val readBack = objectMapper.readValue(
                tempFile,
                AppConfig::class.java
            )
            readBack.allocations.size shouldBe 2
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
                        targetPercent = 90.0
                    )
                )
            )

            shouldThrow<InvalidConfigurationException> {
                configService.updateConfig(
                    invalidConfig
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
                        targetPercent = 100.0
                    )
                )
            )

            shouldThrow<InvalidConfigurationException> {
                configService.updateConfig(
                    invalidConfig
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
                        targetPercent = 50.0
                    ),
                    Allocation(
                        symbol = Asset.BTC.lowercase(),
                        targetPercent = 50.0
                    )
                )
            )

            shouldThrow<InvalidConfigurationException> {
                configService.updateConfig(
                    invalidConfig
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
                        targetPercent = 110.0
                    ),
                    Allocation(
                        symbol = Asset.BTC,
                        targetPercent = -10.0
                    )
                )
            )

            shouldThrow<InvalidConfigurationException> {
                configService.updateConfig(
                    invalidConfig
                )
            }
        }

        "validateConfig_EmptyAllocations" {
            configService.loadConfig()
            val oldConfig = configService.getConfig()
            val invalidConfig = AppConfig(
                kraken = oldConfig.kraken,
                settings = oldConfig.settings,
                allocations = emptyList()
            )

            shouldThrow<InvalidConfigurationException> {
                configService.updateConfig(
                    invalidConfig
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
                        targetPercent = 50.0
                    ), Allocation(
                        symbol = "  ",
                        targetPercent = 50.0
                    )
                )
            )

            shouldThrow<InvalidConfigurationException> {
                configService.updateConfig(
                    invalidConfig
                )
            }
        }

        "validateConfig_BadSettings" {
            configService.loadConfig()
            val oldConfig = configService.getConfig()
            val badLoopDelay = AppConfig(
                kraken = oldConfig.kraken,
                settings = oldConfig.settings.copy(loopDelaySeconds = 0),
                allocations = oldConfig.allocations
            )
            shouldThrow<InvalidConfigurationException> {
                configService.updateConfig(
                    badLoopDelay
                )
            }

            val badDev = AppConfig(
                kraken = oldConfig.kraken,
                settings = oldConfig.settings.copy(deviationTriggerPercent = -1.0),
                allocations = oldConfig.allocations
            )
            shouldThrow<InvalidConfigurationException> {
                configService.updateConfig(
                    badDev
                )
            }

            val badDust = AppConfig(
                kraken = oldConfig.kraken,
                settings = oldConfig.settings.copy(dustThresholdUSD = -1.0),
                allocations = oldConfig.allocations
            )
            shouldThrow<InvalidConfigurationException> {
                configService.updateConfig(
                    badDust
                )
            }

            val badFiatDrawdown1 = AppConfig(
                kraken = oldConfig.kraken,
                settings = oldConfig.settings.copy(fiatMaxDrawdown = -1.0),
                allocations = oldConfig.allocations
            )
            shouldThrow<InvalidConfigurationException> {
                configService.updateConfig(
                    badFiatDrawdown1
                )
            }

            val badFiatDrawdown2 = AppConfig(
                kraken = oldConfig.kraken,
                settings = oldConfig.settings.copy(fiatMaxDrawdown = 101.0),
                allocations = oldConfig.allocations
            )
            shouldThrow<InvalidConfigurationException> {
                configService.updateConfig(
                    badFiatDrawdown2
                )
            }

            val badFiatExp = AppConfig(
                kraken = oldConfig.kraken,
                settings = oldConfig.settings.copy(fiatDeploymentExponent = 0.0),
                allocations = oldConfig.allocations
            )
            shouldThrow<InvalidConfigurationException> {
                configService.updateConfig(
                    badFiatExp
                )
            }
        }

        "saveConfig_Exception" {
            val mockMapper = mockk<ObjectMapper>(relaxed = true)
            val mockWriter = mockk<ObjectWriter>(relaxed = true)
            every { mockMapper.writerWithDefaultPrettyPrinter() } returns mockWriter
            every {
                mockMapper.readValue(
                    any<File>(),
                    AppConfig::class.java
                )
            } returns AppConfig(
                kraken = KrakenCredentials("a", "b"),
                settings = Settings(
                    loopDelaySeconds = 1,
                    deviationTriggerPercent = 1.0,
                    dustThresholdUSD = 1.0,
                    dryRun = true,
                    fiatMaxDrawdown = 0.0,
                    fiatDeploymentExponent = 1.0
                ),
                allocations = listOf(
                    Allocation(
                        symbol = Asset.USD,
                        targetPercent = 100.0
                    )
                )
            )
            every {
                mockWriter.writeValue(
                    any<File>(),
                    any<Any>()
                )
            } throws IOException("Write error")

            configService = ConfigServiceImpl(
                mockMapper,
                tempFile.absolutePath
            )

            shouldThrow<RuntimeException> {
                configService.updateConfig(
                    AppConfig(
                        kraken = KrakenCredentials(
                            apiKey = "a",
                            privateKey = "b"
                        ),
                        settings = Settings(
                            loopDelaySeconds = 1,
                            deviationTriggerPercent = 1.0,
                            dustThresholdUSD = 1.0,
                            dryRun = true,
                            fiatMaxDrawdown = 0.0,
                            fiatDeploymentExponent = 1.0
                        ),
                        allocations = listOf(
                            Allocation(
                                symbol = Asset.USD,
                                targetPercent = 100.0
                            )
                        )
                    ))
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
                    fiatDeploymentExponent = 1.0
                ),
                allocations = listOf(
                    Allocation(
                        symbol = Asset.USD,
                        targetPercent = 90.0
                    )
                )
            )
            objectMapper.writeValue(tempFile, invalidConfig)
            shouldThrow<InvalidConfigurationException> {
                ConfigServiceImpl(objectMapper, tempFile.absolutePath)
            }
        }
    }
}
