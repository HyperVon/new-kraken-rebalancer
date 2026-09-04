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
        val configured = parseInceptionDate(settings.inceptionDate)
        if (configured != null) {
            val snapshot = findClosestSnapshot(configured)
            tradeRepository.setSyncMetadata(
                SyncMetadataKeys.DETECTED_INCEPTION_EPOCH_MS,
                configured.toEpochMilli().toString(),
            )
            log.info("Using configured inception date: {}", configured)
            return InceptionResolution(
                inceptionTime = configured,
                inceptionSnapshot = snapshot,
                isAutoDetected = false,
            )
        }

        // 2. Auto-detect from trade clusters
        val detected = detectBurstInception()
        if (detected != null) {
            tradeRepository.setSyncMetadata(
                SyncMetadataKeys.DETECTED_INCEPTION_EPOCH_MS,
                detected.inceptionTime.toEpochMilli().toString(),
            )
            log.info("Auto-detected inception from rebalance burst at {}", detected.inceptionTime)
            return detected
        }

        // 3. Check cached metadata if already detected previously
        val cachedEpoch = tradeRepository.getSyncMetadata(SyncMetadataKeys.DETECTED_INCEPTION_EPOCH_MS)?.toLongOrNull()
        if (cachedEpoch != null && cachedEpoch > 0) {
            val cachedTime = Instant.ofEpochMilli(cachedEpoch)
            val snapshot = findClosestSnapshot(cachedTime)
            return InceptionResolution(
                inceptionTime = cachedTime,
                inceptionSnapshot = snapshot,
                isAutoDetected = true,
            )
        }

        // 4. Fallback: earliest snapshot in database
        val earliestSnapshot = tradeRepository.load().minByOrNull { it.timestamp }
        if (earliestSnapshot != null) {
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

        // 5. Default fallback to current time
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
            .filter { it.success && !it.dryRun }
            .sortedBy { it.timestamp }

        if (trades.isEmpty()) return null

        var clusterStart = trades.first()
        var clusterPrev = trades.first()
        val currentClusterSymbols = mutableSetOf<String>()
        if (clusterStart.symbol.uppercase() in configuredSymbols) {
            currentClusterSymbols.add(clusterStart.symbol.uppercase())
        }

        for (i in 1 until trades.size) {
            val trade = trades[i]
            val gapMs = trade.timestamp.toEpochMilli() - clusterPrev.timestamp.toEpochMilli()
            if (gapMs in 0..BURST_WINDOW_MS) {
                if (trade.symbol.uppercase() in configuredSymbols) {
                    currentClusterSymbols.add(trade.symbol.uppercase())
                }
                clusterPrev = trade
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
                clusterPrev = trade
                currentClusterSymbols.clear()
                if (trade.symbol.uppercase() in configuredSymbols) {
                    currentClusterSymbols.add(trade.symbol.uppercase())
                }
            }
        }
        return null
    }

    suspend fun findClosestSnapshot(targetTime: Instant): PortfolioSnapshot? {
        val candidatesRange = tradeRepository.getSnapshotsInRange(
            targetTime.minusSeconds(30),
            targetTime.plusSeconds(30),
        )
        if (candidatesRange.isNotEmpty()) {
            return candidatesRange.minByOrNull {
                abs(it.timestamp.toEpochMilli() - targetTime.toEpochMilli())
            }
        }
        return tradeRepository.getSnapshotBefore(targetTime.plusMillis(1000))
            ?: tradeRepository.load().minByOrNull {
                abs(it.timestamp.toEpochMilli() - targetTime.toEpochMilli())
            }
    }

    companion object {
        const val BURST_WINDOW_MS = 5000L
        const val MIN_DISTINCT_SYMBOLS_FOR_BURST = 2

        fun parseInceptionDate(text: String?): Instant? {
            if (text.isNullOrBlank()) return null
            val trimmed = text.trim()
            return try {
                Instant.parse(trimmed)
            } catch (_: DateTimeParseException) {
                try {
                    LocalDate.parse(trimmed).atStartOfDay(ZoneOffset.UTC).toInstant()
                } catch (_: DateTimeParseException) {
                    trimmed.toLongOrNull()?.let { Instant.ofEpochMilli(it) }
                }
            }
        }
    }
}
