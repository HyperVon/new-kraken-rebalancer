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

/**
 * Whether the local history can support [InceptionResolution] as a true
 * strategy start. [CONFIDENT] means full history from strategy start is
 * present (or the start is explicitly configured with an anchor);
 * [TRUNCATED] means older history was likely removed by a previous retention
 * era, so any lifetime baseline would be a plausible falsehood.
 */
enum class InceptionConfidence {
    CONFIDENT,
    TRUNCATED,
}

data class InceptionResolution(
    val inceptionTime: Instant,
    val inceptionSnapshot: PortfolioSnapshot?,
    val isAutoDetected: Boolean,
    val confidence: InceptionConfidence = InceptionConfidence.CONFIDENT,
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
        // A configured date is authoritative: re-resolve from it on every call
        // so a stale cache can never override the user's explicit setting.
        if (configured != null) {
            val snapshot = findClosestSnapshot(configured)
            persistDetection(configured, snapshot, source = INCEPTION_SOURCE_CONFIGURED)
            if (snapshot == null) {
                // Configured but unanchorable: no retained snapshot near the
                // date, so no baseline can be built from it.
                log.warn("Configured inception date {} has no retained anchor snapshot", configured)
                return InceptionResolution(
                    inceptionTime = configured,
                    inceptionSnapshot = null,
                    isAutoDetected = false,
                    confidence = InceptionConfidence.TRUNCATED,
                )
            }
            log.info("Using configured inception date: {}", configured)
            return InceptionResolution(
                inceptionTime = configured,
                inceptionSnapshot = snapshot,
                isAutoDetected = false,
            )
        }

        // 2. Check cached/previously-committed metadata if already detected previously.
        // The recorded source prevents a cleared manual override from silently
        // returning as "auto-detected": a CONFIGURED cache is only honored
        // while the same date is still configured (handled above, so any
        // CONFIGURED cache reaching here is stale). Legacy rows without a
        // source are trusted only while their anchor snapshot is retained.
        val cachedEpoch = tradeRepository.getSyncMetadata(SyncMetadataKeys.DETECTED_INCEPTION_EPOCH_MS)?.toLongOrNull()
        val cachedSource = tradeRepository.getSyncMetadata(SyncMetadataKeys.DETECTED_INCEPTION_SOURCE)
        if (cachedEpoch != null && cachedEpoch > 0) {
            val cachedTime = Instant.ofEpochMilli(cachedEpoch)
            if (cachedTime.isAfter(nowProvider())) {
                log.warn("Ignoring cached inception timestamp in the future: {}", cachedTime)
            } else if (cachedSource == INCEPTION_SOURCE_CONFIGURED) {
                log.info("Ignoring stale configured inception cache after configuration change: {}", cachedTime)
            } else {
                val snapshot = findClosestSnapshot(cachedTime)
                if (snapshot != null) {
                    tradeRepository.getSnapshotId(snapshot.timestamp)?.let { id ->
                        tradeRepository.setSyncMetadata(SyncMetadataKeys.INCEPTION_SNAPSHOT_ID, id.toString())
                    }
                    return InceptionResolution(
                        inceptionTime = cachedTime,
                        inceptionSnapshot = snapshot,
                        isAutoDetected = true,
                    )
                }
                log.info("Ignoring cached inception without a retained anchor: {}", cachedTime)
            }
        }

        // 3. One-time auto-detect from trade clusters
        val detected = detectBurstInception()
        if (detected != null) {
            persistDetection(detected.inceptionTime, detected.inceptionSnapshot, source = INCEPTION_SOURCE_AUTO)
            log.info("Auto-detected inception from rebalance burst at {}", detected.inceptionTime)
            return detected
        }

        // 4. Earliest retained snapshot: only a valid inception when the
        // retained history provably starts at strategy start. Retention only
        // ever removed data older than HISTORICAL_DAYS_BACK, so an earliest
        // snapshot newer than that horizon means nothing could have been
        // pruned. Otherwise the database is a migrated/truncated install and
        // the earliest row must NOT be cached or presented as inception.
        val earliestSnapshot = tradeRepository.getSnapshotsInRange(Instant.EPOCH, nowProvider()).firstOrNull()
        if (earliestSnapshot != null) {
            // Retention only ever removed snapshots older than this horizon, so
            // an earliest snapshot newer than it proves nothing was pruned.
            val retentionHorizon = nowProvider().minusSeconds(
                com.gemini.krakenbot.util.PrecisionConstants.HISTORICAL_DAYS_BACK.toLong() * 86400L,
            )
            val tradesExist = tradeRepository.getTradesInRange(Instant.EPOCH, nowProvider()).isNotEmpty()
            if (!earliestSnapshot.timestamp.isBefore(retentionHorizon) ||
                (!tradesExist && !tradeRepository.isHistorySeeded())
            ) {
                persistDetection(
                    earliestSnapshot.timestamp,
                    earliestSnapshot,
                    source = INCEPTION_SOURCE_AUTO,
                )
                log.info("Falling back to earliest snapshot as inception: {}", earliestSnapshot.timestamp)
                return InceptionResolution(
                    inceptionTime = earliestSnapshot.timestamp,
                    inceptionSnapshot = earliestSnapshot,
                    isAutoDetected = true,
                )
            }
            log.warn(
                "Earliest retained snapshot at {} predates trustworthy retention; " +
                    "inception must be configured manually",
                earliestSnapshot.timestamp,
            )
            return InceptionResolution(
                inceptionTime = earliestSnapshot.timestamp,
                inceptionSnapshot = null,
                isAutoDetected = true,
                confidence = InceptionConfidence.TRUNCATED,
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

    private suspend fun persistDetection(time: Instant, snapshot: PortfolioSnapshot?, source: String) {
        tradeRepository.setSyncMetadata(
            SyncMetadataKeys.DETECTED_INCEPTION_EPOCH_MS,
            time.toEpochMilli().toString(),
        )
        tradeRepository.setSyncMetadata(SyncMetadataKeys.DETECTED_INCEPTION_SOURCE, source)
        if (snapshot != null) {
            tradeRepository.getSnapshotId(snapshot.timestamp)?.let { id ->
                tradeRepository.setSyncMetadata(SyncMetadataKeys.INCEPTION_SNAPSHOT_ID, id.toString())
            }
        }
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
        const val INCEPTION_SOURCE_CONFIGURED = "configured"
        const val INCEPTION_SOURCE_AUTO = "auto"

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
