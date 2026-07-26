package com.gemini.krakenbot.service.impl.history

import com.gemini.krakenbot.config.AppConfig
import com.gemini.krakenbot.model.SyncMetadataKeys
import com.gemini.krakenbot.model.TradeRecord
import com.gemini.krakenbot.model.TradeSource
import com.gemini.krakenbot.model.isMatchingApiTrade
import com.gemini.krakenbot.repository.TradeRepository
import com.gemini.krakenbot.service.ConfigService
import com.gemini.krakenbot.service.KrakenService
import com.gemini.krakenbot.util.TradeCalculator
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant

class TradeHistorySyncService(
    private val repository: TradeRepository,
    private val krakenService: KrakenService,
    private val configService: ConfigService,
    private val reconstructionService: TradeHistoryReconstructionService,
) {
    private val log = LoggerFactory.getLogger(TradeHistorySyncService::class.java)

    @Volatile
    private var lastSyncTime: Instant = Instant.EPOCH

    suspend fun syncTradesFromKraken() {
        val now = Instant.now()
        val elapsedSeconds = Duration.between(lastSyncTime, now).seconds
        // Throttle Kraken history pulls to at most once per 5 minutes.
        if (elapsedSeconds < 300) {
            log.info("Skipping trade history synchronization; last run was only {} seconds ago.", elapsedSeconds)
            return
        }

        val config = configService.getConfig()
        if (!config.settings.simulation && !config.kraken.hasValidCredentials()) {
            log.warn("Kraken API key is blank or placeholder. Skipping trade history synchronization.")
            return
        }

        krakenService.withStableBackend { syncTradesFromKrakenPinned(config) }
    }

    private suspend fun syncTradesFromKrakenPinned(config: AppConfig) {
        val isSeeded = repository.isHistorySeeded()
        val latestTradeTime = repository.getLatestTradeTime()
        val watermarkInstant = readSyncWatermark()
        // Prefer real/sim fill time; fall back to the last successful sync watermark so a
        // dry-run-only account does not re-pull full history from EPOCH every 5 minutes (CQ-8-M2).
        val effectiveLatest = listOfNotNull(latestTradeTime, watermarkInstant).maxOrNull()

        // Null effective → full history (startSec null). Otherwise overlap by 5 minutes so fills
        // near the previous watermark are re-fetched and reconciled rather than double-inserted.
        // [isHistorySeeded] only gates progress metadata / first-sync completion, not this window.
        val startSec = effectiveLatest?.minusSeconds(300)?.epochSecond

        log.info("Starting trade history synchronization (isSeeded={}, startSec={})...", isSeeded, startSec)

        val queryStart = effectiveLatest?.minusSeconds(300) ?: Instant.EPOCH

        val queryEnd = Instant.now().plusSeconds(300)
        val originalLocalTrades = repository.getTradesInRange(queryStart, queryEnd).toMutableList()

        val allocations = config.allocations.map { it.symbol.value }
        var totalAdded = 0
        var totalReconciled = 0
        // Fingerprints of API fills already handled in this sync. Kraken newest-first offset
        // pagination can re-emit the last row of page N as the first of page N+1 when a fill
        // lands mid-pagination; without this set that row is double-inserted (CQ-8-M1).
        val seenApiFillKeys = mutableSetOf<String>()

        getTradeHistoryPaginated(startSec = startSec)
            .collect { apiTrades ->
                for (apiTrade: TradeRecord in apiTrades) {
                    if (!seenApiFillKeys.add(apiFillIdentityKey(apiTrade))) continue

                    // Local row has requested economics; API has the settle. Match within
                    // tolerances so we update the local row instead of inserting a second History trade.
                    val matchingLocalTrade =
                        originalLocalTrades.find { local ->
                            local.isMatchingApiTrade(apiTrade, allocations)
                        }

                    if (matchingLocalTrade != null) {
                        if (matchingLocalTrade != apiTrade) {
                            val expectedPrice = matchingLocalTrade.expectedPrice
                            val reconciledSlippage =
                                expectedPrice?.let { expected ->
                                    TradeCalculator.calculateSlippage(
                                        apiTrade.side,
                                        apiTrade.price,
                                        expected,
                                    )
                                }
                            val reconciledTrade =
                                apiTrade.copy(
                                    expectedPrice = expectedPrice,
                                    slippagePercent = reconciledSlippage,
                                    source = TradeSource.API_FILL,
                                    // Keep local cycle linkage; prefer API ordertxid when present.
                                    cycleId = matchingLocalTrade.cycleId,
                                    orderTxid = apiTrade.orderTxid ?: matchingLocalTrade.orderTxid,
                                )
                            log.info(
                                "Reconciling trade record: local (timestamp={}, usdAmount={}) with API (timestamp={}, usdAmount={})",
                                matchingLocalTrade.timestamp,
                                matchingLocalTrade.usdAmount,
                                apiTrade.timestamp,
                                apiTrade.usdAmount,
                            )

                            repository.updateTrade(matchingLocalTrade, reconciledTrade)
                            totalReconciled++
                        }
                        // One local row per API fill in this sync pass.
                        originalLocalTrades.remove(matchingLocalTrade)
                    } else {
                        repository.saveTrade(apiTrade)
                        totalAdded++
                    }
                }
            }

        val snapshots = repository.load()
        val totalTrades = repository.getTradeSummaryStats().totalTradesExecuted
        val isSimulation = config.settings.simulation

        if (!isSimulation && totalTrades > 0 && snapshots.size <= 1) {
            log.info(
                "Historical snapshots are missing or insufficient (found {} snapshots, {} trades). Starting reconstruction...",
                snapshots.size,
                totalTrades,
            )
            try {
                reconstructionService.reconstructHistoricalSnapshots()
                log.info("Historical snapshot reconstruction completed successfully.")
            } catch (e: Exception) {
                log.error("Failed to reconstruct historical snapshots", e)
            }
        }

        if (!isSeeded) {
            repository.setHistorySeeded(true)
            repository.setSyncMetadata(SyncMetadataKeys.SYNC_OFFSET, SyncMetadataKeys.COMPLETED)
            repository.setSyncMetadata(SyncMetadataKeys.SYNC_TOTAL, SyncMetadataKeys.COMPLETED)
        }
        // Persist watermark even when no real fills exist so the next sync is incremental.
        writeSyncWatermark(Instant.now())
        lastSyncTime = Instant.now()
        log.info("Trade history synchronization completed. Added: {} new, Reconciled: {}.", totalAdded, totalReconciled)
    }

    private suspend fun readSyncWatermark(): Instant? =
        repository.getSyncMetadata(SyncMetadataKeys.SYNC_WATERMARK_EPOCH_SEC)
            ?.toLongOrNull()
            ?.let { Instant.ofEpochSecond(it) }

    private suspend fun writeSyncWatermark(instant: Instant) {
        repository.setSyncMetadata(
            SyncMetadataKeys.SYNC_WATERMARK_EPOCH_SEC,
            instant.epochSecond.toString(),
        )
    }

    /**
     * Identity for a single API fill within one sync pass. Includes economics + timestamp so
     * multi-leg fills that share [TradeRecord.orderTxid] stay distinct, while an exact
     * pagination duplicate is skipped.
     */
    private fun apiFillIdentityKey(trade: TradeRecord): String = listOf(
        trade.timestamp.toEpochMilli().toString(),
        trade.pair,
        trade.side.uppercase(),
        trade.volume.toPlainString(),
        trade.usdAmount.toPlainString(),
        trade.fee.toPlainString(),
        trade.orderTxid.orEmpty(),
    ).joinToString("|")

    /** Cold paginated Kraken history (page size 50); writes sync_offset/total only until first seed completes. */
    private fun getTradeHistoryPaginated(startSec: Long?): Flow<List<TradeRecord>> = flow {
        val pageSize = 50
        var offset = 0
        val isSeeded = repository.isHistorySeeded()

        while (true) {
            log.info("Fetching trade history batch with offset={}", offset)
            val apiTrades = krakenService.getTradeHistory(startSec = startSec, offset = offset)
            val totalCount = krakenService.getLastTradeHistoryTotalCount()

            if (!isSeeded) {
                repository.setSyncMetadata(SyncMetadataKeys.SYNC_OFFSET, offset.toString())
                repository.setSyncMetadata(SyncMetadataKeys.SYNC_TOTAL, totalCount.toString())
            }

            if (apiTrades.isEmpty()) break

            emit(apiTrades)

            if (apiTrades.size < pageSize) break
            offset += pageSize
        }
    }

    suspend fun getSyncMetadata(key: String): String? = repository.getSyncMetadata(key)

    suspend fun setSyncMetadata(key: String, value: String) = repository.setSyncMetadata(key, value)

    suspend fun isHistorySeeded(): Boolean = repository.isHistorySeeded()
}
