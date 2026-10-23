package com.gemini.krakenbot.service.impl

import com.gemini.krakenbot.config.AppConfig
import com.gemini.krakenbot.domain.RawBalances
import com.gemini.krakenbot.domain.RebalancerEngine
import com.gemini.krakenbot.domain.toUsdScale
import com.gemini.krakenbot.model.Asset
import com.gemini.krakenbot.model.PortfolioSnapshot
import com.gemini.krakenbot.service.ConfigService
import com.gemini.krakenbot.service.CurrentCycleActivity
import com.gemini.krakenbot.service.CurrentCycleActivityMode
import com.gemini.krakenbot.service.CurrentCycleOutcome
import com.gemini.krakenbot.service.CurrentPortfolioState
import com.gemini.krakenbot.service.KrakenService
import com.gemini.krakenbot.service.OrderAttemptTracker
import com.gemini.krakenbot.service.OrderExecutionOutcome
import com.gemini.krakenbot.service.OrderExecutor
import com.gemini.krakenbot.service.PortfolioAnalyzer
import com.gemini.krakenbot.service.PortfolioManager
import com.gemini.krakenbot.service.RebalanceOperationalStatus
import com.gemini.krakenbot.service.withExecutionSession
import com.gemini.krakenbot.util.RebalanceEventFormatter
import com.gemini.krakenbot.view.util.RecentActivityText
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
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID
import kotlin.coroutines.coroutineContext
import kotlin.time.Duration.Companion.seconds

class PortfolioManagerImpl(
    private val configService: ConfigService,
    private val portfolioAnalyzer: PortfolioAnalyzer,
    private val orderExecutor: OrderExecutor,
    private val krakenService: KrakenService? = null,
    private val currentPortfolioState: CurrentPortfolioState = CurrentPortfolioState(),
) : PortfolioManager {
    private val log =
        LoggerFactory.getLogger(PortfolioManagerImpl::class.java)

    companion object {
        const val CYCLE_ID_MDC_KEY = "cycleId"
    }

    // The monitor covers synchronous start/stop Job ownership; the Mutex rejects duplicate coroutine callers.
    private val lifecycleLock = Any()
    private val runLoopMutex = Mutex()
    private val cycleAdmissionMutex = Mutex()

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

    @Volatile
    private var cycleActive = false

    override fun isCycleActive(): Boolean = cycleActive

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
        cycleAdmissionMutex.withLock {
            cycleActive = true
            try {
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
            } finally {
                cycleActive = false
            }
        }
    }

    internal suspend fun performRebalanceCycle(): PortfolioSnapshot? {
        currentCoroutineContext().ensureActive()
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
        )
        var activityDraft: CycleActivityDraft? = null
        try {
            val config = configService.getConfig()
            val draft = CycleActivityDraft(
                cycleId = cycleId,
                config = config,
                sourceScope = portfolioSourceScope(config, cycleId),
                actions = mutableListOf(),
            )
            activityDraft = draft
            val snapshot = performRebalanceCyclePinned(draft)
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
            val activity = draft.toActivity(snapshot?.timestamp ?: Instant.now())
            if (snapshot == null) {
                currentPortfolioState.publishActivity(activity, draft.sourceScope)
            } else {
                currentPortfolioState.publish(snapshot, draft.sourceScope, activity)
            }
            return snapshot
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            operationalStatus = operationalStatus.copy(
                lastCycleError = e::class.simpleName ?: "CycleFailure",
            )
            val draft = activityDraft ?: try {
                configService.getConfig().let { config ->
                    CycleActivityDraft(
                        cycleId = cycleId,
                        config = config,
                        sourceScope = portfolioSourceScope(config, cycleId),
                        actions = mutableListOf(),
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
            if (draft != null) {
                val errorText = e.message ?: e::class.simpleName ?: RecentActivityText.UNKNOWN_REASON
                draft.actions += RecentActivityText.CYCLE_FAILURE_PREFIX + errorText
                currentPortfolioState.publishActivity(
                    draft.toActivity(Instant.now(), CurrentCycleOutcome.PROBLEM),
                    draft.sourceScope,
                )
            }
            throw e
        }
    }

    private suspend fun <T> withCycleMdc(cycleId: String, block: suspend () -> T): T {
        val contextMap = (MDC.getCopyOfContextMap() ?: emptyMap()) + (CYCLE_ID_MDC_KEY to cycleId)
        return withContext(MDCContext(contextMap)) { block() }
    }

    private suspend fun performRebalanceCyclePinned(
        activityDraft: CycleActivityDraft,
    ): PortfolioSnapshot? {
        log.info("--- Starting Snapshot Phase ---")
        val config = activityDraft.config
        val actionLog = activityDraft.actions

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
                    actionLog += RecentActivityText.VALUATION_FAILURE_PREFIX +
                        (it.message ?: RecentActivityText.UNKNOWN_REASON)
                    return null
                },
            )

        log.info(
            "Total Portfolio Value: $${
                totalPortfolioValueUSD.toUsdScale()
            }",
        )

        val effectiveUsdTarget = RebalancerEngine.calculateConfiguredUsdTargetPercent(config.allocations)
        val cryptoScaleFactor = RebalancerEngine.calculateCryptoScaleFactor(effectiveUsdTarget, config.allocations)

        val plan = portfolioAnalyzer.analyzeDeviations(
            totalPortfolioValueUSD = totalPortfolioValueUSD,
            currentValuesUSD = currentValuesUSD,
            effectiveUsdTarget = effectiveUsdTarget,
            cryptoScaleFactor = cryptoScaleFactor,
        )
        val buyOrders = plan.buyOrders
        val sellOrders = plan.sellOrders
        activityDraft.hasOrders = buyOrders.isNotEmpty() || sellOrders.isNotEmpty()
        actionLog.addAll(plan.events.map(RebalanceEventFormatter::format))

        currentCoroutineContext().ensureActive()
        var executionAttemptedExchange = false
        var executionFailed = false
        val executionAttempts = OrderAttemptTracker()
        var executionOutcome: OrderExecutionOutcome? = null
        try {
            executionOutcome = orderExecutor.executeOrders(
                buyOrders = buyOrders,
                sellOrders = sellOrders,
                currentValuesUSD = currentValuesUSD,
                prices = prices,
                settings = config.settings,
                actionLog = actionLog,
                cycleId = cycleId,
                availableBalances = balances,
                attempts = executionAttempts,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.error("Order execution failed; continuing with a snapshot", e)
            markCycleError("Order execution failed")
            executionFailed = true
            actionLog.add(ViewText.ERROR_ORDER_EXECUTION_FAILED_PREFIX + (e.message ?: e.javaClass.simpleName))
        } finally {
            // The tracker outlives an exception thrown after a submission, preserving the actual
            // exchange-attempt outcome in the current-cycle activity feed.
            executionAttemptedExchange = executionOutcome?.exchangeOrderAttempted == true ||
                executionAttempts.exchangeOrderAttempted
        }

        val finalState =
            if (buyOrders.isNotEmpty() || sellOrders.isNotEmpty()) {
                try {
                    val postBalances = portfolioAnalyzer.fetchObservedBalances()
                    val postPrices = portfolioAnalyzer.fetchObservedPrices()
                    portfolioAnalyzer.calculatePortfolioValues(postBalances.balances, postPrices.prices).fold(
                        onSuccess = { (total, values) ->
                            RebalanceState(
                                postBalances.balances,
                                postPrices.prices,
                                values,
                                total,
                                postBalances.observedAt,
                            )
                        },
                        onFailure = {
                            markCycleError("Post-trade valuation failed")
                            actionLog += RecentActivityText.VALUATION_FAILURE_PREFIX +
                                (it.message ?: RecentActivityText.UNKNOWN_REASON)
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
                    actionLog += RecentActivityText.CYCLE_FAILURE_PREFIX +
                        (e.message ?: e::class.simpleName ?: RecentActivityText.UNKNOWN_REASON)
                    RebalanceState(balances, prices, currentValuesUSD, totalPortfolioValueUSD, preObservedAt)
                }
            } else {
                RebalanceState(balances, prices, currentValuesUSD, totalPortfolioValueUSD, preObservedAt)
            }

        val cycleOutcome = when {
            executionFailed || operationalStatus.lastCycleError != null -> CurrentCycleOutcome.PROBLEM
            config.settings.dryRun && activityDraft.hasOrders -> CurrentCycleOutcome.DRY_RUN_PLAN
            executionAttemptedExchange -> CurrentCycleOutcome.ORDERS_ATTEMPTED
            else -> CurrentCycleOutcome.NO_TRADE
        }
        activityDraft.outcome = cycleOutcome

        val snapshot =
            portfolioAnalyzer.buildSnapshot(
                balances = finalState.balances,
                prices = finalState.prices,
                currentValuesUSD = finalState.currentValuesUSD,
                totalPortfolioValueUSD = finalState.totalPortfolioValueUSD,
                effectiveUsdTarget = effectiveUsdTarget,
                cryptoScaleFactor = cryptoScaleFactor,
                actionLog = actionLog,
                balancesObservedAt = finalState.balancesObservedAt,
            )

        log.info("--- Cycle Complete ---")
        return snapshot
    }

    private fun markCycleError(error: String) {
        operationalStatus = operationalStatus.copy(lastCycleError = error)
    }

    private suspend fun portfolioSourceScope(config: AppConfig, cycleId: String): String {
        val source = if (config.settings.simulation) "simulation" else "live"
        val credentialScope = try {
            krakenService?.getFundingEvidenceScope()
                ?.trim()
                ?.takeIf { it.isNotBlank() && it != "scope-unavailable" }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("Could not determine portfolio source scope; resetting the hero trend: {}", e.message)
            null
        }
        return "$source:${credentialScope ?: cycleId}"
    }

    private fun CycleActivityDraft.toActivity(
        occurredAt: Instant,
        outcome: CurrentCycleOutcome = this.outcome ?: CurrentCycleOutcome.PROBLEM,
    ) = CurrentCycleActivity(
        cycleId = cycleId,
        occurredAt = occurredAt,
        mode = when {
            config.settings.simulation -> CurrentCycleActivityMode.SIMULATION
            config.settings.dryRun -> CurrentCycleActivityMode.DRY_RUN
            else -> CurrentCycleActivityMode.LIVE
        },
        outcome = outcome,
        actions = actions.toList(),
    )

    private data class CycleActivityDraft(
        val cycleId: String,
        val config: AppConfig,
        val sourceScope: String,
        val actions: MutableList<String>,
        var hasOrders: Boolean = false,
        var outcome: CurrentCycleOutcome? = null,
    )
}

private data class RebalanceState(
    val balances: RawBalances,
    val prices: Map<String, BigDecimal>,
    val currentValuesUSD: Map<String, BigDecimal>,
    val totalPortfolioValueUSD: BigDecimal,
    val balancesObservedAt: Instant,
)
