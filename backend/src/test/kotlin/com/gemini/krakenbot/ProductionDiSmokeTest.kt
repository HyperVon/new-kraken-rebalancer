package com.gemini.krakenbot

import com.fasterxml.jackson.databind.ObjectMapper
import com.gemini.krakenbot.config.APPLICATION_SCOPE_QUALIFIER
import com.gemini.krakenbot.config.appModule
import com.gemini.krakenbot.controller.DashboardController
import com.gemini.krakenbot.controller.dashboardRouting
import com.gemini.krakenbot.repository.LedgerRepository
import com.gemini.krakenbot.repository.OrderIntentRepository
import com.gemini.krakenbot.repository.PortfolioStatsRepository
import com.gemini.krakenbot.repository.TradeRepository
import com.gemini.krakenbot.service.ConfigService
import com.gemini.krakenbot.service.KrakenService
import com.gemini.krakenbot.service.OrderExecutor
import com.gemini.krakenbot.service.OrderIntentService
import com.gemini.krakenbot.service.PortfolioAnalyzer
import com.gemini.krakenbot.service.PortfolioManager
import com.gemini.krakenbot.service.TradeHistoryService
import com.gemini.krakenbot.service.impl.history.AccountHistoryScopeGuard
import com.gemini.krakenbot.service.impl.history.InceptionDiscoveryService
import com.gemini.krakenbot.service.impl.history.InceptionRecoveryService
import com.gemini.krakenbot.service.impl.history.LedgersSyncService
import com.gemini.krakenbot.service.impl.history.TradeHistoryQueryService
import com.gemini.krakenbot.service.impl.history.TradeHistoryReconstructionService
import com.gemini.krakenbot.service.impl.history.TradeHistorySyncService
import com.gemini.krakenbot.view.DashboardView
import com.gemini.krakenbot.view.component.AllocationChartComponent
import com.gemini.krakenbot.view.component.DashboardFragmentComponent
import com.gemini.krakenbot.view.component.DashboardShellComponent
import com.gemini.krakenbot.view.component.HistoryPageComponent
import com.gemini.krakenbot.view.component.OverviewGridComponent
import com.gemini.krakenbot.view.component.PerformanceTableComponent
import com.gemini.krakenbot.view.component.RecentActivityComponent
import com.gemini.krakenbot.view.component.SettingsFormComponent
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.client.HttpClient
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.sse.SSE
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.CoroutineScope
import org.jetbrains.exposed.v1.jdbc.Database
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.core.qualifier.named
import org.koin.test.KoinTest
import org.koin.test.get
import java.io.File

class ProductionDiSmokeTest :
    StringSpec(),
    KoinTest {
    override fun isolationMode() = IsolationMode.InstancePerTest

    init {
        "production appModule resolves DashboardController and critical singletons" {
            val configFile = File("rebalancer-config.json")
            val existed = configFile.exists()
            if (!existed) {
                configFile.writeText(
                    """
                    {
                      "kraken": { "apiKey": "k", "privateKey": "s" },
                      "settings": {
                        "loopDelaySeconds": 60,
                        "deviationTriggerPercent": 2.0,
                        "minimumOrderSizeUSD": 5.0,
                        "dryRun": true,
                        "simulation": true,
                        "fiatMaxDrawdown": 0.0,
                        "fiatDeploymentExponent": 1.0
                      },
                      "allocations": [ { "symbol": "USD", "targetPercent": 100.0 } ]
                    }
                    """.trimIndent(),
                )
            }

            val previousDbPath = System.getProperty("kraken.db.path")
            System.setProperty("kraken.db.path", TestFixtures.MEMORY_)
            try {
                stopKoin()
                startKoin {
                    modules(appModule)
                }

                // Core infrastructure
                get<Database>().shouldNotBeNull()
                get<HttpClient>().shouldNotBeNull()
                get<ObjectMapper>().shouldNotBeNull()
                get<ConfigService>().shouldNotBeNull()
                get<CoroutineScope>(named(APPLICATION_SCOPE_QUALIFIER)).shouldNotBeNull()

                // Repositories
                get<TradeRepository>().shouldNotBeNull()
                get<OrderIntentRepository>().shouldNotBeNull()
                get<LedgerRepository>().shouldNotBeNull()
                get<PortfolioStatsRepository>().shouldNotBeNull()

                // History services
                get<InceptionDiscoveryService>().shouldNotBeNull()
                get<InceptionRecoveryService>().shouldNotBeNull()
                get<TradeHistoryQueryService>().shouldNotBeNull()
                get<AccountHistoryScopeGuard>().shouldNotBeNull()
                get<LedgersSyncService>().shouldNotBeNull()
                get<TradeHistoryReconstructionService>().shouldNotBeNull()
                get<TradeHistorySyncService>().shouldNotBeNull()
                get<TradeHistoryService>().shouldNotBeNull()

                // Trading and execution
                get<KrakenService>().shouldNotBeNull()
                get<PortfolioAnalyzer>().shouldNotBeNull()
                get<OrderIntentService>().shouldNotBeNull()
                get<OrderExecutor>().shouldNotBeNull()
                get<PortfolioManager>().shouldNotBeNull()

                // Web UI components
                get<DashboardShellComponent>().shouldNotBeNull()
                get<SettingsFormComponent>().shouldNotBeNull()
                get<OverviewGridComponent>().shouldNotBeNull()
                get<AllocationChartComponent>().shouldNotBeNull()
                get<PerformanceTableComponent>().shouldNotBeNull()
                get<RecentActivityComponent>().shouldNotBeNull()
                get<DashboardFragmentComponent>().shouldNotBeNull()
                get<HistoryPageComponent>().shouldNotBeNull()
                get<DashboardView>().shouldNotBeNull()

                // DashboardController must construct successfully without missing Function0/nowProvider
                val controller = get<DashboardController>()
                controller.shouldNotBeNull()
                controller.shouldBeInstanceOf<DashboardController>()
            } finally {
                stopKoin()
                if (previousDbPath != null) {
                    System.setProperty("kraken.db.path", previousDbPath)
                } else {
                    System.clearProperty("kraken.db.path")
                }
                if (!existed) {
                    configFile.delete()
                }
            }
        }

        "production Ktor dashboard routing installs successfully via DI" {
            val configFile = File("rebalancer-config.json")
            val existed = configFile.exists()
            if (!existed) {
                configFile.writeText(
                    """
                    {
                      "kraken": { "apiKey": "k", "privateKey": "s" },
                      "settings": {
                        "loopDelaySeconds": 60,
                        "deviationTriggerPercent": 2.0,
                        "minimumOrderSizeUSD": 5.0,
                        "dryRun": true,
                        "simulation": true,
                        "fiatMaxDrawdown": 0.0,
                        "fiatDeploymentExponent": 1.0
                      },
                      "allocations": [ { "symbol": "USD", "targetPercent": 100.0 } ]
                    }
                    """.trimIndent(),
                )
            }

            val previousDbPath = System.getProperty("kraken.db.path")
            System.setProperty("kraken.db.path", TestFixtures.MEMORY_)
            try {
                stopKoin()
                startKoin {
                    modules(appModule)
                }

                testApplication {
                    application {
                        this.install(SSE)
                        this.dashboardRouting()
                    }
                }
            } finally {
                stopKoin()
                if (previousDbPath != null) {
                    System.setProperty("kraken.db.path", previousDbPath)
                } else {
                    System.clearProperty("kraken.db.path")
                }
                if (!existed) {
                    configFile.delete()
                }
            }
        }
    }
}
