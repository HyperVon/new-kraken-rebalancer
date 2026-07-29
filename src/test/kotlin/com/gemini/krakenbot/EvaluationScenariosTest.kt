package com.gemini.krakenbot

import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.gemini.krakenbot.config.*
import com.gemini.krakenbot.controller.DashboardController
import com.gemini.krakenbot.controller.dashboardRouting
import com.gemini.krakenbot.model.Asset
import com.gemini.krakenbot.model.OrderResult
import com.gemini.krakenbot.model.PortfolioSnapshot
import com.gemini.krakenbot.model.PortfolioStats
import com.gemini.krakenbot.repository.PortfolioStatsRepository
import com.gemini.krakenbot.repository.impl.SqlitePortfolioStatsRepositoryImpl
import com.gemini.krakenbot.repository.impl.SqliteTradeRepositoryImpl
import com.gemini.krakenbot.service.ConfigService
import com.gemini.krakenbot.service.FakeKrakenService
import com.gemini.krakenbot.service.TradeHistoryService
import com.gemini.krakenbot.service.impl.ConfigServiceImpl
import com.gemini.krakenbot.service.impl.OrderExecutorImpl
import com.gemini.krakenbot.service.impl.PortfolioAnalyzerImpl
import com.gemini.krakenbot.service.impl.PortfolioManagerImpl
import com.gemini.krakenbot.view.DashboardView
import com.gemini.krakenbot.view.component.*
import com.gemini.krakenbot.view.util.FormFields
import com.gemini.krakenbot.view.util.HtmxHeaders
import com.gemini.krakenbot.view.util.Routes
import com.gemini.krakenbot.view.util.ViewText
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.comparables.shouldBeEqualComparingTo
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.plugins.sse.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.testing.*
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import java.io.File
import java.io.IOException
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import kotlin.math.abs
import kotlin.time.Duration.Companion.milliseconds
import io.ktor.client.plugins.sse.SSE as ClientSSE
import io.ktor.server.sse.SSE as ServerSSE

class EvaluationScenariosTest : StringSpec() {
    // SingleInstance: the mocks and mapper below are shared by all 34 scenarios, so a scenario that
    // captures calls (snapshot actions, order lists) must build its own mock instead of reusing them.
    override fun isolationMode() = IsolationMode.SingleInstance

    internal val tradeHistoryService = mockk<TradeHistoryService>(relaxed = true)
    internal val configService = mockk<ConfigService>(relaxed = true)
    internal val objectMapper = jacksonObjectMapper().registerModule(JavaTimeModule())

    internal fun Application.configureTestEnv() {
        install(ServerSSE)
        dashboardRouting()
    }

    companion object {
        private val results = mutableMapOf<String, ScenarioResult>()

        data class ScenarioResult(
            val name: String,
            val description: String,
            val status: String,
            val evidence: String,
        )

        /** Rewrites the whole report after each scenario so an aborted run still leaves the results so far. */
        @Synchronized
        fun recordResult(
            name: String,
            description: String,
            status: String,
            evidence: String,
        ) {
            results[name] = ScenarioResult(name, description, status, evidence)
            writeReport()
        }

        private fun writeReport() {
            val reportPath =
                System.getenv("SCENARIOS_REPORT_PATH")
                    ?: System.getProperty("scenarios.report.path")
                    ?: "build/reports/scenarios_evaluation_report.md"
            val reportFile = File(reportPath)
            reportFile.parentFile?.mkdirs()
            val sb = StringBuilder()
            sb.append("# Scenarios Evaluation Report\n\n")
            sb.append(
                "This report lists the outcomes of the 34 realistic scenarios designed to evaluate the major capabilities of the Kraken Rebalancer.\n\n",
            )
            sb.append("## Evaluation Rubric & Status\n\n")
            sb.append("| Scenario | Description | Status | Details / Evidence |\n")
            sb.append("| :--- | :--- | :--- | :--- |\n")
            for ((name, description, status, evidence) in results.values.sortedBy { it.name.substringAfter(" ").toIntOrNull() ?: 0 }) {
                val statusStr = if (status == TestFixtures.PASS) "🟢 **PASS**" else "🔴 **FAIL**"
                sb.append("| $name | $description | $statusStr | ${sanitizeEvidence(evidence)} |\n")
            }
            sb.append("\n## Detailed Evidence for Each Scenario\n\n")
            for ((name, description, status, evidence) in results.values.sortedBy { it.name.substringAfter(" ").toIntOrNull() ?: 0 }) {
                sb.append("### $name: $description\n\n")
                sb.append("**Status**: $status\n\n")
                sb.append("```text\n")
                sb.append(sanitizeEvidencePlain(evidence))
                sb.append("\n```\n\n")
            }
            reportFile.writeText(sb.toString())
        }

        /** Keep the Markdown report environment-agnostic for docs sync. */
        private fun sanitizeEvidencePlain(evidence: String): String {
            val projectRoot = File("").absoluteFile.canonicalPath.trimEnd('/', '\\') + File.separator
            return evidence
                .replace(projectRoot, ".../")
                .replace(Regex("""/var/folders/\S+/"""), ".../")
                .replace(Regex("""/tmp/\S+/"""), ".../")
                .replace(Regex("""scenario(\d+)-\d+\.json"""), "scenario$1-*.json")
        }

        private fun sanitizeEvidence(evidence: String): String = sanitizeEvidencePlain(evidence)
            .replace("\n", "<br>")
            .replace("|", "\\|")
    }

    init {
        val testModule =
            module {
                single { tradeHistoryService }
                single { configService }
                single { objectMapper }
                single { DashboardShellComponent() }
                single { SettingsFormComponent() }
                single { OverviewGridComponent() }
                single { AllocationChartComponent() }
                single { PerformanceTableComponent() }
                single { RecentActivityComponent() }
                single {
                    DashboardFragmentComponent(
                        overviewGridComponent = get(),
                        allocationChartComponent = get(),
                        performanceTableComponent = get(),
                        recentActivityComponent = get(),
                    )
                }
                single { HistoryPageComponent(get()) }
                single {
                    DashboardView(
                        shellComponent = get(),
                        settingsFormComponent = get(),
                        fragmentComponent = get(),
                        historyPageComponent = get(),
                    )
                }
                single { DashboardController(get(), get(), get(), get()) }
            }

        beforeTest {
            stopKoin()
            startKoin {
                modules(testModule)
            }
        }

        afterTest {
            stopKoin()
        }

        registerScenarios1To7()
        registerScenarios8To14()
        registerScenarios15To21()
        registerScenarios22To28()
        registerScenarios29To34()
    }
}
