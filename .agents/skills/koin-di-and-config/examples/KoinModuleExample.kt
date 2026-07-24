package com.gemini.krakenbot.di

import org.koin.dsl.module
import org.slf4j.LoggerFactory
import java.io.File

data class AppConfig(
    val rebalanceIntervalSeconds: Long = 300,
    val driftThresholdPercent: Double = 5.0
)

class ConfigServiceExample(private val configFile: File) {
    private val log = LoggerFactory.getLogger(ConfigServiceExample::class.java)

    fun loadConfig(): AppConfig {
        log.info("Loading configuration from {}", configFile.path)
        return AppConfig()
    }
}

val exampleKoinModule = module {
    single { File("rebalancer-config.json") }
    single { ConfigServiceExample(get()) }
}
