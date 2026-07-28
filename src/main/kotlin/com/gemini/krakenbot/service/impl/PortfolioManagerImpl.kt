package com.gemini.krakenbot.service.impl

import com.gemini.krakenbot.model.PortfolioSnapshot
import com.gemini.krakenbot.service.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import java.io.IOException
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.*
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
    }

    @Volatile
    private var isRunning = false

    @Synchronized
    override fun stopRebalancingLoop() {
        this.isRunning = false
        log.info("Rebalancing loop stopped.")
    }

    @Synchronized
    override fun startRebalancingLoop() {
        this.isRunning = true
        log.info("Rebalancing loop started.")
    }

    override suspend fun runLoop() {
        try {
            log.info("Checking and performing historical trades synchronization from Kraken API...")
            tradeHistoryService.syncTradesFromKraken()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.error("Failed to synchronize historical trades on startup", e)
        }

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
                        try {
                            tradeHistoryService.syncTradesFromKraken()
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            log.error("Failed to synchronize historical trades during cycle", e)
                        }
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

    internal suspend fun performRebalanceCycle(): PortfolioSnapshot? {
        configService.beginExecutionSession()
        val cycleId = UUID.randomUUID().toString()
        MDC.put(CYCLE_ID_MDC_KEY, cycleId)
        try {
            val ks = krakenService
            return if (ks != null) {
                ks.withStableBackend { performRebalanceCyclePinned(cycleId) }
            } else {
                performRebalanceCyclePinned(cycleId)
            }
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
                totalPortfolioValueUSD.setScale(
                    2,
                    RoundingMode.HALF_UP,
                )
            }",
        )

        val drawdownPct =
            portfolioAnalyzer
                .updateAthAndCalculateDrawdown(totalPortfolioValueUSD)
        val fiatDeploymentPct =
            portfolioAnalyzer
                .calculateFiatDeployment(
                    drawdownPct,
                    config.settings,
                )

        if (fiatDeploymentPct > BigDecimal.ZERO) {
            log.info(
                "Drawdown Detected: {}%. Fiat Deployment: {}%",
                drawdownPct.setScale(2, RoundingMode.HALF_UP),
                fiatDeploymentPct.setScale(2, RoundingMode.HALF_UP),
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

        orderExecutor.executeOrders(
            buyOrders = buyOrders,
            sellOrders = sellOrders,
            currentValuesUSD = currentValuesUSD,
            prices = prices,
            settings = config.settings,
            actionLog = actionLog,
            cycleId = cycleId,
        )

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
            actionLog.add("ERROR: Failed to persist trade history: ${e.message}")
        }

        log.info("--- Cycle Complete ---")
        return snapshot
    }
}

private data class RebalanceState(
    val balances: RawBalances,
    val prices: Map<String, BigDecimal>,
    val currentValuesUSD: Map<String, BigDecimal>,
    val totalPortfolioValueUSD: BigDecimal,
)
