package com.gemini.krakenbot

import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.gemini.krakenbot.controller.DashboardController
import com.gemini.krakenbot.controller.dashboardRouting
import com.gemini.krakenbot.service.ConfigService
import com.gemini.krakenbot.service.OrderIntentService
import com.gemini.krakenbot.service.PortfolioManager
import com.gemini.krakenbot.service.TradeHistoryService
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
import io.kotest.core.spec.Spec
import io.kotest.core.spec.style.StringSpec
import io.kotest.core.test.TestCase
import io.kotest.engine.test.TestResult
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.mockk.mockk
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import java.io.File
import io.ktor.server.sse.SSE as ServerSSE

internal fun evaluationTempPath(prefix: String): File = File.createTempFile("scenario-$prefix-", ".json").apply {
    deleteOnExit()
    delete()
}

class EvaluationScenariosTest : StringSpec() {
    // SingleInstance: the mocks and mapper below are shared by all 41 scenarios, so a scenario that
    // captures calls (snapshot actions, order lists) must build its own mock instead of reusing them.
    override fun isolationMode() = IsolationMode.SingleInstance

    internal val tradeHistoryService = mockk<TradeHistoryService>(relaxed = true)
    internal val configService = mockk<ConfigService>(relaxed = true)
    internal val portfolioManager = mockk<PortfolioManager>(relaxed = true)
    internal val orderIntentService = mockk<OrderIntentService>(relaxed = true)
    internal val objectMapper = jacksonObjectMapper().registerModule(JavaTimeModule())

    internal fun Application.configureTestEnv() {
        install(ServerSSE)
        dashboardRouting()
    }

    companion object {
        private val results = mutableMapOf<String, ScenarioResult>()
        private val registeredScenarios = mutableMapOf<String, String>()
        private const val FAIL = "FAIL"
        private val scenarioNamePattern = Regex("""^(Scenario \d+): (.+)$""")

        data class ScenarioResult(val name: String, val description: String, val status: String, val evidence: String)

        /** Rewrites the whole report after each scenario so an aborted run still leaves the results so far. */
        @Synchronized
        fun recordResult(name: String, description: String, status: String, evidence: String) {
            registeredScenarios[name] = description
            results[name] = ScenarioResult(name, description, status, evidence)
            writeReport()
        }

        @Synchronized
        private fun writeReport() {
            registeredScenarios.forEach { (name, description) ->
                if (name !in results) {
                    results[name] = ScenarioResult(
                        name = name,
                        description = description,
                        status = FAIL,
                        evidence = "Scenario completed without recording an outcome",
                    )
                }
            }
            check(results.keys == registeredScenarios.keys) {
                "Evaluation report outcome count does not match registered scenario count"
            }
            val reportPath =
                System.getenv("SCENARIOS_REPORT_PATH")
                    ?: System.getProperty("scenarios.report.path")
                    ?: "build/reports/scenarios_evaluation_report.md"
            val reportFile = File(reportPath)
            reportFile.parentFile?.mkdirs()
            val sb = StringBuilder()
            sb.append("# Scenarios Evaluation Report\n\n")
            sb.append(
                "This report lists the outcomes of ${registeredScenarios.size} registered realistic scenarios designed to evaluate the major capabilities of the Kraken Rebalancer.\n\n",
            )
            sb.append("Registered scenarios: ${registeredScenarios.size}; recorded outcomes: ${results.size}.\n\n")
            sb.append("## Evaluation Rubric & Status\n\n")
            sb.append("| Scenario | Description | Status | Details / Evidence |\n")
            sb.append("| :--- | :--- | :--- | :--- |\n")
            for ((name, description, status, evidence) in results.values.sortedBy {
                it.name.substringAfter(" ").toIntOrNull()
                    ?: 0
            }) {
                val statusStr = if (status == TestFixtures.PASS) "🟢 **PASS**" else "🔴 **FAIL**"
                sb.append("| $name | $description | $statusStr | ${sanitizeEvidence(evidence)} |\n")
            }
            sb.append("\n## Detailed Evidence for Each Scenario\n\n")
            for ((name, description, status, evidence) in results.values.sortedBy {
                it.name.substringAfter(" ").toIntOrNull()
                    ?: 0
            }) {
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

    override suspend fun afterTest(testCase: TestCase, result: TestResult) {
        val match = scenarioNamePattern.matchEntire(testCase.name.name) ?: return
        val name = match.groupValues[1]
        val description = match.groupValues[2]
        if (result.isErrorOrFailure) {
            val error = result.errorOrNull
            recordResult(
                name = name,
                description = description,
                status = FAIL,
                evidence = error?.let { "${it::class.simpleName}: ${it.message ?: "no message"}" }
                    ?: "Scenario failed without an exception",
            )
        } else {
            synchronized(EvaluationScenariosTest::class.java) {
                registeredScenarios[name] = description
            }
        }
    }

    override suspend fun afterSpec(spec: Spec) {
        synchronized(EvaluationScenariosTest::class.java) {
            writeReport()
        }
    }

    init {
        val testModule =
            module {
                single { tradeHistoryService }
                single { configService }
                single { portfolioManager }
                single { orderIntentService }
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
                single { DashboardController(get(), get(), get(), get(), get(), get()) }
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
        registerScenarios29To35()
        registerScenarios36To41()
    }
}
