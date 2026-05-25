package com.gemini.krakenbot

import com.gemini.krakenbot.config.appModule
import com.gemini.krakenbot.service.PortfolioManager
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.test.KoinTest
import org.koin.test.inject

class KrakenRebalancerApplicationTest : StringSpec(), KoinTest {
    init {
        "verify koin modules" {
            startKoin {
                modules(appModule)
            }
            val pm: PortfolioManager by inject()
            pm.shouldNotBeNull()
            stopKoin()
        }
    }
}
