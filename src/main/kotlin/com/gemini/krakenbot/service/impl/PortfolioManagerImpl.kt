package com.gemini.krakenbot.service.impl

import com.gemini.krakenbot.model.PortfolioSnapshot
import com.gemini.krakenbot.service.ConfigService
import com.gemini.krakenbot.service.KrakenService
import com.gemini.krakenbot.service.OrderExecutor
import com.gemini.krakenbot.service.PortfolioAnalyzer
import com.gemini.krakenbot.service.PortfolioManager
import com.gemini.krakenbot.service.RawBalances
import com.gemini.krakenbot.service.RebalanceOperationalStatus
import com.gemini.krakenbot.service.TradeHistoryService
import com.gemini.krakenbot.util.toPercentScale
import com.gemini.krakenbot.util.toUsdScale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
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
) : PortfolioManager {
    private val log =
        LoggerFactory.getLogger(PortfolioManagerImpl::class.java)

    companion object {
        const val CYCLE_ID_MDC_KEY = "cycleId"
        private const val ERROR_PERSIST_TRADE_HISTORY_PREFIX = "ERROR: Failed to persist trade history: "
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

    override fun stopRebalancingLoop() {
        synchronized(lifecycleLock) {
            isRunning = false
            isPaused = false
            workerJob?.cancel()
        }
        log.info("Rebalancing loop stopped.")
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

    override fun isLoopRunning(): Boolean = isRunning

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

            runLoopBody()
        } finally {
            synchronized(lifecycleLock) {
                if (workerJob === currentJob) {
                    workerJob = null
                }
            }
            runLoopMutex.unlock()
        }
    }

    private suspend fun runLoopBody() {
        synchronizeLedgers("on startup")
        synchronizeTrades("on startup")
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
                        synchronizeLedgers("during cycle")
                        synchronizeTrades("during cycle")
                        synchronizeHistoricalSnapshots("during cycle")
                        performRebalanceCycle()
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
        }
    }

    internal suspend fun performRebalanceCycle(): PortfolioSnapshot? {
        currentCoroutineContext().ensureActive()
        val startedAt = Instant.now()
        operationalStatus = operationalStatus.copy(
            lastCycleStartedAt = startedAt,
            lastCycleError = null,
        )
        configService.beginExecutionSession()
        val cycleId = UUID.randomUUID().toString()
        MDC.put(CYCLE_ID_MDC_KEY, cycleId)
        try {
            val ks = krakenService
            val snapshot = if (ks != null) {
                ks.withStableBackend { performRebalanceCyclePinned(cycleId) }
            } else {
                performRebalanceCyclePinned(cycleId)
            }
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
        } finally {
            MDC.remove(CYCLE_ID_MDC_KEY)
            configService.endExecutionSession()
        }
    }

    private suspend fun performRebalanceCyclePinned(cycleId: String): PortfolioSnapshot? {
        log.info("--- Starting Snapshot Phase ---")
        val config = configService.getConfig()
        val actionLog = mutableListOf<String>()

        val balances = portfolioAnalyzer.fetchBalances()
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

        val drawdownPct =
            portfolioAnalyzer
                .updateAthAndCalculateDrawdown(totalPortfolioValueUSD)
        val hasDeployableCryptoTarget =
            config.allocations.any { allocation ->
                !allocation.symbol.isUsd && allocation.targetPercent > 0.0
            }
        val fiatDeploymentPct =
            if (hasDeployableCryptoTarget) {
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

        val (buyOrders, sellOrders, cycleActions) =
            portfolioAnalyzer.analyzeDeviations(
                totalPortfolioValueUSD = totalPortfolioValueUSD,
                currentValuesUSD = currentValuesUSD,
                effectiveUsdTarget = effectiveUsdTarget,
                cryptoScaleFactor = cryptoScaleFactor,
            )
        actionLog.addAll(cycleActions)

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
            actionLog.add("ERROR: Order execution failed: ${e.message ?: e.javaClass.simpleName}")
        }

        val finalState =
            if (buyOrders.isNotEmpty() || sellOrders.isNotEmpty()) {
                try {
                    val postBalances = portfolioAnalyzer.fetchBalances()
                    val postPrices = portfolioAnalyzer.fetchPrices()
                    portfolioAnalyzer.calculatePortfolioValues(postBalances, postPrices).fold(
                        onSuccess = { (total, values) ->
                            RebalanceState(postBalances, postPrices, values, total)
                        },
                        onFailure = {
                            markCycleError("Post-trade valuation failed")
                            RebalanceState(balances, prices, currentValuesUSD, totalPortfolioValueUSD)
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
                    RebalanceState(balances, prices, currentValuesUSD, totalPortfolioValueUSD)
                }
            } else {
                RebalanceState(balances, prices, currentValuesUSD, totalPortfolioValueUSD)
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
            )

        try {
            tradeHistoryService.addSnapshot(snapshot)
        } catch (e: IOException) {
            log.error("Failed to persist trade history snapshot", e)
            markCycleError("Trade history persistence failed")
            actionLog.add("$ERROR_PERSIST_TRADE_HISTORY_PREFIX${e.message}")
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
)
