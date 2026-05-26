package com.gemini.krakenbot.service

import io.mockk.every
import io.mockk.mockk

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.databind.ObjectWriter
import com.gemini.krakenbot.config.Allocation
import com.gemini.krakenbot.config.AppConfig
import com.gemini.krakenbot.config.KrakenCredentials
import com.gemini.krakenbot.config.Settings
import com.gemini.krakenbot.service.impl.ConfigServiceImpl
import java.io.File
import java.io.IOException

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.assertions.throwables.shouldThrow
import java.nio.file.Files

class ConfigServiceTest : StringSpec({

    lateinit var configService: ConfigService
    lateinit var objectMapper: ObjectMapper
    lateinit var tempFile: File

    fun createValidConfig(file: File) {
        val settings = Settings(60L, 2.0, 1.0, true, 0.0, 1.0)
        val config = AppConfig(KrakenCredentials("k", "s"), settings, listOf(Allocation("USD", 100.0)))
        objectMapper.writeValue(file, config)
    }

    beforeTest {
        objectMapper = jacksonObjectMapper()
        tempFile = Files.createTempDirectory("test").resolve("test-config.json").toFile()
        createValidConfig(tempFile)
        configService = ConfigServiceImpl(objectMapper, tempFile.absolutePath)
    }

    "loadConfig_Success" {
        configService.loadConfig()
        configService.getConfig().shouldNotBeNull()
        configService.getConfig().allocations.first().symbol shouldBe "USD"
    }

    "loadConfig_FileNotFound" {
        val missingFile = File(tempFile.parent, "missing.json")
        val ex = shouldThrow<RuntimeException> { ConfigServiceImpl(objectMapper, missingFile.absolutePath) }
        ex.message!!.contains("not found").shouldBeTrue()
    }

    "updateConfig_Success" {
        configService.loadConfig()
        val oldConfig = configService.getConfig()
        val newConfig = AppConfig(
            oldConfig.kraken, oldConfig.settings,
            listOf(Allocation("USD", 50.0), Allocation("BTC", 50.0))
        )

        configService.updateConfig(newConfig)

        configService.getConfig().allocations.size shouldBe 2
        val readBack = objectMapper.readValue(tempFile, AppConfig::class.java)
        readBack.allocations.size shouldBe 2
    }

    "validateConfig_InvalidTotal" {
        configService.loadConfig()
        val oldConfig = configService.getConfig()
        val invalidConfig = AppConfig(
            oldConfig.kraken, oldConfig.settings,
            listOf(Allocation("USD", 90.0))
        )

        shouldThrow<RuntimeException> { configService.updateConfig(invalidConfig) }
    }

    "validateConfig_NoUSD" {
        configService.loadConfig()
        val oldConfig = configService.getConfig()
        val invalidConfig = AppConfig(
            oldConfig.kraken, oldConfig.settings,
            listOf(Allocation("BTC", 100.0))
        )

        shouldThrow<RuntimeException> { configService.updateConfig(invalidConfig) }
    }

    "validateConfig_BadSettings" {
        configService.loadConfig()
        val oldConfig = configService.getConfig()
        val badLoopDelay = AppConfig(oldConfig.kraken, oldConfig.settings.copy(loopDelaySeconds = 0), oldConfig.allocations)
        shouldThrow<RuntimeException> { configService.updateConfig(badLoopDelay) }
        
        val badDev = AppConfig(oldConfig.kraken, oldConfig.settings.copy(deviationTriggerPercent = -1.0), oldConfig.allocations)
        shouldThrow<RuntimeException> { configService.updateConfig(badDev) }

        val badDust = AppConfig(oldConfig.kraken, oldConfig.settings.copy(dustThresholdUSD = -1.0), oldConfig.allocations)
        shouldThrow<RuntimeException> { configService.updateConfig(badDust) }

        val badFiatDrawdown1 = AppConfig(oldConfig.kraken, oldConfig.settings.copy(fiatMaxDrawdown = -1.0), oldConfig.allocations)
        shouldThrow<RuntimeException> { configService.updateConfig(badFiatDrawdown1) }

        val badFiatDrawdown2 = AppConfig(oldConfig.kraken, oldConfig.settings.copy(fiatMaxDrawdown = 101.0), oldConfig.allocations)
        shouldThrow<RuntimeException> { configService.updateConfig(badFiatDrawdown2) }

        val badFiatExp = AppConfig(oldConfig.kraken, oldConfig.settings.copy(fiatDeploymentExponent = 0.0), oldConfig.allocations)
        shouldThrow<RuntimeException> { configService.updateConfig(badFiatExp) }
    }

    "saveConfig_Exception" {
        val mockMapper = mockk<ObjectMapper>(relaxed = true)
        val mockWriter = mockk<ObjectWriter>(relaxed = true)
        every { mockMapper.writerWithDefaultPrettyPrinter() } returns mockWriter
        every { mockMapper.readValue(any<File>(), AppConfig::class.java) } returns AppConfig(KrakenCredentials("a", "b"), Settings(1, 1.0, 1.0, true, 0.0, 1.0), listOf(Allocation("USD", 100.0)))
        every { mockWriter.writeValue(any<File>(), any<Any>()) } throws IOException("Write error")

        configService = ConfigServiceImpl(mockMapper, tempFile.absolutePath)

        shouldThrow<RuntimeException> { 
            configService.updateConfig(AppConfig(KrakenCredentials("a", "b"), Settings(1, 1.0, 1.0, true, 0.0, 1.0), listOf(Allocation("USD", 100.0)))) 
        }
    }
})
