package com.gemini.krakenbot.service.impl.history

import com.gemini.krakenbot.config.AppConfig
import com.gemini.krakenbot.model.Asset
import com.gemini.krakenbot.model.KrakenApiConstants
import com.gemini.krakenbot.model.LedgerEvent
import com.gemini.krakenbot.model.SyncMetadataKeys
import com.gemini.krakenbot.repository.LedgerRepository
import com.gemini.krakenbot.repository.PortfolioStatsRepository
import com.gemini.krakenbot.repository.TradeRepository
import com.gemini.krakenbot.service.ConfigService
import com.gemini.krakenbot.service.KrakenService
import com.gemini.krakenbot.service.PortfolioAnalyzer
import com.gemini.krakenbot.service.withExecutionSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import org.slf4j.LoggerFactory
import java.math.BigDecimal
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.time.Duration.Companion.milliseconds

class TradeHistoryReconstructionService(
    private val repository: TradeRepository,
    private val ledgerRepository: LedgerRepository,
    private val krakenService: KrakenService,
    private val configService: ConfigService,
    private val portfolioAnalyzer: PortfolioAnalyzer,
    private val portfolioStatsRepository: PortfolioStatsRepository? = null,
    private val nowProvider: () -> Instant = Instant::now,
) {
    private val log = LoggerFactory.getLogger(TradeHistoryReconstructionService::class.java)

    companion object {
        const val CURRENT_RECONSTRUCTION_VERSION = "3"
    }

    suspend fun canRebuildSnapshots(): Boolean = ledgerRepository.isLedgersSeeded()

    suspend fun reconstructHistoricalSnapshots() = configService.withExecutionSession {
        val config = configService.getConfig()
        krakenService.withStableBackend { backend ->
            reconstructHistoricalSnapshots(config, backend, replaceExisting = false)
        }
    }

    suspend fun reconstructHistoricalSnapshots(config: AppConfig, backend: KrakenService) =
        reconstructHistoricalSnapshots(config, backend, replaceExisting = false)

    suspend fun rebuildHistoricalSnapshots() = configService.withExecutionSession {
        val config = configService.getConfig()
        krakenService.withStableBackend { backend ->
            rebuildHistoricalSnapshots(config, backend)
        }
    }

    suspend fun rebuildHistoricalSnapshots(config: AppConfig, backend: KrakenService) {
        check(ledgerRepository.isLedgersSeeded()) {
            "Cannot rebuild historical snapshots before ledger synchronization completes"
        }
        reconstructHistoricalSnapshots(config, backend, replaceExisting = true)
    }

    private suspend fun reconstructHistoricalSnapshots(
        config: AppConfig,
        backend: KrakenService,
        replaceExisting: Boolean,
    ) {
        log.info("Starting historical snapshots reconstruction...")
        val allocations = config.allocations
        val reconstructionNow = nowProvider()

        // load() is newest-first (DESC); lastOrNull() is the oldest retained snapshot.
        val currentSnapshots = if (replaceExisting) emptyList() else repository.load()
        val oldestSnapshot = currentSnapshots.lastOrNull()

        val cutoffTime = oldestSnapshot?.timestamp ?: reconstructionNow

        val fetchedLiveBalances =
            try {
                backend.getBalances()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.error("Failed to fetch balances for snapshot reconstruction", e)
                emptyMap()
            }

        if (oldestSnapshot == null && fetchedLiveBalances.isEmpty()) {
            log.warn("Aborting historical snapshot reconstruction: starting balances unavailable.")
            return
        }

        val runningBalances = mutableMapOf<String, BigDecimal>()
        val currentPrices = mutableMapOf<String, BigDecimal>()

        if (oldestSnapshot != null) {
            for ((symbol, asset) in oldestSnapshot.assets) {
                runningBalances[symbol] = asset.balance
                currentPrices[symbol] = asset.price
            }
        } else {
            for ((symbol) in allocations) {
                val symbolU = symbol.value.uppercase()
                val bal = portfolioAnalyzer.resolveBalance(symbolU, fetchedLiveBalances)
                runningBalances[symbolU] = bal
            }
            val pairsStr =
                allocations.filter { !it.symbol.isUsd }.joinToString(",") {
                    Asset.tradingPair(it.symbol.value)
                }
            val prices =
                try {
                    backend.getTickerPrices(pairsStr)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.error("Failed to fetch starting prices for snapshot reconstruction", e)
                    emptyMap()
                }
            for ((symbol) in allocations) {
                val symbolU = symbol.value.uppercase()
                currentPrices[symbolU] = portfolioAnalyzer.resolvePriceFromTicker(symbolU, prices)
            }
            currentPrices[Asset.USD] = BigDecimal.ONE
        }

        // Slightly wider than HISTORICAL_DAYS_BACK so daily closes cover the full reconstruction window.
        val ohlcData = mutableMapOf<String, List<Pair<Long, BigDecimal>>>()
        val since = reconstructionNow.minus(95, ChronoUnit.DAYS)
        val sinceSec = since.epochSecond
        for ((symbol) in allocations) {
            val symbolU = symbol.value.uppercase()
            if (symbolU == Asset.USD) continue
            val pair = Asset.tradingPair(symbolU)
            try {
                val prices = backend.getOHLC(pair, interval = 1440, since = sinceSec)
                ohlcData[symbolU] = prices
                log.info("Fetched {} OHLC close prices for {}", prices.size, symbolU)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.error("Failed to fetch OHLC prices for $symbolU ($pair)", e)
            }
            delay(200.milliseconds)
        }

        val trades =
            repository
                .getTradesInRange(since, reconstructionNow)
                .filter { it.success && !it.dryRun }

        val tradePrices =
            trades
                .groupBy { it.symbol.uppercase() }
                .mapValues { entry ->
                    entry.value.map { Pair(it.timestamp, it.price) }
                }

        val historicalTrades = trades.filter { it.timestamp.isBefore(cutoffTime) }

        val stakingRewards =
            ledgerRepository
                .getLedgersInRange(since, reconstructionNow)
                .filter { it.type == KrakenApiConstants.LEDGER_TYPE_STAKING }
        val historicalRewards = stakingRewards.filter { it.time.isBefore(cutoffTime) }

        val events =
            SnapshotHistoryCalculator.buildTimelineEvents(
                historicalTrades = historicalTrades,
                historicalRewards = historicalRewards,
                cutoffTime = cutoffTime,
                now = reconstructionNow,
            )

        val settings = config.settings
        val currentAth =
            try {
                portfolioStatsRepository?.load()?.allTimeHigh ?: BigDecimal.ZERO
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn("Failed to load allTimeHigh for snapshot reconstruction; defaulting to zero ATH", e)
                BigDecimal.ZERO
            }

        val snapshotsToSave =
            SnapshotHistoryCalculator.calculateHistoricalSnapshots(
                events = events,
                allocations = allocations,
                runningBalances = runningBalances,
                currentPrices = currentPrices,
                ohlcData = ohlcData,
                tradePrices = tradePrices,
                settings = settings,
                currentAth = currentAth,
            )

        if (snapshotsToSave.isNotEmpty()) {
            log.info("Saving {} reconstructed historical snapshots...", snapshotsToSave.size)
            if (replaceExisting) {
                repository.replaceSnapshots(snapshotsToSave)
            } else {
                repository.save(snapshotsToSave)
            }
        }
        if (replaceExisting || (oldestSnapshot == null && ledgerRepository.isLedgersSeeded())) {
            repository.setSyncMetadata(
                SyncMetadataKeys.SNAPSHOT_RECONSTRUCTION_VERSION,
                CURRENT_RECONSTRUCTION_VERSION,
            )
        }
    }
}
