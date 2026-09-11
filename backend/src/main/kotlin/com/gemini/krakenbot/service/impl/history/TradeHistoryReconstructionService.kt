package com.gemini.krakenbot.service.impl.history

import com.gemini.krakenbot.config.AppConfig
import com.gemini.krakenbot.domain.RebalancerEngine
import com.gemini.krakenbot.domain.resolveBalance
import com.gemini.krakenbot.model.Asset
import com.gemini.krakenbot.model.KrakenApiConstants
import com.gemini.krakenbot.model.LedgerEvent
import com.gemini.krakenbot.model.SyncMetadataKeys
import com.gemini.krakenbot.repository.LedgerRepository
import com.gemini.krakenbot.repository.PortfolioStatsRepository
import com.gemini.krakenbot.repository.TradeRepository
import com.gemini.krakenbot.service.ConfigService
import com.gemini.krakenbot.service.KrakenService
import com.gemini.krakenbot.service.withExecutionSession
import com.gemini.krakenbot.util.PrecisionConstants
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
    private val portfolioStatsRepository: PortfolioStatsRepository? = null,
    private val nowProvider: () -> Instant = Instant::now,
    private val accountHistoryScopeGuard: AccountHistoryScopeGuard? = null,
) {
    private val log = LoggerFactory.getLogger(TradeHistoryReconstructionService::class.java)

    companion object {
        const val CURRENT_RECONSTRUCTION_VERSION = "9"
    }

    suspend fun canRebuildSnapshots(config: AppConfig? = null, requestedStart: Instant? = null): Boolean {
        if (!ledgerRepository.isLedgersSeeded() || !repository.isHistorySeeded()) {
            return false
        }
        if (ledgerRepository.getSyncMetadata(SyncMetadataKeys.LEDGER_COVERAGE_VERSION) !=
            LedgersSyncService.CURRENT_LEDGER_COVERAGE_VERSION
        ) {
            return false
        }
        if (repository.getSyncMetadata(SyncMetadataKeys.TRADE_COVERAGE_VERSION) !=
            TradeHistorySyncService.CURRENT_TRADE_COVERAGE_VERSION
        ) {
            return false
        }

        val parsedInception = requestedStart
            ?: config?.settings?.inceptionDate?.let(InceptionDiscoveryService::parseInceptionDate)
        val seedBound = nowProvider().minus(PrecisionConstants.SEED_HISTORY_LOOKBACK_DAYS, ChronoUnit.DAYS)
        val effectiveStart = parsedInception?.takeIf { it.isBefore(seedBound) } ?: seedBound

        val ledgerHorizon = ledgerRepository
            .getSyncMetadata(SyncMetadataKeys.LEDGER_COVERAGE_HORIZON_EPOCH_SEC)
            ?.toLongOrNull()
            ?.let(Instant::ofEpochSecond)
        val tradeHorizon = repository
            .getSyncMetadata(SyncMetadataKeys.TRADE_COVERAGE_HORIZON_EPOCH_SEC)
            ?.toLongOrNull()
            ?.let(Instant::ofEpochSecond)

        if (ledgerHorizon == null || ledgerHorizon.isBefore(effectiveStart)) {
            return false
        }
        if (tradeHorizon == null || tradeHorizon.isBefore(effectiveStart)) {
            return false
        }

        if (effectiveStart.isBefore(seedBound)) {
            val storedLedgerStartSec = ledgerRepository
                .getSyncMetadata(SyncMetadataKeys.LEDGER_COVERAGE_START_EPOCH_SEC)
                ?.toLongOrNull()
            if (storedLedgerStartSec == null || storedLedgerStartSec > effectiveStart.epochSecond) {
                return false
            }
            val storedTradeStartSec = repository
                .getSyncMetadata(SyncMetadataKeys.TRADE_COVERAGE_START_EPOCH_SEC)
                ?.toLongOrNull()
            if (storedTradeStartSec == null || storedTradeStartSec > effectiveStart.epochSecond) {
                return false
            }
        }

        if (accountHistoryScopeGuard != null) {
            val scope = accountHistoryScopeGuard.validateAccountScope()
            if (!scope.isValid) {
                return false
            }
            val currentDigest = scope.currentScopeDigest
            if (!currentDigest.isNullOrBlank()) {
                val ledgerScopeDigest = ledgerRepository
                    .getSyncMetadata(SyncMetadataKeys.LEDGER_COVERAGE_ACCOUNT_SCOPE_DIGEST)
                if (ledgerScopeDigest != currentDigest) {
                    return false
                }
                val tradeScopeDigest = repository
                    .getSyncMetadata(SyncMetadataKeys.TRADE_COVERAGE_ACCOUNT_SCOPE_DIGEST)
                if (tradeScopeDigest != currentDigest) {
                    return false
                }
            }
        }

        return true
    }

    suspend fun reconstructHistoricalSnapshots() = configService.withExecutionSession {
        val config = configService.getConfig()
        krakenService.withStableBackend { backend ->
            reconstructHistoricalSnapshots(config, backend, replaceExisting = false)
        }
    }

    suspend fun reconstructHistoricalSnapshots(config: AppConfig, backend: KrakenService) =
        reconstructHistoricalSnapshots(config, backend, replaceExisting = false)

    suspend fun rebuildHistoricalSnapshots(config: AppConfig, backend: KrakenService) {
        check(canRebuildSnapshots(config)) {
            "Cannot rebuild historical snapshots before ledger synchronization, trade synchronization, and coverage migration complete"
        }
        reconstructHistoricalSnapshots(config, backend, replaceExisting = true)
    }

    private suspend fun reconstructHistoricalSnapshots(
        config: AppConfig,
        backend: KrakenService,
        replaceExisting: Boolean,
    ) {
        if (!canRebuildSnapshots(config)) {
            log.info(
                "Skipping historical snapshot reconstruction: trade or ledger history is not seeded or coverage is not current.",
            )
            return
        }

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
                val bal = resolveBalance(symbolU, fetchedLiveBalances)
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
                currentPrices[symbolU] = RebalancerEngine.resolvePriceFromTicker(symbolU, prices)
            }
            currentPrices[Asset.USD] = BigDecimal.ONE
        }

        // Slightly wider than HISTORICAL_DAYS_BACK so daily closes cover the full reconstruction window.
        val ohlcData = mutableMapOf<String, List<Pair<Long, BigDecimal>>>()
        val parsedInception = config.settings.inceptionDate
            ?.let(InceptionDiscoveryService::parseInceptionDate)
        val defaultSince = reconstructionNow.minus(95, ChronoUnit.DAYS)
        val since = if (parsedInception != null && parsedInception.isBefore(defaultSince)) {
            parsedInception.minus(1, ChronoUnit.DAYS).truncatedTo(ChronoUnit.DAYS)
        } else {
            defaultSince
        }
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

        val allLedgers = ledgerRepository.getLedgersInRange(since, reconstructionNow)
        val validation = AuthoritativeLedgerBalanceValidator.validate(allLedgers)
        if (!validation.isValid) {
            log.warn(
                "Skipping historical snapshot reconstruction: authoritative ledger validation failed: {}",
                validation.failure?.diagnostic ?: "unknown validation failure",
            )
            return
        }
        val resolvedScopes = validation.resolvedScopes

        val unresolvedNonzeroLedger = allLedgers.firstOrNull { event ->
            event.type in LedgerEvent.EXTERNAL_BALANCE_TYPES &&
                event.netBalanceDelta().signum() != 0 &&
                event.ledgerId !in resolvedScopes
        }
        if (unresolvedNonzeroLedger != null) {
            log.warn(
                "Skipping historical snapshot reconstruction: nonzero ledger scope is unresolved for type {}",
                unresolvedNonzeroLedger.type,
            )
            return
        }
        val externalLedgers = allLedgers.filter { it.type in LedgerEvent.EXTERNAL_BALANCE_TYPES }
        val historicalRewards = externalLedgers.filter { it.time.isBefore(cutoffTime) }

        val events =
            SnapshotHistoryCalculator.buildTimelineEvents(
                historicalTrades = historicalTrades,
                historicalRewards = historicalRewards,
                cutoffTime = cutoffTime,
                now = reconstructionNow,
                reconstructionStart = parsedInception,
            )

        // An empty OHLC response is recoverable only when another trustworthy source can value
        // every tracked asset at every required point. Never turn an unavailable price into a
        // zero-valued snapshot or advance the reconstruction version marker.
        val requiredPriceTimes = events.map { it.timestamp }.ifEmpty { listOf(reconstructionNow) }
        for (allocation in allocations) {
            val symbol = allocation.symbol.value.uppercase()
            if (symbol != Asset.USD) {
                requiredPriceTimes.forEach { timestamp ->
                    SnapshotHistoryCalculator.requireTrustworthyPrice(
                        symbol = symbol,
                        timestamp = timestamp,
                        ohlcData = ohlcData,
                        tradePrices = tradePrices,
                        currentPrices = currentPrices,
                    )
                }
            }
        }

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
                resolvedScopes = resolvedScopes,
            )

        if (snapshotsToSave.isNotEmpty()) {
            log.info("Saving {} reconstructed historical snapshots...", snapshotsToSave.size)
            if (replaceExisting) {
                repository.replaceSnapshots(snapshotsToSave)
            } else {
                repository.save(snapshotsToSave)
            }
            val earliest = snapshotsToSave.minBy { it.timestamp }
            repository.setSyncMetadata(
                SyncMetadataKeys.CONTINUOUS_HISTORY_START_EPOCH_MS,
                earliest.timestamp.toEpochMilli().toString(),
            )
            repository.setSyncMetadata(SyncMetadataKeys.INCEPTION_COMPARISON_PROPOSAL_FINGERPRINT, "")
            repository.setSyncMetadata(SyncMetadataKeys.INCEPTION_COMPARISON_PROPOSAL_STATUS, "")
            repository.setSyncMetadata(SyncMetadataKeys.INCEPTION_COMPARISON_PROPOSAL_CURSOR_EPOCH_MS, "")
            repository.setSyncMetadata(SyncMetadataKeys.INCEPTION_COMPARISON_PROPOSAL_SNAPSHOT_ID, "")
            repository.setSyncMetadata(SyncMetadataKeys.INCEPTION_COMPARISON_PROPOSAL_FUNDING_FINGERPRINT, "")
        }
        // Keep reconstruction freshness tied to the ledger coverage that was replayed. A
        // current reconstruction marker from an older coverage migration must not suppress the
        // first rebuild that can include newly supported ledger types.
        repository.setSyncMetadata(
            SyncMetadataKeys.SNAPSHOT_RECONSTRUCTION_LEDGER_COVERAGE_VERSION,
            LedgersSyncService.CURRENT_LEDGER_COVERAGE_VERSION,
        )
        repository.setSyncMetadata(
            SyncMetadataKeys.SNAPSHOT_RECONSTRUCTION_TRADE_COVERAGE_VERSION,
            TradeHistorySyncService.CURRENT_TRADE_COVERAGE_VERSION,
        )
        repository.setSyncMetadata(
            SyncMetadataKeys.SNAPSHOT_RECONSTRUCTION_VERSION,
            CURRENT_RECONSTRUCTION_VERSION,
        )
    }
}
