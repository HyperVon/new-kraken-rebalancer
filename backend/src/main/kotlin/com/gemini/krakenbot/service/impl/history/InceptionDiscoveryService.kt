package com.gemini.krakenbot.service.impl.history

import com.gemini.krakenbot.model.PortfolioSnapshot
import com.gemini.krakenbot.model.SyncMetadataKeys
import com.gemini.krakenbot.repository.TradeRepository
import com.gemini.krakenbot.service.ConfigService
import org.slf4j.LoggerFactory
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeParseException
import kotlin.math.abs

data class InceptionResolution(
    val inceptionTime: Instant,
    val inceptionSnapshot: PortfolioSnapshot?,
    val isAutoDetected: Boolean,
)

class InceptionDiscoveryService(
    private val tradeRepository: TradeRepository,
    private val configService: ConfigService,
    private val nowProvider: () -> Instant = Instant::now,
) {
    private val log = LoggerFactory.getLogger(InceptionDiscoveryService::class.java)

    suspend fun resolveInception(): InceptionResolution {
        val settings = configService.getConfig().settings
        // 1. Check user-configured inception date
        val parsedConfigured = parseInceptionDate(settings.inceptionDate)
        val configured = if (parsedConfigured != null && parsedConfigured.isAfter(nowProvider())) {
            log.warn("Ignoring configured inception date in the future: {}", parsedConfigured)
            null
        } else {
            parsedConfigured
        }
        if (configured != null) {
            val snapshot = findClosestSnapshot(configured)
            tradeRepository.setSyncMetadata(
                SyncMetadataKeys.DETECTED_INCEPTION_EPOCH_MS,
                configured.toEpochMilli().toString(),
            )
            if (snapshot != null) {
                tradeRepository.getSnapshotId(snapshot.timestamp)?.let { id ->
                    tradeRepository.setSyncMetadata(SyncMetadataKeys.INCEPTION_SNAPSHOT_ID, id.toString())
                }
            }
            log.info("Using configured inception date: {}", configured)
            return InceptionResolution(
                inceptionTime = configured,
                inceptionSnapshot = snapshot,
                isAutoDetected = false,
            )
        }

        // 2. Check cached/previously-committed metadata if already detected previously
        val cachedEpoch = tradeRepository.getSyncMetadata(SyncMetadataKeys.DETECTED_INCEPTION_EPOCH_MS)?.toLongOrNull()
        if (cachedEpoch != null && cachedEpoch > 0) {
            val cachedTime = Instant.ofEpochMilli(cachedEpoch)
            if (cachedTime.isAfter(nowProvider())) {
                log.warn("Ignoring cached inception timestamp in the future: {}", cachedTime)
            } else {
                val snapshot = findClosestSnapshot(cachedTime)
                if (snapshot != null) {
                    tradeRepository.getSnapshotId(snapshot.timestamp)?.let { id ->
                        tradeRepository.setSyncMetadata(SyncMetadataKeys.INCEPTION_SNAPSHOT_ID, id.toString())
                    }
                }
                return InceptionResolution(
                    inceptionTime = cachedTime,
                    inceptionSnapshot = snapshot,
                    isAutoDetected = true,
                )
            }
        }

        // 3. One-time auto-detect from trade clusters
        val detected = detectBurstInception()
        if (detected != null) {
            tradeRepository.setSyncMetadata(
                SyncMetadataKeys.DETECTED_INCEPTION_EPOCH_MS,
                detected.inceptionTime.toEpochMilli().toString(),
            )
            if (detected.inceptionSnapshot != null) {
                tradeRepository.getSnapshotId(detected.inceptionSnapshot.timestamp)?.let { id ->
                    tradeRepository.setSyncMetadata(SyncMetadataKeys.INCEPTION_SNAPSHOT_ID, id.toString())
                }
            }
            log.info("Auto-detected inception from rebalance burst at {}", detected.inceptionTime)
            return detected
        }

        // 4. Defensible fallback: earliest snapshot in database
        val earliestSnapshot = tradeRepository.getSnapshotsInRange(Instant.EPOCH, nowProvider()).firstOrNull()
        if (earliestSnapshot != null) {
            tradeRepository.getSnapshotId(earliestSnapshot.timestamp)?.let { id ->
                tradeRepository.setSyncMetadata(SyncMetadataKeys.INCEPTION_SNAPSHOT_ID, id.toString())
            }
            tradeRepository.setSyncMetadata(
                SyncMetadataKeys.DETECTED_INCEPTION_EPOCH_MS,
                earliestSnapshot.timestamp.toEpochMilli().toString(),
            )
            log.info("Falling back to earliest snapshot as inception: {}", earliestSnapshot.timestamp)
            return InceptionResolution(
                inceptionTime = earliestSnapshot.timestamp,
                inceptionSnapshot = earliestSnapshot,
                isAutoDetected = true,
            )
        }

        // 5. Default fallback to current time when database has no snapshots
        val now = nowProvider()
        return InceptionResolution(
            inceptionTime = now,
            inceptionSnapshot = null,
            isAutoDetected = true,
        )
    }

    suspend fun detectBurstInception(): InceptionResolution? {
        val config = configService.getConfig()
        val configuredSymbols = config.allocations
            .map { it.symbol.value.uppercase() }
            .filterNot { it == "USD" || it == "ZUSD" }
            .toSet()

        if (configuredSymbols.isEmpty()) return null

        val trades = tradeRepository.getTradesInRange(Instant.EPOCH, nowProvider())
            .filter { it.success && !it.dryRun && it.symbol.uppercase() in configuredSymbols }
            .sortedBy { it.timestamp }

        if (trades.isEmpty()) return null

        var clusterStart = trades.first()
        val currentClusterSymbols = mutableSetOf(clusterStart.symbol.uppercase())

        for (i in 1 until trades.size) {
            val trade = trades[i]
            val totalSpanMs = trade.timestamp.toEpochMilli() - clusterStart.timestamp.toEpochMilli()
            if (totalSpanMs <= BURST_WINDOW_MS) {
                currentClusterSymbols.add(trade.symbol.uppercase())
                if (currentClusterSymbols.size >= MIN_DISTINCT_SYMBOLS_FOR_BURST) {
                    val burstTime = clusterStart.timestamp
                    val snapshot = findClosestSnapshot(burstTime)
                    return InceptionResolution(
                        inceptionTime = burstTime,
                        inceptionSnapshot = snapshot,
                        isAutoDetected = true,
                    )
                }
            } else {
                clusterStart = trade
                currentClusterSymbols.clear()
                currentClusterSymbols.add(trade.symbol.uppercase())
            }
        }
        return null
    }

    suspend fun findClosestSnapshot(targetTime: Instant): PortfolioSnapshot? {
        val candidatesRange = tradeRepository.getSnapshotsInRange(
            targetTime.minusSeconds(MAX_ANCHOR_PROXIMITY_SECONDS),
            targetTime.plusSeconds(MAX_ANCHOR_PROXIMITY_SECONDS),
        )
        if (candidatesRange.isNotEmpty()) {
            return candidatesRange.minByOrNull {
                abs(it.timestamp.toEpochMilli() - targetTime.toEpochMilli())
            }
        }
        val before = tradeRepository.getSnapshotBefore(targetTime.plusMillis(1000))
        if (before != null &&
            abs(before.timestamp.toEpochMilli() - targetTime.toEpochMilli()) <= MAX_ANCHOR_PROXIMITY_SECONDS * 1000L
        ) {
            return before
        }
        return null
    }

    companion object {
        const val BURST_WINDOW_MS = 5000L
        const val MIN_DISTINCT_SYMBOLS_FOR_BURST = 2
        const val MAX_ANCHOR_PROXIMITY_SECONDS = 300L

        fun parseInceptionDate(text: String?): Instant? {
            if (text.isNullOrBlank()) return null
            val trimmed = text.trim()
            return try {
                Instant.parse(trimmed)
            } catch (_: DateTimeParseException) {
                try {
                    LocalDate.parse(trimmed).atStartOfDay(ZoneOffset.UTC).toInstant()
                } catch (_: DateTimeParseException) {
                    null
                }
            }
        }
    }
}
