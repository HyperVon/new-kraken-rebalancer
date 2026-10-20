package com.gemini.krakenbot.browserfixture

import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.gemini.krakenbot.config.ErrorHandlingConfig.configureErrorHandling
import com.gemini.krakenbot.config.configureCachingAndConditionalHeaders
import com.gemini.krakenbot.config.configureCompression
import com.gemini.krakenbot.config.configureSerialization
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
import java.time.temporal.ChronoUnit
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean

private const val LOOPBACK_HOST = "127.0.0.1"
private const val FIXTURE_PORT = 18080

object BrowserFixtureRunner {
    @JvmStatic
    fun main(args: Array<String>) {
        require(args.isEmpty()) { "The browser fixture runner does not accept production app arguments." }
        val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val fixture = runBlocking { BrowserFixtureEnvironment.create(applicationScope) }
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
            val server = embeddedServer(Netty, host = LOOPBACK_HOST, port = FIXTURE_PORT) {
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
            fixture.portfolioManager.startRebalancingLoop(applicationScope)
            runBlocking {
                withTimeout(15_000) { fixture.portfolioState.snapshot.filterNotNull().first() }
            }
            server.start(wait = false)
            println("Browser fixture ready at http://$LOOPBACK_HOST:$FIXTURE_PORT")
            println("Account fixture: $BROWSER_FIXTURE_ACCOUNT_IDENTITY (verified fake)")
            println(
                "Fake trade and ledger will publish out-of-band in " +
                    "${BROWSER_FIXTURE_LATE_EVENT_DELAY.seconds}s; automatic History scans run every " +
                    "${BROWSER_FIXTURE_PERIODIC_SYNC_INTERVAL.seconds}s.",
            )
            applicationScope.launch {
                delay(BROWSER_FIXTURE_LATE_EVENT_DELAY.toMillis())
                val eventAt = fixture.fixtureNow.plus(BROWSER_FIXTURE_LATE_EVENT_DELAY).truncatedTo(ChronoUnit.SECONDS)
                fixture.publishLateExchangeEvents(eventAt)
                println(
                    "Out-of-band fake rows published: " +
                        "fixture-post-start-trade-${eventAt.epochSecond}, " +
                        "fixture-post-start-ledger-${eventAt.epochSecond}.",
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
}
