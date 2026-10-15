package com.gemini.krakenbot

import com.fasterxml.jackson.databind.ObjectMapper
import com.gemini.krakenbot.config.APPLICATION_SCOPE_QUALIFIER
import com.gemini.krakenbot.config.DataDirectoryConfig
import com.gemini.krakenbot.config.DataDirectoryIdentity
import com.gemini.krakenbot.config.ExecutionDatabase
import com.gemini.krakenbot.config.ExecutionJournalBootstrap
import com.gemini.krakenbot.config.appModule
import com.gemini.krakenbot.controller.DashboardController
import com.gemini.krakenbot.controller.dashboardRouting
import com.gemini.krakenbot.repository.ExecutionAccountBindingRepository
import com.gemini.krakenbot.repository.ExecutionOrderIntentRepository
import com.gemini.krakenbot.service.ConfigService
import com.gemini.krakenbot.service.CurrentPortfolioState
import com.gemini.krakenbot.service.ExecutionAccountBindingVerifier
import com.gemini.krakenbot.service.KrakenService
import com.gemini.krakenbot.service.OrderExecutor
import com.gemini.krakenbot.service.OrderIntentService
import com.gemini.krakenbot.service.PortfolioAnalyzer
import com.gemini.krakenbot.service.PortfolioManager
import com.gemini.krakenbot.service.history.HistoryDatabase
import com.gemini.krakenbot.service.history.HistoryObservationRecorder
import com.gemini.krakenbot.service.history.HistoryService
import com.gemini.krakenbot.service.history.HistoryStore
import com.gemini.krakenbot.service.history.HistorySynchronizer
import com.gemini.krakenbot.service.impl.ExecutionAccountBindingService
import com.gemini.krakenbot.view.DashboardView
import com.gemini.krakenbot.view.component.DashboardShellComponent
import com.gemini.krakenbot.view.component.HistoryPageComponent
import com.gemini.krakenbot.view.component.SettingsFormComponent
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.ktor.server.application.install
import io.ktor.server.sse.SSE
import io.ktor.server.testing.testApplication
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.core.qualifier.named
import org.koin.dsl.module
import org.koin.test.KoinTest
import org.koin.test.get
import java.nio.file.Files
import java.nio.file.Path

class ProductionDiSmokeTest :
    StringSpec(),
    KoinTest {
    override fun isolationMode() = IsolationMode.InstancePerTest

    private fun startIsolatedApp(): Pair<Path, String?> {
        val previousDataDir = System.getProperty(DataDirectoryConfig.DATA_DIR_PROPERTY)
        val tempDir = Files.createTempDirectory("rebalancer-di-smoke-")
        DataDirectoryConfig.initializeDataDirectory(tempDir)
        System.setProperty(DataDirectoryConfig.DATA_DIR_PROPERTY, tempDir.toString())
        val config = mockk<ConfigService>(relaxed = true)
        every { config.getConfig() } returns TestFixtures.DEFAULT_TEST_CONFIG
        startKoin {
            allowOverride(true)
            modules(
                appModule,
                module { single<ConfigService> { config } },
            )
        }
        return tempDir to previousDataDir
    }

    private fun restoreDatabasePaths(state: Pair<Path, String?>) {
        val (tempDir, previousDataDir) = state
        if (previousDataDir != null) {
            System.setProperty(DataDirectoryConfig.DATA_DIR_PROPERTY, previousDataDir)
        } else {
            System.clearProperty(DataDirectoryConfig.DATA_DIR_PROPERTY)
        }
        tempDir.toFile().deleteRecursively()
    }

    init {
        "production wiring resolves current dashboard and account-safety services" {
            stopKoin()
            val previousPaths = startIsolatedApp()
            try {
                get<DataDirectoryIdentity>().shouldNotBeNull()
                get<ExecutionDatabase>().shouldNotBeNull()
                get<HistoryDatabase>().shouldNotBeNull()
                get<ObjectMapper>().shouldNotBeNull()
                get<ConfigService>().shouldNotBeNull()
                get<CoroutineScope>(named(APPLICATION_SCOPE_QUALIFIER)).shouldNotBeNull()

                get<ExecutionOrderIntentRepository>().shouldNotBeNull()
                get<ExecutionAccountBindingRepository>().shouldNotBeNull()
                get<ExecutionAccountBindingService>().shouldNotBeNull()
                get<ExecutionAccountBindingVerifier>().shouldNotBeNull()
                get<ExecutionJournalBootstrap>().shouldNotBeNull()

                get<HistoryStore>().shouldNotBeNull()
                get<HistoryService>().shouldNotBeNull()
                get<HistoryObservationRecorder>().shouldNotBeNull()
                val synchronizer = get<HistorySynchronizer>()
                synchronizer.shouldNotBeNull()
                synchronizer.cycleGate.isCycleActive() shouldBe false

                com.gemini.krakenbot.config.coreModule.shouldNotBeNull()
                com.gemini.krakenbot.config.webModule.shouldNotBeNull()

                get<KrakenService>().shouldNotBeNull()
                get<PortfolioAnalyzer>().shouldNotBeNull()
                get<OrderIntentService>().shouldNotBeNull()
                get<OrderExecutor>().shouldNotBeNull()
                get<PortfolioManager>().shouldNotBeNull()
                get<CurrentPortfolioState>().shouldNotBeNull()

                get<DashboardController>().shouldNotBeNull()
                get<DashboardView>().shouldNotBeNull()
                get<DashboardShellComponent>().shouldNotBeNull()
                get<SettingsFormComponent>().shouldNotBeNull()
                get<HistoryPageComponent>().shouldNotBeNull()
            } finally {
                stopKoin()
                restoreDatabasePaths(previousPaths)
            }
        }

        "production dashboard routes install through dependency injection" {
            stopKoin()
            val previousPaths = startIsolatedApp()
            try {
                testApplication {
                    application {
                        this@application.install(SSE)
                        this@application.dashboardRouting()
                    }
                }
            } finally {
                stopKoin()
                restoreDatabasePaths(previousPaths)
            }
        }
    }
}
