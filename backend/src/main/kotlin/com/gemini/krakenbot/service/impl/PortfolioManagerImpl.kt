package com.gemini.krakenbot.service.impl

import com.gemini.krakenbot.config.Allocation
import com.gemini.krakenbot.domain.RawBalances
import com.gemini.krakenbot.domain.toUsdScale
import com.gemini.krakenbot.model.Asset
import com.gemini.krakenbot.model.PortfolioSnapshot
import com.gemini.krakenbot.service.ConfigService
import com.gemini.krakenbot.service.KrakenService
import com.gemini.krakenbot.service.OrderExecutor
import com.gemini.krakenbot.service.PortfolioAnalyzer
import com.gemini.krakenbot.service.PortfolioManager
import com.gemini.krakenbot.service.RebalanceOperationalStatus
import com.gemini.krakenbot.service.ReportingDispatcher
import com.gemini.krakenbot.service.actual.ActualObservationDispatcher
import com.gemini.krakenbot.service.withExecutionSession
import com.gemini.krakenbot.util.RebalanceEventFormatter
import com.gemini.krakenbot.view.util.ViewText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.slf4j.MDCContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.coroutineContext
import kotlin.time.Duration.Companion.seconds

class PortfolioManagerImpl(
    private val configService: ConfigService,
    private val portfolioAnalyzer: PortfolioAnalyzer,
    private val orderExecutor: OrderExecutor,
    private val krakenService: KrakenService? = null,
    private val reportingDispatcher: ReportingDispatcher? = null,
    private val actualObservationDispatcher: ActualObservationDispatcher? = null,
) : PortfolioManager {
    private val log =
        LoggerFactory.getLogger(PortfolioManagerImpl::class.java)

    companion object {
        const val CYCLE_ID_MDC_KEY = "cycleId"

        /** Days of completed daily closes that define "a recent high" for sell suppression. */
        const val RECENT_HIGH_LOOKBACK_DAYS = 20L

        /** TTL for caching completed daily recent-high closes to avoid redundant public OHLC polling. */
        const val RECENT_HIGH_CACHE_TTL_SECONDS = 3600L

        private const val SECONDS_PER_DAY = 86_400L
    }

    private val recentHighCache = ConcurrentHashMap<RecentHighCacheKey, Pair<Long, BigDecimal>>()

    /**
     * Recent-high cache scope. The trading mode selects the backend, and simulation has no OHLC
     * history at all, so a high resolved under one mode must not suppress or fail to suppress a
     * trade under the other for the rest of the TTL.
     */
    private data class RecentHighCacheKey(val pair: String, val simulation: Boolean)

    /**
     * Symbols currently trading at or above their highest completed close in the lookback
     * window. Selling an asset in that regime is where mean-reversion has historically lost to
     * trend continuation, so those overweight legs are held rather than trimmed.
     *
     * Fails open: any symbol whose history cannot be resolved is simply omitted, which leaves
     * the cycle trading exactly as it did before this rule existed.
     */
    internal suspend fun resolveAtRecentHigh(
        allocations: List<Allocation>,
        prices: Map<String, BigDecimal>,
        nowEpochSecond: Long,
        candidateSymbols: Set<String>? = null,
        simulation: Boolean,
    ): Set<String> {
        val backend = krakenService ?: return emptySet()
        if (candidateSymbols != null && candidateSymbols.isEmpty()) return emptySet()
        val currentDayStart = Math.floorDiv(nowEpochSecond, SECONDS_PER_DAY) * SECONDS_PER_DAY
        val since = currentDayStart - RECENT_HIGH_LOOKBACK_DAYS * SECONDS_PER_DAY
        val trending = mutableSetOf<String>()
        for (allocation in allocations) {
            val symbol = allocation.symbol
            if (symbol.isUsd) continue
            if (candidateSymbols != null && !candidateSymbols.contains(symbol.value)) continue
            val currentPrice = prices[symbol.value] ?: continue
            if (currentPrice.signum() <= 0) continue

            val cacheKey = RecentHighCacheKey(Asset.tradingPair(symbol.value), simulation)
            val cached = recentHighCache[cacheKey]
            val recentHigh = if (cached != null && nowEpochSecond < cached.first) {
                cached.second
            } else {
                val closes = try {
                    backend.getOHLC(pair = cacheKey.pair, interval = 1440, since = since)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.debug("Recent-high lookup failed for {}; trading it as before", symbol.value, e)
                    continue
                }
                val completedCloses = closes.filter { it.first >= since && it.first < currentDayStart }
                val high = completedCloses.maxOfOrNull { it.second } ?: continue
                if (high.signum() > 0) {
                    val expiresAt = minOf(
                        nowEpochSecond + RECENT_HIGH_CACHE_TTL_SECONDS,
                        currentDayStart + SECONDS_PER_DAY,
                    )
                    recentHighCache[cacheKey] = Pair(expiresAt, high)
                }
                high
            }

            if (recentHigh.signum() > 0 && currentPrice >= recentHigh) {
                trending.add(symbol.value)
            }
        }
        return trending
    }

    // The monitor covers synchronous start/stop Job ownership; the Mutex rejects duplicate coroutine callers.
    private val lifecycleLock = Any()
    private val runLoopMutex = Mutex()

    @Volatile
    private var isRunning = false

    @Volatile
    private var isPaused = false

    @Volatile
    private var workerJob: Job? = null

    @Volatile
    private var applicationScope: CoroutineScope? = null

    @Volatile
    private var operationalStatus = RebalanceOperationalStatus()

    override fun stopRebalancingLoop(): Job? {
        // Capture and cancel the current worker under the lock so callers (the shutdown hook) join
        // the worker actually cancelled here, not a stale startup worker left behind by pause/resume.
        val cancelled = synchronized(lifecycleLock) {
            isRunning = false
            isPaused = false
            workerJob.also { it?.cancel() }
        }
        log.info("Rebalancing loop stopped.")
        return cancelled
    }

    override fun startRebalancingLoop() {
        synchronized(lifecycleLock) {
            isRunning = true
            isPaused = false
        }
        log.info("Rebalancing loop started.")
    }

    override fun startRebalancingLoop(scope: CoroutineScope): Job {
        val job = synchronized(lifecycleLock) {
            applicationScope = scope
            isRunning = true
            isPaused = false
            val staleJob = workerJob
            if (staleJob != null && staleJob.isActive) {
                staleJob
            } else {
                // A cancelled worker is still draining until its finally block runs. The replacement
                // joins it before runLoop so it can never lose the admission race on runLoopMutex.
                scope.launch(start = CoroutineStart.LAZY) {
                    staleJob?.join()
                    runLoop()
                }.also { newJob ->
                    workerJob = newJob
                    newJob.start()
                }
            }
        }
        log.info("Rebalancing loop started.")
        return job
    }

    override fun isLoopPaused(): Boolean = isPaused

    override fun isLoopRunning(): Boolean = isRunning && workerJob?.isActive == true

    override fun getOperationalStatus(): RebalanceOperationalStatus = operationalStatus

    override fun pauseLoop() {
        synchronized(lifecycleLock) {
            isRunning = false
            isPaused = true
            workerJob?.cancel()
        }
        log.info("Rebalancing loop paused by operator.")
    }

    override fun resumeLoop() {
        val scope = applicationScope
            ?: throw IllegalStateException("Cannot resume without an active application scope")
        startRebalancingLoop(scope)
        log.info("Rebalancing loop resumed.")
    }

    override suspend fun runLoop() {
        if (!runLoopMutex.tryLock()) {
            log.warn("Rebalancing loop worker already exists; ignoring duplicate runLoop caller.")
            // The caller returned without becoming the worker. Only drop its ownership claim
            // when the owned job is already completed; an alive owned job is still draining and
            // will release workerJob from its own finally block.
            synchronized(lifecycleLock) {
                val owned = workerJob
                if (owned != null && owned === coroutineContext[Job] && owned.isCompleted) {
                    workerJob = null
                }
            }
            return
        }

        val currentJob = currentCoroutineContext()[Job]
        var cancellationObserved = false
        try {
            val admitted = synchronized(lifecycleLock) {
                if (!isRunning) {
                    false
                } else {
                    val existingJob = workerJob
                    if (existingJob != null && existingJob !== currentJob && existingJob.isActive) {
                        false
                    } else {
                        workerJob = currentJob
                        true
                    }
                }
            }
            if (!admitted) return

            try {
                runLoopBody()
            } catch (e: CancellationException) {
                cancellationObserved = true
                throw e
            }
        } finally {
            synchronized(lifecycleLock) {
                if (workerJob === currentJob) {
                    workerJob = null
                    if (applicationScope == null && (cancellationObserved || currentJob?.isCancelled == true)) {
                        isRunning = false
                    }
                }
            }
            runLoopMutex.unlock()
        }
    }

    private suspend fun runLoopBody() {
        try {
            // Hot SharedFlow + collectLatest: config changes restart an idle delay immediately.
            // During a rebalance, ConfigService defers publication until the execution session exits.
            configService.watchConfigChanges().collectLatest { settings ->
                while (isRunning) {
                    try {
                        log.info(
                            "Starting Rebalance Cycle. DryRun: {}",
                            settings.dryRun,
                        )
                        // One execution session + backend pin covers the entire order cycle.
                        performCycleWithStableSession()
                    } catch (e: CancellationException) {
                        // Cancellation drives collectLatest restarts and shutdown; never treat it
                        // as a cycle error, or a config change would leave the old loop running.
                        throw e
                    } catch (e: Exception) {
                        log.error("Error in rebalancing cycle", e)
                    }
                    delay(settings.loopDelaySeconds.seconds)
                }
            }
        } catch (e: CancellationException) {
            log.info("Rebalancing loop coroutine cancelled. Shutting down loop.")
            throw e
        }
    }

    /** Pins settings and exchange session for the complete rebalance cycle. */
    private suspend fun performCycleWithStableSession() {
        val cycleId = UUID.randomUUID().toString()
        clearCycleSyncWarning()
        configService.withExecutionSession {
            val ks = krakenService
            if (ks != null) {
                ks.withStableBackend {
                    withCycleMdc(cycleId) {
                        performRebalanceCycleForCycle(cycleId)
                    }
                }
            } else {
                withCycleMdc(cycleId) {
                    performRebalanceCycleForCycle(cycleId)
                }
            }
        }
    }

    internal suspend fun performRebalanceCycle(): PortfolioSnapshot? {
        currentCoroutineContext().ensureActive()
        clearCycleSyncWarning()
        val cycleId = UUID.randomUUID().toString()
        return withCycleMdc(cycleId) {
            performRebalanceCycleForCycle(cycleId)
        }
    }

    private suspend fun performRebalanceCycleForCycle(cycleId: String): PortfolioSnapshot? {
        val startedAt = Instant.now()
        operationalStatus = operationalStatus.copy(
            lastCycleStartedAt = startedAt,
            lastCycleError = null,
            lastAthDeferredReason = null,
        )
        try {
            val snapshot = performRebalanceCyclePinned(cycleId)
            if (snapshot == null) {
                operationalStatus = operationalStatus.copy(
                    lastCycleError = operationalStatus.lastCycleError ?: "Cycle produced no snapshot",
                )
            } else if (operationalStatus.lastCycleError == null) {
                operationalStatus = operationalStatus.copy(lastCycleCompletedAt = Instant.now())
            } else {
                log.warn(
                    "Rebalance cycle produced a snapshot with an operational error: {}",
                    operationalStatus.lastCycleError,
                )
            }
            return snapshot
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            operationalStatus = operationalStatus.copy(
                lastCycleError = e::class.simpleName ?: "CycleFailure",
            )
            throw e
        }
    }

    private suspend fun <T> withCycleMdc(cycleId: String, block: suspend () -> T): T {
        val contextMap = (MDC.getCopyOfContextMap() ?: emptyMap()) + (CYCLE_ID_MDC_KEY to cycleId)
        return withContext(MDCContext(contextMap)) { block() }
    }

    private fun clearCycleSyncWarning() {
        operationalStatus = operationalStatus.copy(lastCycleSyncWarning = null)
    }

    private suspend fun performRebalanceCyclePinned(cycleId: String): PortfolioSnapshot? {
        log.info("--- Starting Snapshot Phase ---")
        val config = configService.getConfig()
        if (config.settings.simulation) {
            reportingDispatcher?.initializeBeforeSimulationCycle()
        }
        val actionLog = mutableListOf<String>()

        val observedBalances = portfolioAnalyzer.fetchObservedBalances()
        val balances = observedBalances.balances
        val preObservedAt = observedBalances.observedAt
        val observedPrices = portfolioAnalyzer.fetchObservedPrices()
        val prices = observedPrices.prices
        val calculationResult = portfolioAnalyzer.calculatePortfolioValues(balances, prices)

        val (totalPortfolioValueUSD, currentValuesUSD) =
            calculationResult.fold(
                onSuccess = { it },
                onFailure = {
                    log.error("Failed to calculate portfolio values: {}", it.message)
                    enqueueActualObservation(cycleId, config, observedBalances, observedPrices)
                    return null
                },
            )

        log.info(
            "Total Portfolio Value: $${
                totalPortfolioValueUSD.toUsdScale()
            }",
        )

        val drawdownPct = BigDecimal.ZERO
        val fiatDeploymentPct = BigDecimal.ZERO
        val effectiveUsdTarget = portfolioAnalyzer.calculateEffectiveUsdTarget(BigDecimal.ZERO)
        val cryptoScaleFactor =
            portfolioAnalyzer.calculateCryptoScaleFactor(effectiveUsdTarget)

        val plan = portfolioAnalyzer.analyzeDeviations(
            totalPortfolioValueUSD = totalPortfolioValueUSD,
            currentValuesUSD = currentValuesUSD,
            effectiveUsdTarget = effectiveUsdTarget,
            cryptoScaleFactor = cryptoScaleFactor,
            trendingAssets = emptySet(),
        )
        val buyOrders = plan.buyOrders
        val sellOrders = plan.sellOrders
        actionLog.addAll(plan.events.map(RebalanceEventFormatter::format))

        currentCoroutineContext().ensureActive()
        try {
            orderExecutor.executeOrders(
                buyOrders = buyOrders,
                sellOrders = sellOrders,
                currentValuesUSD = currentValuesUSD,
                prices = prices,
                settings = config.settings,
                actionLog = actionLog,
                cycleId = cycleId,
                availableBalances = balances,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.error("Order execution failed; continuing with a snapshot", e)
            markCycleError("Order execution failed")
            actionLog.add(ViewText.ERROR_ORDER_EXECUTION_FAILED_PREFIX + (e.message ?: e.javaClass.simpleName))
        }

        enqueueActualObservation(cycleId, config, observedBalances, observedPrices)

        val finalState =
            if (buyOrders.isNotEmpty() || sellOrders.isNotEmpty()) {
                try {
                    val (postBalances, postObservedAt) = portfolioAnalyzer.fetchObservedBalances()
                    val postPrices = portfolioAnalyzer.fetchPrices()
                    portfolioAnalyzer.calculatePortfolioValues(postBalances, postPrices).fold(
                        onSuccess = { (total, values) ->
                            RebalanceState(postBalances, postPrices, values, total, postObservedAt)
                        },
                        onFailure = {
                            markCycleError("Post-trade valuation failed")
                            RebalanceState(balances, prices, currentValuesUSD, totalPortfolioValueUSD, preObservedAt)
                        },
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.warn(
                        "Failed to fetch post-trade balances/prices for snapshot, falling back to pre-trade values",
                        e,
                    )
                    markCycleError("Post-trade state refresh failed")
                    RebalanceState(balances, prices, currentValuesUSD, totalPortfolioValueUSD, preObservedAt)
                }
            } else {
                RebalanceState(balances, prices, currentValuesUSD, totalPortfolioValueUSD, preObservedAt)
            }

        val snapshot =
            portfolioAnalyzer.buildSnapshot(
                balances = finalState.balances,
                prices = finalState.prices,
                currentValuesUSD = finalState.currentValuesUSD,
                totalPortfolioValueUSD = finalState.totalPortfolioValueUSD,
                effectiveUsdTarget = effectiveUsdTarget,
                cryptoScaleFactor = cryptoScaleFactor,
                drawdownPct = drawdownPct,
                fiatDeploymentPct = fiatDeploymentPct,
                actionLog = actionLog,
                balancesObservedAt = finalState.balancesObservedAt,
            )

        if (config.settings.simulation) {
            reportingDispatcher?.persistSimulationSnapshot(snapshot)
        } else {
            reportingDispatcher?.enqueueSnapshot(snapshot)
        }

        log.info("--- Cycle Complete ---")
        return snapshot
    }

    private fun markCycleError(error: String) {
        operationalStatus = operationalStatus.copy(lastCycleError = error)
    }

    private suspend fun enqueueActualObservation(
        cycleId: String,
        config: com.gemini.krakenbot.config.AppConfig,
        balances: com.gemini.krakenbot.service.ObservedBalances,
        prices: com.gemini.krakenbot.service.ObservedPrices,
    ) {
        try {
            actualObservationDispatcher?.captureAfterCycle(cycleId, config, balances, prices)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("Actual observation was skipped without affecting the rebalance cycle: {}", e.message)
        }
    }
}

private data class RebalanceState(
    val balances: RawBalances,
    val prices: Map<String, BigDecimal>,
    val currentValuesUSD: Map<String, BigDecimal>,
    val totalPortfolioValueUSD: BigDecimal,
    val balancesObservedAt: Instant,
)
