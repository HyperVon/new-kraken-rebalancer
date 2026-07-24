package com.gemini.krakenbot

import com.gemini.krakenbot.config.appModule
import com.gemini.krakenbot.service.PortfolioManager
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.test.KoinTest
import org.koin.test.inject
import java.io.File

@Suppress("unused")
class KrakenRebalancerApplicationTest :
    StringSpec(),
    KoinTest {
    override fun isolationMode() = IsolationMode.InstancePerTest

    init {
        "verify koin modules" {
            val configFile = File("rebalancer-config.json")
            val existed = configFile.exists()
            if (!existed) {
                configFile.writeText(
                    """
                    {
                      "kraken": { "apiKey": "k", "privateKey": "s" },
                      "settings": { "loopDelaySeconds": 60, "deviationTriggerPercent": 2.0, "dustThresholdUSD": 1.0, "dryRun": true, "fiatMaxDrawdown": 0.0, "fiatDeploymentExponent": 1.0 },
                      "allocations": [ { "symbol": "USD", "targetPercent": 100.0 } ]
                    }
                    """.trimIndent(),
                )
            }

            try {
                stopKoin() // Ensure clean state
                startKoin {
                    modules(appModule)
                }
                val pm: PortfolioManager by inject()
                pm.shouldNotBeNull()
            } finally {
                stopKoin()
                if (!existed) {
                    configFile.delete()
                }
            }
        }
    }
}
