package com.gemini.krakenbot.browserfixture

import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.gemini.krakenbot.config.ErrorHandlingConfig.configureErrorHandling
import com.gemini.krakenbot.config.configureCachingAndConditionalHeaders
import com.gemini.krakenbot.config.configureCompression
import com.gemini.krakenbot.config.configureSerialization
import com.gemini.krakenbot.service.history.HISTORY_ACCOUNT_DAILY_SCOPE
import com.gemini.krakenbot.service.history.HistoryDailyValueStatusSnapshot
import com.gemini.krakenbot.service.history.HistoryObservationValuator
import com.gemini.krakenbot.service.history.HistoryRangeQuery
import com.gemini.krakenbot.service.history.HistoryValuationJobState
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.routing.routing
import io.ktor.server.sse.SSE
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean

private const val LOOPBACK_HOST = "127.0.0.1"
private val fixturePort = System.getenv("HISTORY_BROWSER_FIXTURE_PORT")?.toIntOrNull()
    ?.takeIf { it in 1024..65535 }
    ?: 18080

object BrowserFixtureRunner {
    @JvmStatic
    fun main(args: Array<String>) {
        require(args.isEmpty()) { "The browser fixture runner does not accept production app arguments." }
        val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val fixture = runBlocking { BrowserFixtureEnvironment.create(applicationScope) }
        val accountDigest = checkNotNull(fixture.accountScope.accountIdentityDigest)
        val initialStatus = runBlocking {
            fixture.historyStore.queryDailyValueStatusSnapshot(accountDigest, HISTORY_ACCOUNT_DAILY_SCOPE)
        }
        val initialLedgerRevision = runBlocking { fixture.historyStore.currentLedgerRevision(accountDigest) }
        val stopped = CountDownLatch(1)
        val cleanupStarted = AtomicBoolean()
        fun cleanupFixture() {
            if (cleanupStarted.compareAndSet(false, true)) {
                applicationScope.cancel()
                fixture.close()
            }
        }
        var stopServer: (() -> Unit)? = null
        var shutdownHook: Thread? = null
        try {
            val server = embeddedServer(Netty, host = LOOPBACK_HOST, port = fixturePort) {
                install(SSE)
                configureCompression()
                configureCachingAndConditionalHeaders()
                // Same-origin browser requests need no CORS policy or environment-dependent override.
                configureErrorHandling(
                    jacksonObjectMapper()
                        .registerModule(JavaTimeModule())
                        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS),
                )
                configureSerialization()
                routing { fixture.controller.registerRoutes(this) }
            }
            stopServer = { server.stop(gracePeriodMillis = 1_000, timeoutMillis = 2_000) }
            val shutdown = Thread(
                {
                    try {
                        server.stop(gracePeriodMillis = 1_000, timeoutMillis = 2_000)
                    } finally {
                        try {
                            cleanupFixture()
                        } finally {
                            stopped.countDown()
                        }
                    }
                },
                "browser-fixture-shutdown",
            )
            shutdownHook = shutdown
            Runtime.getRuntime().addShutdownHook(shutdown)
            fixture.historyObservationRecorder.start(applicationScope)
            fixture.portfolioManager.startRebalancingLoop(applicationScope)
            fixture.historyValuationCoordinator.start(applicationScope)
            runBlocking {
                withTimeout(15_000) { fixture.portfolioState.snapshot.filterNotNull().first() }
                withTimeout(5_000) {
                    while (true) {
                        val intraday = fixture.historyStore.queryObservations(
                            HistoryRangeQuery(
                                from = Instant.now().minusSeconds(24 * 60 * 60),
                                until = Instant.now().plusSeconds(1),
                                sourceScope = HistoryObservationValuator.fullAccountScope(accountDigest),
                                limit = 1,
                            ),
                        )
                        if ((intraday.total ?: 0L) > 0) break
                        delay(10)
                    }
                }
            }
            server.start(wait = false)
            println("Browser fixture ready at http://$LOOPBACK_HOST:$fixturePort")
            println("Account fixture: $BROWSER_FIXTURE_ACCOUNT_IDENTITY (verified fake)")
            println(
                "Fake trade and ledger will publish out-of-band in " +
                    "${BROWSER_FIXTURE_LATE_EVENT_DELAY.seconds}s; automatic History scans run every " +
                    "${BROWSER_FIXTURE_PERIODIC_SYNC_INTERVAL.seconds}s.",
            )
            applicationScope.launch {
                delay(BROWSER_FIXTURE_LATE_EVENT_DELAY.toMillis())
                val eventAt = fixture.fixtureNow.atZone(ZoneOffset.UTC)
                    .toLocalDate()
                    .minusDays(1)
                    .atTime(23, 59, 59)
                    .toInstant(ZoneOffset.UTC)
                fixture.publishLateExchangeEvents(eventAt)
                val lateEventsPublishedAt = Instant.now()
                fixture.historySyncCoordinator.requestRecentRefresh()
                println(
                    "Out-of-band fake rows published at=$lateEventsPublishedAt: " +
                        "fixture-post-start-trade-${eventAt.epochSecond}, " +
                        "fixture-post-start-ledger-${eventAt.epochSecond - 1}-first, " +
                        "fixture-post-start-ledger-${eventAt.epochSecond}-second.",
                )
                val successful = waitForPublishedLateGeneration(
                    fixture,
                    accountDigest,
                    initialLedgerRevision + 2,
                    initialStatus.publishedGenerationId,
                )
                println(
                    "LATE_GENERATION_PUBLISHED sourceRevision=${successful.publishedSourceRevision} " +
                        "generation=${successful.publishedGenerationId} at=${Instant.now()}",
                )
                delay(BROWSER_FIXTURE_INCOMPLETE_EVENT_DELAY.toMillis())
                fixture.publishIncompleteEvidence(eventAt.minusSeconds(2))
                val incompleteEvidencePublishedAt = Instant.now()
                fixture.historySyncCoordinator.requestRecentRefresh()
                println(
                    "Out-of-band incomplete ledger evidence published at=$incompleteEvidencePublishedAt " +
                        "after the successful rebuild.",
                )
                val incomplete = waitForTerminalIncompleteGeneration(
                    fixture,
                    accountDigest,
                    checkNotNull(successful.publishedSourceRevision),
                )
                println(
                    "INCOMPLETE_REBUILD state=${incomplete.valuationJob?.state} " +
                        "publishedGeneration=${incomplete.publishedGenerationId} " +
                        "sourceRevision=${incomplete.valuationJob?.sourceRevision} at=${Instant.now()}",
                )
            }
            fixture.historySyncCoordinator.start(applicationScope)
            println("Press Ctrl+C to stop and remove the fixture identity directory.")
            stopped.await()
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
        } finally {
            runCatching { stopServer?.invoke() }
            shutdownHook?.let { runCatching { Runtime.getRuntime().removeShutdownHook(it) } }
            cleanupFixture()
        }
    }

    private suspend fun waitForPublishedLateGeneration(
        fixture: BrowserFixtureEnvironment,
        accountDigest: String,
        minimumSourceRevision: Long,
        previousGenerationId: String?,
    ): HistoryDailyValueStatusSnapshot = withTimeout(90_000) {
        while (true) {
            val status = fixture.historyStore.queryDailyValueStatusSnapshot(accountDigest, HISTORY_ACCOUNT_DAILY_SCOPE)
            if (status.valuationJob?.state == HistoryValuationJobState.COMPLETE &&
                status.publishedSourceRevision != null &&
                status.publishedSourceRevision >= minimumSourceRevision &&
                status.publishedGenerationId != previousGenerationId
            ) {
                return@withTimeout status
            }
            delay(50)
        }
        error("Unreachable")
    }

    private suspend fun waitForTerminalIncompleteGeneration(
        fixture: BrowserFixtureEnvironment,
        accountDigest: String,
        previousSourceRevision: Long,
    ): HistoryDailyValueStatusSnapshot = withTimeout(90_000) {
        while (true) {
            val status = fixture.historyStore.queryDailyValueStatusSnapshot(accountDigest, HISTORY_ACCOUNT_DAILY_SCOPE)
            val job = status.valuationJob
            if (job != null && job.sourceRevision > previousSourceRevision &&
                job.state in setOf(HistoryValuationJobState.COMPLETE, HistoryValuationJobState.NEEDS_ATTENTION)
            ) {
                return@withTimeout status
            }
            delay(50)
        }
        error("Unreachable")
    }
}
