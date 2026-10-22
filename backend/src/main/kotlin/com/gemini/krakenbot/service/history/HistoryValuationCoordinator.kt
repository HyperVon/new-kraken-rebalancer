package com.gemini.krakenbot.service.history

import com.gemini.krakenbot.model.Asset
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** Builds account-scoped daily values away from the trading cycle and exchange import worker. */
class HistoryValuationCoordinator(
    private val applicationScope: CoroutineScope,
    private val synchronizer: HistorySynchronizer,
    private val store: HistoryStore,
    private val priceResolver: HistoryDailyPriceResolver,
    private val nowProvider: () -> Instant = Instant::now,
    private val pollInterval: Duration = POLL_INTERVAL,
    private val retryDelays: List<Duration> = RETRY_DELAYS,
    private val balanceBuilder: HistoryDailyBalanceBuilder = HistoryDailyBalanceBuilder(),
    private val valueBuilder: HistoryDailyValueBuilder = HistoryDailyValueBuilder(),
) {
    private val log = LoggerFactory.getLogger(HistoryValuationCoordinator::class.java)
    private val wakeups = Channel<Unit>(Channel.CONFLATED)
    private val manualRetryPending = AtomicBoolean(false)

    @Volatile
    private var worker: Job? = null

    init {
        require(!pollInterval.isNegative && !pollInterval.isZero)
        require(retryDelays.isNotEmpty())
        require(retryDelays.all { !it.isNegative && !it.isZero })
    }

    fun start(scope: CoroutineScope = applicationScope) {
        synchronized(this) {
            if (worker?.isActive == true) return
            worker = scope.launch {
                while (isActive) {
                    withTimeoutOrNull(pollInterval.toMillis()) { wakeups.receive() }
                    val retry = manualRetryPending.getAndSet(false)
                    try {
                        runOnce(retry)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        log.warn("Account History valuation pass failed.", error)
                    }
                }
            }
        }
    }

    fun stop() {
        synchronized(this) {
            worker?.cancel()
            worker = null
        }
    }

    /** Coalesced wakeup used after a committed ledger page and on the periodic recovery tick. */
    fun requestRebuild() {
        wakeups.trySend(Unit)
    }

    /** Retries blocked pricing work without deleting imported records or published generations. */
    fun retryFailedWork() {
        manualRetryPending.set(true)
        wakeups.trySend(Unit)
    }

    internal suspend fun runOnce(forceRetry: Boolean = false) {
        val account = synchronizer.verifiedAccount() ?: return
        while (store.loadSyncJobs(account.digest).any { job ->
                job.purpose == HistorySyncPurpose.RECENT &&
                    job.state in setOf(HistorySyncJobState.PENDING, HistorySyncJobState.RUNNING)
            }
        ) {
            delay(RECENT_SYNC_WAIT_INTERVAL.toMillis())
        }
        val now = nowProvider()
        val targetEnd = now.atZone(ZoneOffset.UTC).toLocalDate().minusDays(1)
        val targetStart = targetEnd.minusDays(TARGET_DAYS - 1L)
        val sourceRevision = store.currentLedgerRevision(account.digest)
        val previous = store.loadValuationJob(account.digest, HISTORY_ACCOUNT_DAILY_SCOPE)
        val sameTarget = previous?.let {
            it.targetStartDay == targetStart &&
                it.targetEndDay == targetEnd &&
                it.targetDays == TARGET_DAYS &&
                it.sourceRevision == sourceRevision &&
                it.calculationPolicyVersion == HISTORY_DAILY_VALUE_POLICY_VERSION
        } == true
        if (sameTarget && !forceRetry) {
            when (previous.state) {
                HistoryValuationJobState.COMPLETE,
                HistoryValuationJobState.NEEDS_ATTENTION,
                -> return

                HistoryValuationJobState.WAITING -> if (previous.nextRetryAt?.isAfter(now) == true) return

                else -> Unit
            }
        }

        var job = if (sameTarget) {
            checkNotNull(previous).copy(
                state = HistoryValuationJobState.RUNNING,
                nextRetryAt = null,
                lastError = null,
                retryAttempt = if (forceRetry) 0 else checkNotNull(previous).retryAttempt,
                updatedAt = now,
            )
        } else {
            HistoryValuationJob(
                accountIdentityDigest = account.digest,
                sourceAssetScope = HISTORY_ACCOUNT_DAILY_SCOPE,
                generationId = UUID.randomUUID().toString(),
                state = HistoryValuationJobState.RUNNING,
                stage = HistoryValuationStage.ESTABLISH_BALANCES,
                targetStartDay = targetStart,
                targetEndDay = targetEnd,
                nextDay = targetStart,
                targetDays = TARGET_DAYS,
                balancesProcessed = 0,
                balancesValid = 0,
                balancesBlocked = 0,
                requiredPriceTasks = null,
                completedPriceTasks = 0,
                completeValueDays = 0,
                blockedValueDays = 0,
                sourceRevision = sourceRevision,
                calculationPolicyVersion = HISTORY_DAILY_VALUE_POLICY_VERSION,
                lastProgressAt = null,
                nextRetryAt = null,
                lastSuccessAt = null,
                lastError = null,
                updatedAt = now,
                retryAttempt = 0,
            )
        }
        store.upsertValuationJob(job)

        var balances = store.queryDailyBalances(
            accountIdentityDigest = account.digest,
            sourceAssetScope = HISTORY_ACCOUNT_DAILY_SCOPE,
            from = targetStart.atStartOfDay(ZoneOffset.UTC).toInstant(),
            until = targetEnd.plusDays(2).atStartOfDay(ZoneOffset.UTC).toInstant(),
        )
        if (job.stage == HistoryValuationStage.PRICE_AND_VALUE &&
            (
                !isCompleteBalanceGeneration(balances, job.generationId, sourceRevision, targetStart, targetEnd) ||
                    balances.any {
                        it.generationId != job.generationId || it.sourceRevision != sourceRevision
                    }
                )
        ) {
            job = job.copy(
                stage = HistoryValuationStage.ESTABLISH_BALANCES,
                balancesProcessed = 0,
                balancesValid = 0,
                balancesBlocked = 0,
                requiredPriceTasks = null,
                completedPriceTasks = 0,
                nextDay = targetStart,
                updatedAt = nowProvider(),
            )
            store.upsertValuationJob(job)
        }

        if (job.stage == HistoryValuationStage.ESTABLISH_BALANCES) {
            val checkpoints = readAllCheckpoints(account.digest, targetEnd)
            ensureSourceRevision(account.digest, sourceRevision)
            val coverage = store.queryLedgerCoverageWindows(
                account.digest,
                overlappingStartSec = 0,
                overlappingEndSec = targetEnd.plusDays(1).atStartOfDay(ZoneOffset.UTC).toEpochSecond(),
            ).map { HistorySyncCoverageWindow(it.startSec, it.endSec) }
            balances = balanceBuilder.build(
                accountIdentityDigest = account.digest,
                startDay = targetStart,
                endDay = targetEnd,
                checkpoints = checkpoints,
                coverage = coverage,
                sourceRevision = sourceRevision,
                generationId = job.generationId,
            )
            val byDay = balances.groupBy { it.dayLabel }.toSortedMap()
            var validDays = 0
            var blockedDays = 0
            val resumeAfter = job.balancesProcessed
            byDay.values.forEachIndexed { index, dayBalances ->
                if (index < resumeAfter) {
                    if (dayBalances.all { it.status == HistoryDailyBalanceStatus.VALID }) validDays++ else blockedDays++
                    return@forEachIndexed
                }
                ensureSourceRevision(account.digest, sourceRevision)
                store.replaceDailyBalanceDay(dayBalances)
                if (dayBalances.all { it.status == HistoryDailyBalanceStatus.VALID }) validDays++ else blockedDays++
                val nextDay = dayBalances.first().dayLabel.plusDays(1).takeIf { it <= targetEnd }
                job = job.copy(
                    state = HistoryValuationJobState.RUNNING,
                    balancesProcessed = index + 1,
                    balancesValid = validDays,
                    balancesBlocked = blockedDays,
                    nextDay = nextDay,
                    lastProgressAt = nowProvider(),
                    updatedAt = nowProvider(),
                )
                store.upsertValuationJob(job)
            }
            check(byDay.size == TARGET_DAYS) { "Balance replay did not evaluate the frozen target period." }
            val requests = requiredPriceRequests(balances)
            job = job.copy(
                state = HistoryValuationJobState.RUNNING,
                stage = HistoryValuationStage.PRICE_AND_VALUE,
                nextDay = targetStart,
                balancesProcessed = TARGET_DAYS,
                balancesValid = validDays,
                balancesBlocked = blockedDays,
                requiredPriceTasks = requests.size,
                completedPriceTasks = 0,
                lastProgressAt = nowProvider(),
                updatedAt = nowProvider(),
            )
            store.upsertValuationJob(job)
        }

        balances = store.queryDailyBalances(
            accountIdentityDigest = account.digest,
            sourceAssetScope = HISTORY_ACCOUNT_DAILY_SCOPE,
            from = targetStart.atStartOfDay(ZoneOffset.UTC).toInstant(),
            until = targetEnd.plusDays(2).atStartOfDay(ZoneOffset.UTC).toInstant(),
        )
        val priceRequests = requiredPriceRequests(balances)
        val marketRequests = priceRequests.map(::marketRequest).toSet()
        val cachedPrices = loadCachedPrices(account.digest, priceRequests)
        val cachedRequests = priceRequests.count { request -> hasPrice(cachedPrices, request) }
        job = job.copy(
            requiredPriceTasks = priceRequests.size,
            completedPriceTasks = cachedRequests,
            lastProgressAt = if (cachedRequests > 0) nowProvider() else job.lastProgressAt,
            updatedAt = nowProvider(),
        )
        store.upsertValuationJob(job)

        try {
            for (requestsForAsset in marketRequests.groupBy { it.rawAssetKey }.toSortedMap().values) {
                val unresolved = requestsForAsset.filterNot { hasPrice(cachedPrices, it) }.toSet()
                if (unresolved.isEmpty()) continue
                val resolved = priceResolver.resolve(account.digest, unresolved)
                val usable = resolved.filter { price ->
                    unresolved.any { request ->
                        request.rawAssetKey == price.rawAssetKey &&
                            request.day.atStartOfDay(ZoneOffset.UTC).toEpochSecond() == price.candleOpen.epochSecond &&
                            price.candleEnd == price.candleOpen.plusSeconds(HistoryDailyValueBuilder.SECONDS_PER_DAY)
                    }
                }.distinctBy { it.rawAssetKey to it.candleOpen.epochSecond }
                store.saveDailyPrices(usable)
                usable.forEach { price -> cachedPrices[price.rawAssetKey to price.candleOpen.epochSecond] = price }
                job = job.copy(
                    completedPriceTasks = priceRequests.count { request -> hasPrice(cachedPrices, request) },
                    lastProgressAt = nowProvider(),
                    updatedAt = nowProvider(),
                )
                store.upsertValuationJob(job)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            recordPriceFailure(job, error)
            return
        }

        val dailyValues = valueBuilder.build(balances, cachedPrices.values.toList())
        check(dailyValues.size == TARGET_DAYS) { "Value replay did not evaluate the frozen target period." }
        val completeDays = dailyValues.count { it.status == HistoryDailyValueStatus.COMPLETE }
        val blockedDays = TARGET_DAYS - completeDays
        val needsAttention =
            blockedDays > 0 || cachedRequestsForRequired(priceRequests, cachedPrices) < priceRequests.size
        val publishAt = nowProvider()
        val terminalJob = job.copy(
            state = if (needsAttention) HistoryValuationJobState.NEEDS_ATTENTION else HistoryValuationJobState.COMPLETE,
            completeValueDays = completeDays,
            blockedValueDays = blockedDays,
            completedPriceTasks = cachedRequestsForRequired(priceRequests, cachedPrices),
            nextDay = null,
            lastProgressAt = publishAt,
            nextRetryAt = null,
            lastSuccessAt = publishAt,
            lastError = null,
            retryAttempt = 0,
            updatedAt = publishAt,
        )
        ensureSourceRevision(account.digest, sourceRevision)
        try {
            store.publishDailyValues(terminalJob, dailyValues)
        } catch (stale: IllegalStateException) {
            if (store.currentLedgerRevision(account.digest) != sourceRevision) {
                requestRebuild()
                return
            }
            throw stale
        }
    }

    private suspend fun readAllCheckpoints(
        accountIdentityDigest: String,
        throughDay: LocalDate,
    ): List<HistoryBalanceCheckpoint> {
        val throughSec = throughDay.plusDays(1).atStartOfDay(ZoneOffset.UTC).toEpochSecond()
        val all = mutableListOf<HistoryBalanceCheckpoint>()
        var offset = 0
        var expectedTotal: Long? = null
        do {
            val page = store.queryDailyCheckpointInputPage(
                accountIdentityDigest = accountIdentityDigest,
                throughSec = throughSec,
                limit = HISTORY_MAX_PAGE_LIMIT,
                offset = offset,
            )
            if (expectedTotal == null) expectedTotal = page.total
            check(page.total == expectedTotal) { "Ledger checkpoint set changed while being read." }
            check(page.items.isNotEmpty() || offset >= (expectedTotal ?: 0L)) {
                "Ledger checkpoint paging stopped before the reported total."
            }
            all += page.items
            offset += page.items.size
        } while (offset < (expectedTotal ?: 0L))
        return all
    }

    private suspend fun loadCachedPrices(
        accountIdentityDigest: String,
        requests: Set<HistoryDailyPriceRequest>,
    ): MutableMap<Pair<String, Long>, HistoryDailyPrice> {
        val start =
            requests.minOfOrNull { it.day.atStartOfDay(ZoneOffset.UTC).toEpochSecond() } ?: return mutableMapOf()
        val end =
            requests.maxOfOrNull { it.day.plusDays(1).atStartOfDay(ZoneOffset.UTC).toEpochSecond() }
                ?: return mutableMapOf()
        return requests.flatMap { request ->
            listOf(request.rawAssetKey, Asset.normalizeLedgerAsset(request.rawAssetKey))
        }.distinct().flatMap { rawAssetKey ->
            store.queryDailyPrices(accountIdentityDigest, rawAssetKey, start, end)
        }.filter { price ->
            isValidCachedPrice(price)
        }.associateByTo(mutableMapOf()) { Asset.normalizeLedgerAsset(it.rawAssetKey) to it.candleOpen.epochSecond }
    }

    private fun requiredPriceRequests(balances: List<HistoryDailyBalance>): Set<HistoryDailyPriceRequest> =
        balances.asSequence()
            .filter { it.status == HistoryDailyBalanceStatus.VALID && it.quantity?.signum() != 0 }
            .map { HistoryDailyPriceRequest(it.rawAssetKey, it.dayLabel) }
            .toSet()

    private fun hasPrice(
        prices: Map<Pair<String, Long>, HistoryDailyPrice>,
        request: HistoryDailyPriceRequest,
    ): Boolean = request.day.atStartOfDay(ZoneOffset.UTC).toEpochSecond().let { open ->
        prices[Asset.normalizeLedgerAsset(request.rawAssetKey) to open]?.let {
            isValidCachedPrice(it)
        } == true
    }

    private fun isValidCachedPrice(price: HistoryDailyPrice): Boolean =
        price.candleEnd == price.candleOpen.plusSeconds(HistoryDailyValueBuilder.SECONDS_PER_DAY) &&
            if (Asset.normalizeLedgerAsset(price.rawAssetKey).equals(Asset.USD, ignoreCase = true)) {
                price.closeUsd.compareTo(BigDecimal.ONE) == 0
            } else {
                price.closeUsd.signum() > 0
            }

    private fun marketRequest(request: HistoryDailyPriceRequest) =
        request.copy(rawAssetKey = Asset.normalizeLedgerAsset(request.rawAssetKey))

    private fun cachedRequestsForRequired(
        requests: Set<HistoryDailyPriceRequest>,
        prices: Map<Pair<String, Long>, HistoryDailyPrice>,
    ): Int = requests.count { hasPrice(prices, it) }

    private suspend fun ensureSourceRevision(accountIdentityDigest: String, expectedRevision: Long) {
        check(store.currentLedgerRevision(accountIdentityDigest) == expectedRevision) {
            "Ledger evidence changed while this valuation generation was being calculated."
        }
    }

    private suspend fun recordPriceFailure(job: HistoryValuationJob, error: Exception) {
        val attempt = job.retryAttempt + 1
        val now = nowProvider()
        val isTerminal = attempt >= retryDelays.size
        val retryAt = if (isTerminal) null else now.plus(retryDelays[attempt - 1])
        store.upsertValuationJob(
            job.copy(
                state = if (isTerminal) HistoryValuationJobState.NEEDS_ATTENTION else HistoryValuationJobState.WAITING,
                nextRetryAt = retryAt,
                retryAttempt = attempt,
                lastError = PRICE_FETCH_ERROR,
                lastProgressAt = now,
                updatedAt = now,
            ),
        )
        log.warn("Historical close pricing failed; retryAttempt={} terminal={}", attempt, isTerminal, error)
    }

    private fun isCompleteBalanceGeneration(
        balances: List<HistoryDailyBalance>,
        generationId: String,
        sourceRevision: Long,
        startDay: LocalDate,
        endDay: LocalDate,
    ): Boolean {
        val byDay = balances.groupBy { it.dayLabel }
        if (byDay.size != TARGET_DAYS) return false
        var day = startDay
        while (day <= endDay) {
            if (byDay[day]?.all { it.generationId == generationId && it.sourceRevision == sourceRevision } != true) {
                return false
            }
            day = day.plusDays(1)
        }
        return true
    }

    companion object {
        const val TARGET_DAYS = 365
        private const val PRICE_FETCH_ERROR = "Historical daily prices could not be fetched."
        private val POLL_INTERVAL: Duration = Duration.ofMinutes(1)
        private val RECENT_SYNC_WAIT_INTERVAL: Duration = Duration.ofMillis(250)
        private val RETRY_DELAYS = listOf(
            Duration.ofMinutes(1),
            Duration.ofMinutes(2),
            Duration.ofMinutes(5),
            Duration.ofMinutes(15),
            Duration.ofMinutes(15),
        )
    }
}
