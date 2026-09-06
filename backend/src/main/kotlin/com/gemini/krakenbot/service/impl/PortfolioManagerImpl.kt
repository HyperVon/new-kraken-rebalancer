package com.gemini.krakenbot.service.impl

import com.gemini.krakenbot.domain.RawBalances
import com.gemini.krakenbot.domain.toPercentScale
import com.gemini.krakenbot.domain.toUsdScale
import com.gemini.krakenbot.model.PortfolioSnapshot
import com.gemini.krakenbot.service.AthUpdateResult
import com.gemini.krakenbot.service.ConfigService
import com.gemini.krakenbot.service.KrakenService
import com.gemini.krakenbot.service.ObservedBalances
import com.gemini.krakenbot.service.OrderExecutor
import com.gemini.krakenbot.service.PortfolioAnalyzer
import com.gemini.krakenbot.service.PortfolioManager
import com.gemini.krakenbot.service.RebalanceOperationalStatus
import com.gemini.krakenbot.service.TradeHistoryService
import com.gemini.krakenbot.service.impl.history.InceptionDiscoveryService
import com.gemini.krakenbot.service.impl.history.InceptionRecoveryService
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
import java.io.IOException
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID
import kotlin.coroutines.coroutineContext
import kotlin.time.Duration.Companion.seconds

class PortfolioManagerImpl(
    private val configService: ConfigService,
    private val tradeHistoryService: TradeHistoryService,
    private val portfolioAnalyzer: PortfolioAnalyzer,
    private val orderExecutor: OrderExecutor,
    private val krakenService: KrakenService? = null,
    private val inceptionDiscoveryService: InceptionDiscoveryService? = null,
    private val inceptionRecoveryService: InceptionRecoveryService? = null,
) : PortfolioManager {
    private val log =
        LoggerFactory.getLogger(PortfolioManagerImpl::class.java)

    companion object {
        const val CYCLE_ID_MDC_KEY = "cycleId"
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
        // Startup syncs establish their own session/backend pin; they are not grouped under the
        // cycle-wide execution session/backend pin.
        // Inception resolves before snapshot reconstruction/pruning so
        // prune logic can honor the lifetime retention contract (never
        // prune at or after inception). Burst detection needs trades, so
        // ledgers + trades sync first.
        synchronizeLedgers("on startup")
        synchronizeTrades("on startup")
        recoverInception("on startup")
        resolveInception("on startup")
        synchronizeHistoricalSnapshots("on startup")

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
                        // One execution session + backend pin covers the in-cycle syncs and the
                        // rebalance so a settings save cannot make placement resolve a different
                        // backend than the trade/ledger sync that just ran.
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

    /**
     * Runs the in-cycle syncs and the rebalance under one execution session so
     * `ConfigService` does not publish a staged config between them, and pins a
     * single live/simulation backend for the whole sequence (nested
     * `withStableBackend` calls inside the syncs and executor reuse the pin).
     */
    private suspend fun performCycleWithStableSession() {
        val cycleId = UUID.randomUUID().toString()
        clearCycleSyncWarning()
        configService.withExecutionSession {
            val ks = krakenService
            if (ks != null) {
                ks.withStableBackend {
                    withCycleMdc(cycleId) {
                        // The balance observation must precede the ledger
                        // sync: the sync watermark is stamped at sync start,
                        // and the ATH coverage gate requires coverage at or
                        // after the observation. On throttled cycles the
                        // marker is intentionally older, so the analyzer
                        // fails closed until the next ledger refresh. The
                        // same observation drives the trade valuation below,
                        // so total and observedAt stay a consistent pair.
                        val athObservation = observeBalancesForAth()
                        synchronizeLedgers("during cycle")
                        synchronizeTrades("during cycle")
                        recoverInception("during cycle")
                        synchronizeHistoricalSnapshots("during cycle")
                        performRebalanceCycleForCycle(cycleId, athObservation)
                    }
                }
            } else {
                withCycleMdc(cycleId) {
                    synchronizeLedgers("during cycle")
                    synchronizeTrades("during cycle")
                    synchronizeHistoricalSnapshots("during cycle")
                    performRebalanceCycleForCycle(cycleId)
                }
            }
        }
    }

    private suspend fun observeBalancesForAth(): ObservedBalances? = try {
        portfolioAnalyzer.fetchObservedBalances()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.warn(
            "Pre-sync balance observation failed; the rebalance phase will re-fetch " +
                "(the ATH update may defer until the next cycle)",
            e,
        )
        null
    }

    private suspend fun synchronizeLedgers(context: String) {
        try {
            log.info(
                "Checking and performing ledger entry synchronization from Kraken API {}...",
                context,
            )
            tradeHistoryService.syncLedgersFromKraken()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.error("Failed to synchronize ledger entries {}", context, e)
            markCycleSyncWarning("Ledger synchronization $context", e)
        }
    }

    private suspend fun synchronizeTrades(context: String) {
        try {
            log.info(
                "Checking and performing historical trades synchronization from Kraken API {}...",
                context,
            )
            tradeHistoryService.syncTradesFromKraken()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.error("Failed to synchronize historical trades {}", context, e)
            markCycleSyncWarning("Trade synchronization $context", e)
        }
    }

    private suspend fun synchronizeHistoricalSnapshots(context: String) {
        try {
            log.info("Checking historical snapshot reconstruction {}...", context)
            tradeHistoryService.rebuildHistoricalSnapshotsIfNeeded()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.error("Failed to rebuild historical snapshots {}", context, e)
            markCycleSyncWarning("Historical snapshot reconstruction $context", e)
        }
    }

    private suspend fun resolveInception(context: String) {
        try {
            val resolved = inceptionDiscoveryService?.resolveInception()
            if (resolved != null) {
                log.info("Resolved portfolio inception {} at {}", context, resolved.inceptionTime)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.error("Failed to resolve portfolio inception {}", context, e)
        }
    }

    private suspend fun recoverInception(context: String) {
        try {
            val status = inceptionRecoveryService?.recoverOneBoundedRun()
            if (status != null) {
                log.info("Strategy inception recovery status {} {}", context, status.status)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.error("Failed to recover strategy inception {}", context, e)
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

    private suspend fun performRebalanceCycleForCycle(
        cycleId: String,
        athObservation: ObservedBalances? = null,
    ): PortfolioSnapshot? {
        val startedAt = Instant.now()
        operationalStatus = operationalStatus.copy(
            lastCycleStartedAt = startedAt,
            lastCycleError = null,
            lastAthDeferredReason = null,
        )
        try {
            val snapshot = performRebalanceCyclePinned(cycleId, athObservation)
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

    private fun markCycleSyncWarning(operation: String, error: Exception) {
        val detail = "$operation failed (${error::class.simpleName ?: "unknown"})"
        val existing = operationalStatus.lastCycleSyncWarning
        operationalStatus = operationalStatus.copy(
            lastCycleSyncWarning = if (existing.isNullOrBlank()) detail else "$existing; $detail",
        )
    }

    private suspend fun performRebalanceCyclePinned(
        cycleId: String,
        athObservation: ObservedBalances?,
    ): PortfolioSnapshot? {
        log.info("--- Starting Snapshot Phase ---")
        val config = configService.getConfig()
        val actionLog = mutableListOf<String>()

        val (balances, preObservedAt) = athObservation ?: portfolioAnalyzer.fetchObservedBalances()
        val prices = portfolioAnalyzer.fetchPrices()
        val calculationResult = portfolioAnalyzer.calculatePortfolioValues(balances, prices)

        val (totalPortfolioValueUSD, currentValuesUSD) =
            calculationResult.fold(
                onSuccess = { it },
                onFailure = {
                    log.error("Failed to calculate portfolio values: {}", it.message)
                    return null
                },
            )

        log.info(
            "Total Portfolio Value: $${
                totalPortfolioValueUSD.toUsdScale()
            }",
        )

        var athDeferred = false
        val drawdownPct =
            when (
                val athUpdate =
                    portfolioAnalyzer
                        .updateAthAndCalculateDrawdown(totalPortfolioValueUSD, BigDecimal.ZERO, preObservedAt)
            ) {
                is AthUpdateResult.Trusted -> athUpdate.drawdownPct

                // Fail closed: the balance may contain owner capital the
                // ledger window has not seen yet, so no drawdown derived from
                // it may drive fiat deployment. Keep showing the last trusted
                // drawdown and force deployment to zero below.
                is AthUpdateResult.Deferred -> {
                    athDeferred = true
                    operationalStatus = operationalStatus.copy(lastAthDeferredReason = athUpdate.reason)
                    log.warn(
                        "ATH state deferred (reason={}); preserving last trusted drawdown {}",
                        athUpdate.reason,
                        athUpdate.lastTrustedDrawdownPct,
                    )
                    actionLog.add(
                        "ATH update deferred (${athUpdate.reason}); fiat deployment disabled this cycle.",
                    )
                    athUpdate.lastTrustedDrawdownPct ?: BigDecimal.ZERO
                }
            }
        val hasDeployableCryptoTarget =
            config.allocations.any { allocation ->
                !allocation.symbol.isUsd && allocation.targetPercent > 0.0
            }
        val fiatDeploymentPct =
            if (athDeferred) {
                BigDecimal.ZERO
            } else if (hasDeployableCryptoTarget) {
                portfolioAnalyzer.calculateFiatDeployment(drawdownPct, config.settings)
            } else {
                BigDecimal.ZERO
            }

        if (fiatDeploymentPct > BigDecimal.ZERO) {
            log.info(
                "Drawdown Detected: {}%. Fiat Deployment: {}%",
                drawdownPct.toPercentScale(),
                fiatDeploymentPct.toPercentScale(),
            )
        }

        val effectiveUsdTarget =
            portfolioAnalyzer.calculateEffectiveUsdTarget(fiatDeploymentPct)
        val cryptoScaleFactor =
            portfolioAnalyzer.calculateCryptoScaleFactor(effectiveUsdTarget)

        val plan = portfolioAnalyzer.analyzeDeviations(
            totalPortfolioValueUSD = totalPortfolioValueUSD,
            currentValuesUSD = currentValuesUSD,
            effectiveUsdTarget = effectiveUsdTarget,
            cryptoScaleFactor = cryptoScaleFactor,
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

        try {
            tradeHistoryService.addSnapshot(snapshot)
        } catch (e: IOException) {
            log.error("Failed to persist trade history snapshot", e)
            markCycleError("Trade history persistence failed")
            actionLog.add(ViewText.ERROR_PERSIST_TRADE_HISTORY_PREFIX + e.message)
        }

        log.info("--- Cycle Complete ---")
        return snapshot
    }

    private fun markCycleError(error: String) {
        operationalStatus = operationalStatus.copy(lastCycleError = error)
    }
}

private data class RebalanceState(
    val balances: RawBalances,
    val prices: Map<String, BigDecimal>,
    val currentValuesUSD: Map<String, BigDecimal>,
    val totalPortfolioValueUSD: BigDecimal,
    val balancesObservedAt: Instant,
)
