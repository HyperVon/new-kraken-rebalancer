package com.gemini.krakenbot.di

import com.gemini.krakenbot.config.Allocation
import com.gemini.krakenbot.config.AppConfig
import com.gemini.krakenbot.config.KrakenCredentials
import com.gemini.krakenbot.config.Settings
import com.gemini.krakenbot.model.Asset
import org.koin.dsl.module
import org.slf4j.LoggerFactory
import java.io.File

class ConfigServiceExample(private val configFile: File) {
    private val log = LoggerFactory.getLogger(ConfigServiceExample::class.java)

    fun loadConfig(): AppConfig {
        log.info("Loading configuration from {}", configFile.path)
        // Field names mirror :common/config/Settings.kt. loopDelaySeconds,
        // deviationTriggerPercent, and dryRun carry no Kotlin default — they
        // must be present and finite in rebalancer-config.json; dryRun is never
        // defaulted by the parser (distinct from simulation, which is).
        val settings = Settings(
            loopDelaySeconds = 60L,
            deviationTriggerPercent = 5.0,
            dryRun = true,
            fiatMaxDrawdown = 0.0,
            fiatDeploymentExponent = 1.0,
            simulation = false,
        )
        val allocations = listOf(
            Allocation(symbol = Asset("XBT"), targetPercent = 70.0, color = "#f2a900"),
            Allocation(symbol = Asset("USD"), targetPercent = 30.0),
        )
        return AppConfig(
            kraken = KrakenCredentials(
                apiKey = "YOUR_KRAKEN_API_KEY",
                privateKey = "YOUR_KRAKEN_PRIVATE_KEY",
            ),
            settings = settings,
            allocations = allocations,
        )
    }
}

val exampleKoinModule = module {
    single { File("rebalancer-config.json") }
    single { ConfigServiceExample(get()) }
}
