package com.gemini.krakenbot.service.impl.history

import com.gemini.krakenbot.config.AppConfig
import com.gemini.krakenbot.model.Asset
import com.gemini.krakenbot.model.ComparisonUnavailableReason
import com.gemini.krakenbot.model.PortfolioSnapshot
import com.gemini.krakenbot.model.SyncMetadataKeys
import com.gemini.krakenbot.repository.TradeRepository
import com.gemini.krakenbot.service.ConfigService
import com.gemini.krakenbot.service.InceptionRecoveryStatus
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
 * [TRUNCATED] is the legacy isolated-install result where older history may
 * have been removed by a previous retention era; [RECOVERY_INCOMPLETE] means
 * the production recovery process has not yet proved both coverage and a
 * trustworthy baseline, so any lifetime number would be a plausible falsehood.
 */
enum class InceptionConfidence {
    CONFIDENT,
    TRUNCATED,
    RECOVERY_INCOMPLETE,
}

data class InceptionResolution(
    val inceptionTime: Instant,
    val inceptionSnapshot: PortfolioSnapshot?,
    val isAutoDetected: Boolean,
    val confidence: InceptionConfidence = InceptionConfidence.CONFIDENT,
    val unavailableReason: ComparisonUnavailableReason? = null,
)

class InceptionDiscoveryService(
    private val tradeRepository: TradeRepository,
    private val configService: ConfigService,
    private val nowProvider: () -> Instant = Instant::now,
    private val recoveryService: InceptionRecoveryService? = null,
) {
    private val log = LoggerFactory.getLogger(InceptionDiscoveryService::class.java)

    /**
     * True when a manually configured date may anchor a trusted local snapshot:
     * preparation produced a current verdict and the scope is valid (or the
     * backend is simulation, which carries no cross-account risk). BUSY carries
     * no verdict at all — background recovery may be mid-verification while the
     * credentials changed — so production callers must fail closed rather than
     * assume the previously seen scope still holds.
     */
    private fun isScopeTrustedForManual(preparation: InceptionRecoveryService.InceptionPreparationResult): Boolean {
        if (!preparation.scopeKnown) return false
        val status = preparation.scopeStatus
        return status == AccountScopeValidationStatus.VALID || status == AccountScopeValidationStatus.SIMULATION
    }

    private fun matchesConfiguredUniverse(snapshot: PortfolioSnapshot, config: AppConfig): Boolean {
        val expectedUniverse = config.allocations
            .map { Asset.normalizeLedgerAsset(it.symbol.value).uppercase() }
            .toSet()
        return snapshot.assets.keys.map { Asset.normalizeLedgerAsset(it).uppercase() }.toSet() == expectedUniverse
    }

    suspend fun resolveInception(): InceptionResolution {
        val config = configService.getConfig()
        val settings = config.settings
        val preparation = recoveryService?.prepareForCurrentConfigurationResult(settings)
        // 1. Check user-configured inception date.
        val parsedConfigured = parseInceptionDate(settings.inceptionDate)
        val configured = if (parsedConfigured != null && parsedConfigured.isAfter(nowProvider())) {
            log.warn("Ignoring configured inception date in the future: {}", parsedConfigured)
            null
        } else {
            parsedConfigured
        }
        val parsedComparison = parseInceptionDate(settings.comparisonStartDate)
        val comparisonStart = if (parsedComparison != null && parsedComparison.isAfter(nowProvider())) {
            log.warn("Ignoring configured comparison start in the future: {}", parsedComparison)
            null
        } else {
            parsedComparison
        }
        // A configured date is authoritative: re-resolve from it on every call
        // so a stale cache can never override the user's explicit setting.
        if (configured != null) {
            // The comparison start is the effective anchor when the user accepted a
            // verified later snapshot; the strategy start itself stays authoritative
            // for detection metadata and retention.
            val effectiveStart = comparisonStart ?: configured
            if (preparation != null && !isScopeTrustedForManual(preparation)) {
                // A configured date fixes *when* inception was, but it cannot bless
                // history the active credentials are not shown to own: anchoring a
                // baseline snapshot from foreign (or unverifiable) history would
                // launder it as trusted. Nothing durable is deleted or rewritten;
                // this call only withholds trust until a verdict exists.
                log.warn("Ignoring configured inception date while account scope is unverified: {}", configured)
                return InceptionResolution(
                    inceptionTime = configured,
                    inceptionSnapshot = null,
                    isAutoDetected = false,
                    confidence = InceptionConfidence.RECOVERY_INCOMPLETE,
                    unavailableReason = ComparisonUnavailableReason.INCEPTION_RECOVERY_INCOMPLETE,
                )
            }
            val nearbySnapshot = findClosestSnapshot(effectiveStart)
            // A snapshot from a different allocation universe (e.g. a prior approval
            // before a configuration change) must not be adopted as the configured
            // anchor: the recovery service rejects the same snapshot and reports the
            // universe change, so trusting it here would launder foreign history and
            // diverge from that verdict.
            val snapshot = nearbySnapshot?.takeIf { matchesConfiguredUniverse(it, config) }
            if (nearbySnapshot != null && snapshot == null) {
                log.warn(
                    "Ignoring snapshot near configured inception date {}: tracked asset universe changed",
                    effectiveStart,
                )
            }
            val approvedBaselineId: Int? =
                if (snapshot != null && recoveryService != null && comparisonStart == null) {
                    val candidateId = tradeRepository
                        .getSyncMetadata(SyncMetadataKeys.INCEPTION_APPROVED_BASELINE_SNAPSHOT_ID)
                        ?.toIntOrNull()
                    if (candidateId != null && tradeRepository.getSnapshotId(snapshot.timestamp) == candidateId) {
                        candidateId
                    } else {
                        null
                    }
                } else {
                    null
                }
            if (snapshot != null && approvedBaselineId == null) {
                // Re-check approved state immediately before persisting: recovery confirms
                // its baseline under its own mutex, so this re-read keeps a just-confirmed
                // approved baseline from being re-labelled as configured detection.
                val approvedBaselineNow = tradeRepository
                    .getSyncMetadata(SyncMetadataKeys.INCEPTION_APPROVED_BASELINE_SNAPSHOT_ID)
                    ?.takeIf(String::isNotBlank)
                if (approvedBaselineNow == null) {
                    persistDetection(configured, snapshot, source = INCEPTION_SOURCE_CONFIGURED)
                    log.info("Using configured inception date: {} (comparison anchor: {})", configured, effectiveStart)
                    return InceptionResolution(
                        inceptionTime = effectiveStart,
                        inceptionSnapshot = snapshot,
                        isAutoDetected = false,
                    )
                }
            }
            if (snapshot != null) {
                // Either the nearby snapshot IS the approved baseline persisted by the
                // recovery service, or approved state exists and this is an accepted later
                // anchor; either way re-detecting would overwrite approved provenance
                // with the configured source.
                log.info("Using approved-start baseline established at {}", configured)
                return InceptionResolution(
                    inceptionTime = effectiveStart,
                    inceptionSnapshot = snapshot,
                    isAutoDetected = false,
                )
            }
            if (recoveryService != null && comparisonStart == null) {
                // The approved baseline was persisted by the recovery service with its own
                // provenance; re-detecting here would overwrite it with the configured source.
                val approvedId = tradeRepository
                    .getSyncMetadata(SyncMetadataKeys.INCEPTION_APPROVED_BASELINE_SNAPSHOT_ID)
                    ?.toIntOrNull()
                val approved = approvedId?.let { tradeRepository.getSnapshotById(it) }
                if (approved != null && approved.timestamp == configured) {
                    log.info("Using approved-start baseline established at {}", configured)
                    return InceptionResolution(
                        inceptionTime = configured,
                        inceptionSnapshot = approved,
                        isAutoDetected = false,
                    )
                }
            }
            persistDetection(configured, snapshot, source = INCEPTION_SOURCE_CONFIGURED)
            if (recoveryService == null) {
                // Preserve the legacy isolated-fixture contract; the application graph
                // reports a recovery-specific status instead of claiming history removal.
                log.warn("Configured inception date {} has no retained anchor snapshot", configured)
                return InceptionResolution(
                    inceptionTime = configured,
                    inceptionSnapshot = null,
                    isAutoDetected = false,
                    confidence = InceptionConfidence.TRUNCATED,
                )
            }
            if (comparisonStart != null) {
                // An accepted later anchor is retention-protected; losing it means the
                // snapshot store no longer holds the verified anchor at all.
                log.warn("Accepted comparison start {} has no retained anchor snapshot", comparisonStart)
                return InceptionResolution(
                    inceptionTime = configured,
                    inceptionSnapshot = null,
                    isAutoDetected = false,
                    confidence = InceptionConfidence.RECOVERY_INCOMPLETE,
                    unavailableReason = ComparisonUnavailableReason.INCEPTION_BASELINE_UNAVAILABLE,
                )
            }
            log.warn("Configured inception date {} has no retained anchor snapshot", configured)
            return InceptionResolution(
                inceptionTime = configured,
                inceptionSnapshot = null,
                isAutoDetected = false,
                confidence = InceptionConfidence.RECOVERY_INCOMPLETE,
                unavailableReason = recoveryUnavailableReason(recoveryService.getStatus().status),
            )
        }

        // Production resolution is evidence-driven. The legacy fallback below remains available
        // only for isolated callers that do not wire the recovery service (mostly old unit-test
        // fixtures); the application graph always supplies it.
        if (recoveryService != null) {
            val recovery = recoveryService.getStatus()
            if (recovery.status == InceptionRecoveryStatus.CONFIRMED &&
                preparation?.canTrustRecoveredInception == true
            ) {
                val candidateTime = recovery.candidateTime?.let(::parseInceptionDate)
                val baselineId = tradeRepository
                    .getSyncMetadata(SyncMetadataKeys.INCEPTION_SNAPSHOT_ID)
                    ?.toIntOrNull()
                val baseline = baselineId?.let { tradeRepository.getSnapshotById(it) }
                if (candidateTime != null && baseline != null) {
                    return InceptionResolution(
                        inceptionTime = candidateTime,
                        inceptionSnapshot = baseline,
                        isAutoDetected = true,
                    )
                }
                log.warn("Inception recovery is marked confirmed but its baseline identity is unavailable")
            }
            val candidateTime = recovery.candidateTime?.let(::parseInceptionDate)
            val earliestSnapshot = tradeRepository.getSnapshotsInRange(Instant.EPOCH, nowProvider()).firstOrNull()
            return InceptionResolution(
                inceptionTime = candidateTime ?: earliestSnapshot?.timestamp ?: nowProvider(),
                inceptionSnapshot = null,
                isAutoDetected = true,
                confidence = InceptionConfidence.RECOVERY_INCOMPLETE,
                unavailableReason = recoveryUnavailableReason(recovery.status),
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
                val nearbySnapshot = findClosestSnapshot(cachedTime)
                // Same guard as the configured block: a snapshot from a prior asset
                // universe (allocation change after auto-detection) must not be
                // laundered as a trusted anchor while recovery reports the universe
                // change instead.
                val snapshot = nearbySnapshot?.takeIf { matchesConfiguredUniverse(it, config) }
                if (nearbySnapshot != null && snapshot == null) {
                    log.warn(
                        "Ignoring cached inception snapshot {}: tracked asset universe changed",
                        cachedTime,
                    )
                }
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

        // 3. Check for upgraded vs fresh installation.
        // An existing database where history was already seeded or existing
        // trades/snapshots exist prior to this feature could have had older history
        // pruned by the 90-day retention loop. Row age alone cannot prove completeness.
        // On upgraded installs, auto-detection cannot be trusted as strategy inception
        // without an explicit user-configured date.
        if (isUpgradedInstall()) {
            val earliestSnapshot = tradeRepository.getSnapshotsInRange(Instant.EPOCH, nowProvider()).firstOrNull()
            val time = earliestSnapshot?.timestamp ?: nowProvider()
            log.warn(
                "Database is an upgraded installation with pre-existing history; " +
                    "earlier history may have been pruned. Inception must be configured manually.",
            )
            return InceptionResolution(
                inceptionTime = time,
                inceptionSnapshot = null,
                isAutoDetected = true,
                confidence = InceptionConfidence.TRUNCATED,
            )
        }

        // 4. Fresh installation: history starts from strategy start.
        // One-time auto-detect from trade clusters.
        val detected = detectBurstInception()
        if (detected != null) {
            persistDetection(detected.inceptionTime, detected.inceptionSnapshot, source = INCEPTION_SOURCE_AUTO)
            log.info("Auto-detected inception from rebalance burst at {}", detected.inceptionTime)
            return detected
        }

        // 5. Earliest retained snapshot on a fresh database
        // The same universe guard applies: a snapshot from a prior asset universe
        // cannot represent "history starts from strategy start" under the current
        // configuration, and recovery would reject the same history as ambiguous.
        val earliestSnapshot = tradeRepository.getSnapshotsInRange(Instant.EPOCH, nowProvider())
            .firstOrNull()
            ?.takeIf { matchesConfiguredUniverse(it, config) }
        if (earliestSnapshot != null) {
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
                confidence = InceptionConfidence.CONFIDENT,
            )
        }

        // 6. Default fallback to current time when database has no snapshots
        val now = nowProvider()
        return InceptionResolution(
            inceptionTime = now,
            inceptionSnapshot = null,
            isAutoDetected = true,
        )
    }

    private suspend fun isUpgradedInstall(): Boolean {
        val installType = tradeRepository.getSyncMetadata(SyncMetadataKeys.INCEPTION_INSTALL_TYPE)
        if (installType == INSTALL_TYPE_UPGRADED) return true
        if (installType == INSTALL_TYPE_FRESH) return false

        // No explicit install type recorded yet. Check durable database state:
        // If history was already seeded before this feature was introduced,
        // previous retention could have pruned old snapshots and trades.
        val hasSeededHistory = tradeRepository.isHistorySeeded()
        val recordedType = if (hasSeededHistory) INSTALL_TYPE_UPGRADED else INSTALL_TYPE_FRESH
        tradeRepository.setSyncMetadata(SyncMetadataKeys.INCEPTION_INSTALL_TYPE, recordedType)
        return hasSeededHistory
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
                    val nearbySnapshot = findClosestSnapshot(burstTime)
                    // Same universe guard as the other adoption paths: the burst trades
                    // are in-universe evidence, but a nearby snapshot from a prior asset
                    // universe must not become the trusted anchor.
                    val snapshot = nearbySnapshot?.takeIf { matchesConfiguredUniverse(it, config) }
                    if (nearbySnapshot != null && snapshot == null) {
                        log.warn("Ignoring burst-time snapshot {}: tracked asset universe changed", burstTime)
                    }
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

    private fun recoveryUnavailableReason(status: String): ComparisonUnavailableReason = when (status) {
        InceptionRecoveryStatus.AMBIGUOUS -> ComparisonUnavailableReason.INCEPTION_AMBIGUOUS
        InceptionRecoveryStatus.COMPLETE_NO_BOT_EVIDENCE -> ComparisonUnavailableReason.INCEPTION_NO_BOT_EVIDENCE
        InceptionRecoveryStatus.BASELINE_UNAVAILABLE -> ComparisonUnavailableReason.INCEPTION_BASELINE_UNAVAILABLE
        else -> ComparisonUnavailableReason.INCEPTION_RECOVERY_INCOMPLETE
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
        const val INCEPTION_SOURCE_AUTO_RECOVERED = InceptionRecoveryService.INCEPTION_SOURCE_AUTO_RECOVERED
        const val INSTALL_TYPE_FRESH = "fresh"
        const val INSTALL_TYPE_UPGRADED = "upgraded"

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
