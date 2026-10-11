package com.gemini.krakenbot

import com.fasterxml.jackson.databind.ObjectMapper
import com.gemini.krakenbot.config.APPLICATION_SCOPE_QUALIFIER
import com.gemini.krakenbot.config.appModule
import com.gemini.krakenbot.controller.DashboardController
import com.gemini.krakenbot.controller.dashboardRouting
import com.gemini.krakenbot.repository.LedgerRepository
import com.gemini.krakenbot.repository.OrderIntentRepository
import com.gemini.krakenbot.repository.TradeRepository
import com.gemini.krakenbot.service.ConfigService
import com.gemini.krakenbot.service.CurrentPortfolioState
import com.gemini.krakenbot.service.ExecutionAccountBindingVerifier
import com.gemini.krakenbot.service.KrakenService
import com.gemini.krakenbot.service.OrderExecutor
import com.gemini.krakenbot.service.OrderIntentService
import com.gemini.krakenbot.service.PortfolioAnalyzer
import com.gemini.krakenbot.service.PortfolioManager
import com.gemini.krakenbot.service.actual.ActualObservationDispatcher
import com.gemini.krakenbot.service.impl.history.AccountHistoryScopeGuard
import com.gemini.krakenbot.view.DashboardView
import com.gemini.krakenbot.view.component.ActualObservationsPageComponent
import com.gemini.krakenbot.view.component.DashboardShellComponent
import com.gemini.krakenbot.view.component.SettingsFormComponent
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.ktor.server.application.install
import io.ktor.server.sse.SSE
import io.ktor.server.testing.testApplication
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import org.jetbrains.exposed.v1.jdbc.Database
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.core.qualifier.named
import org.koin.dsl.module
import org.koin.test.KoinTest
import org.koin.test.get

class ProductionDiSmokeTest :
    StringSpec(),
    KoinTest {
    override fun isolationMode() = IsolationMode.InstancePerTest

    private fun startIsolatedApp(): Pair<String?, String?> {
        val previousDbPath = System.getProperty("kraken.db.path")
        val previousExecutionDbPath = System.getProperty("kraken.execution.db.path")
        System.setProperty("kraken.db.path", TestFixtures.MEMORY_)
        System.setProperty("kraken.execution.db.path", TestFixtures.MEMORY_)
        val config = mockk<ConfigService>(relaxed = true)
        every { config.getConfig() } returns TestFixtures.DEFAULT_TEST_CONFIG
        startKoin {
            allowOverride(true)
            modules(
                appModule,
                module { single<ConfigService> { config } },
            )
        }
        return previousDbPath to previousExecutionDbPath
    }

    private fun restoreDatabasePaths(paths: Pair<String?, String?>) {
        paths.first?.let { System.setProperty("kraken.db.path", it) }
            ?: System.clearProperty("kraken.db.path")
        paths.second?.let { System.setProperty("kraken.execution.db.path", it) }
            ?: System.clearProperty("kraken.execution.db.path")
    }

    init {
        "production wiring resolves current dashboard and account-safety services" {
            stopKoin()
            val previousPaths = startIsolatedApp()
            try {
                get<Database>().shouldNotBeNull()
                get<ObjectMapper>().shouldNotBeNull()
                get<ConfigService>().shouldNotBeNull()
                get<CoroutineScope>(named(APPLICATION_SCOPE_QUALIFIER)).shouldNotBeNull()

                get<TradeRepository>().shouldNotBeNull()
                get<LedgerRepository>().shouldNotBeNull()
                get<OrderIntentRepository>().shouldNotBeNull()
                get<AccountHistoryScopeGuard>().shouldNotBeNull()
                get<ExecutionAccountBindingVerifier>().shouldNotBeNull()

                get<KrakenService>().shouldNotBeNull()
                get<PortfolioAnalyzer>().shouldNotBeNull()
                get<OrderIntentService>().shouldNotBeNull()
                get<OrderExecutor>().shouldNotBeNull()
                get<PortfolioManager>().shouldNotBeNull()
                get<CurrentPortfolioState>().shouldNotBeNull()
                get<ActualObservationDispatcher>().shouldNotBeNull()

                get<DashboardController>().shouldNotBeNull()
                get<DashboardView>().shouldNotBeNull()
                get<DashboardShellComponent>().shouldNotBeNull()
                get<SettingsFormComponent>().shouldNotBeNull()
                get<ActualObservationsPageComponent>().shouldNotBeNull()
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
