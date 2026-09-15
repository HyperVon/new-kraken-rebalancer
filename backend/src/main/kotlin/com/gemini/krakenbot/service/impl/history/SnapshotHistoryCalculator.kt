package com.gemini.krakenbot.service.impl.history

import com.gemini.krakenbot.config.Allocation
import com.gemini.krakenbot.config.Settings
import com.gemini.krakenbot.domain.PortfolioCalculations
import com.gemini.krakenbot.domain.RebalancerEngine
import com.gemini.krakenbot.domain.isNegative
import com.gemini.krakenbot.model.Asset
import com.gemini.krakenbot.model.FlowCategory
import com.gemini.krakenbot.model.KrakenApiConstants
import com.gemini.krakenbot.model.LedgerEvent
import com.gemini.krakenbot.model.LedgerFlowClassifier
import com.gemini.krakenbot.model.OrderSide
import com.gemini.krakenbot.model.PortfolioSnapshot
import com.gemini.krakenbot.model.TradeRecord
import com.gemini.krakenbot.util.PrecisionConstants
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.math.abs

/**
 * Reconstructs historical portfolio snapshots by walking a newest→oldest timeline and
 * reverse-applying trades so balances at each point match pre-trade state for older events.
 */
object SnapshotHistoryCalculator {
    private data class CalculatedAsset(
        val symbol: String,
        val balance: BigDecimal,
        val price: BigDecimal,
        val valueUSD: BigDecimal,
        val targetPercent: Double,
    )

    sealed class TimelineEvent : Comparable<TimelineEvent> {
        abstract val timestamp: Instant

        data class TradeEvent(override val timestamp: Instant, val trade: TradeRecord) : TimelineEvent()

        data class RewardEvent(override val timestamp: Instant, val event: LedgerEvent) : TimelineEvent()

        data class DailyCloseEvent(override val timestamp: Instant) : TimelineEvent()

        // Newest first — [calculateHistoricalSnapshots] undoes trades after each snapshot.
        // Same-instant events are re-ordered onto their authoritative ledger checkpoint chain
        // before the walk; ledger rows themselves are not timeline events.
        override fun compareTo(other: TimelineEvent): Int {
            val byTime = other.timestamp.compareTo(this.timestamp)
            if (byTime != 0) return byTime
            return order().compareTo(other.order())
        }

        private fun order(): Int = when (this) {
            is RewardEvent -> 0
            is TradeEvent -> 1
            is DailyCloseEvent -> 2
        }
    }

    private val externalLedgerTypes = LedgerEvent.EXTERNAL_BALANCE_TYPES

    fun buildTimelineEvents(
        historicalTrades: List<TradeRecord>,
        historicalRewards: List<LedgerEvent> = emptyList(),
        cutoffTime: Instant,
        now: Instant = Instant.now(),
        reconstructionStart: Instant? = null,
        authoritativeTradeLegs: List<LedgerEvent> = emptyList(),
    ): List<TimelineEvent> {
        requireCompleteConversions(historicalRewards)
        // Fail closed on unknown raw ledger evidence: the repository is raw/unprojected, so any
        // type outside EXTERNAL_BALANCE_TYPES (except `trade` checkpoints) must block timeline
        // construction rather than being silently filtered out here.
        val unknownReward = historicalRewards.firstOrNull {
            it.type !in externalLedgerTypes &&
                !it.type.equals(KrakenApiConstants.LEDGER_TYPE_TRADE, ignoreCase = true)
        }
        require(unknownReward == null) {
            "Cannot build timeline with unknown raw ledger type: ${unknownReward?.type} (${unknownReward?.ledgerId})"
        }
        val events = historicalTrades
            .map { TimelineEvent.TradeEvent(it.timestamp, it) }
            .toMutableList<TimelineEvent>()
        events += historicalRewards
            .filter { it.type in externalLedgerTypes }
            .map { TimelineEvent.RewardEvent(it.time, it) }
        events += authoritativeTradeLegs.map { TimelineEvent.RewardEvent(it.time, it) }
        val daysBack = maxOf(
            PrecisionConstants.HISTORICAL_DAYS_BACK.toLong(),
            reconstructionStart?.let {
                ChronoUnit.DAYS.between(it.truncatedTo(ChronoUnit.DAYS), now.truncatedTo(ChronoUnit.DAYS)) + 1
            } ?: 0L,
        )
        events += (0..daysBack).mapNotNull { day ->
            val dailyTime =
                now
                    .minus(day, ChronoUnit.DAYS)
                    .truncatedTo(ChronoUnit.DAYS)
                    .plus(PrecisionConstants.LAST_HOUR_OF_DAY.toLong(), ChronoUnit.HOURS)
                    .plus(PrecisionConstants.LAST_MINUTE_OF_HOUR.toLong(), ChronoUnit.MINUTES)
                    .plus(PrecisionConstants.LAST_SECOND_OF_MINUTE.toLong(), ChronoUnit.SECONDS)
            dailyTime.takeIf {
                it.isBefore(cutoffTime) && (reconstructionStart == null || !it.isBefore(reconstructionStart))
            }?.let(TimelineEvent::DailyCloseEvent)
        }
        if (reconstructionStart != null &&
            reconstructionStart.isBefore(cutoffTime) &&
            events.none { it.timestamp == reconstructionStart }
        ) {
            events += TimelineEvent.DailyCloseEvent(reconstructionStart)
        }

        events.sort()
        return events
    }

    /**
     * Raw reverse replay has no benchmark classifier, so validate conversion groups before any
     * leg can be applied independently. An incomplete group must block reconstruction instead of
     * silently moving only one asset balance backward.
     */
    private fun requireCompleteConversions(events: List<LedgerEvent>) {
        val conversionEvents = events.filter {
            it.type.equals(KrakenApiConstants.LEDGER_TYPE_CONVERSION, ignoreCase = true)
        }
        if (conversionEvents.isEmpty()) return

        val classifications = LedgerFlowClassifier.classifyAll(events)
        val invalid = conversionEvents.firstOrNull {
            classifications[it.ledgerId] != FlowCategory.INTERNAL_MOVE
        }
        require(invalid == null) {
            "Cannot reverse-apply incomplete or contradictory conversion ledger group: " +
                "${invalid?.ledgerId}"
        }
    }

    private data class RawHistoricalPoint(
        /** Buffered snapshot points collected newest-first; reversed for ATH-aware chronological computation. */
        val timestamp: Instant,
        val exactPortfolioValue: BigDecimal,
        val calculatedAssets: List<CalculatedAsset>,
    )

    fun calculateHistoricalSnapshots(
        events: List<TimelineEvent>,
        allocations: List<Allocation>,
        runningBalances: MutableMap<String, BigDecimal>,
        currentPrices: Map<String, BigDecimal>,
        ohlcData: Map<String, List<Pair<Long, BigDecimal>>>,
        tradePrices: Map<String, List<Pair<Instant, BigDecimal>>>,
        settings: Settings,
        currentAth: BigDecimal = BigDecimal.ZERO,
        resolvedScopes: Map<String, AuthoritativeLedgerBalanceValidator.LedgerWalletScope> = emptyMap(),
        tradeLegsByRefId: Map<String, List<LedgerEvent>> = emptyMap(),
        tradeLegsByTradeIdentity: Map<String, List<LedgerEvent>> = emptyMap(),
    ): List<PortfolioSnapshot> {
        // Same-instant groups are ordered during the reverse walk below: only the live running
        // balances at a group's newer boundary know which locally reversible orientation the
        // surrounding chain supports, so repository/input order is never used as evidence.
        val appliedOrder = ArrayList<TimelineEvent>(events.size)

        // [runningBalances] starts at the reconstruction cutoff (the oldest retained snapshot, or current balances
        // when none exists). Invert every event first to derive the state that precedes the oldest point, then
        // replay forward so each emitted point is the state after the events at or before its timestamp. A later
        // authoritative checkpoint can therefore never leak backwards into an older point.
        // Bounded per-asset uncertainty from Kraken's rounded ledger amount/fee fields. Every row
        // inverted without a checkpoint of its own adds its validator allowance here, so the next
        // older checkpoint is compared against 1e-8 plus the allowances accrued since the last
        // checkpoint rather than against a fixed per-row cap.
        val reverseUncertainty = mutableMapOf<String, BigDecimal>()
        var groupStart = 0
        while (groupStart < events.size) {
            var groupEnd = groupStart + 1
            while (groupEnd < events.size && events[groupEnd].timestamp == events[groupStart].timestamp) groupEnd++
            val group = events.subList(groupStart, groupEnd)
            if (group.size == 1) {
                val single = group.first()
                reverseApplySingle(
                    single,
                    runningBalances,
                    reverseUncertainty,
                    tradeLegsByRefId,
                    tradeLegsByTradeIdentity,
                    resolvedScopes,
                )
                appliedOrder += single
            } else {
                appliedOrder += reverseApplySameInstantGroup(
                    group,
                    runningBalances,
                    reverseUncertainty,
                    tradeLegsByRefId,
                    tradeLegsByTradeIdentity,
                    resolvedScopes,
                )
            }
            groupStart = groupEnd
        }

        // Replay from a copy so [runningBalances] keeps the pre-history state callers rely on.
        val forwardBalances = runningBalances.toMutableMap()
        val rawPoints = mutableListOf<RawHistoricalPoint>()
        for (ev in appliedOrder.asReversed()) {
            if (ev is TimelineEvent.TradeEvent) {
                applyForwardTrade(ev.trade, forwardBalances, tradeLegsByRefId, tradeLegsByTradeIdentity)
            } else if (ev is TimelineEvent.RewardEvent) {
                applyForwardReward(ev.event, forwardBalances, resolvedScopes)
            }

            val snapshotTimestamp = ev.timestamp
            var exactPortfolioValue = BigDecimal.ZERO

            val calculatedAssets =
                allocations.map { alloc ->
                    val symbol = alloc.symbol.value.uppercase()
                    val rawBal = forwardBalances[symbol] ?: BigDecimal.ZERO
                    val balance = if (rawBal.isNegative) BigDecimal.ZERO else rawBal
                    val price = getPriceForTimestamp(symbol, snapshotTimestamp, ohlcData, tradePrices, currentPrices)
                    val valueUSD = balance.multiply(price)
                    exactPortfolioValue = exactPortfolioValue.add(valueUSD)
                    CalculatedAsset(symbol, balance, price, valueUSD, alloc.targetPercent)
                }

            rawPoints.add(RawHistoricalPoint(snapshotTimestamp, exactPortfolioValue, calculatedAssets))
        }

        return buildSnapshotsChronological(rawPoints.asReversed(), allocations, settings, currentAth)
    }

    /**
     * Undo one fill through the shared trade replay contract. Authoritative retained ledger legs
     * restore the recorded post-entry balances before their net deltas are inverted; trades
     * without retained legs fall back to the TradeRecord economics. Balances for untracked
     * markets are created lazily, matching the historical behavior of this display path.
     *
     * An authoritative checkpoint snaps the wallet, consumes the uncertainty carried by newer
     * rows and restarts the carry at this fill's own allowance; without one, the allowance joins
     * the carry in [reverseUncertainty] for the next older checkpoint to resolve.
     */
    private fun reverseApplyTrade(
        trade: TradeRecord,
        runningBalances: MutableMap<String, BigDecimal>,
        reverseUncertainty: MutableMap<String, BigDecimal>,
        tradeLegsByRefId: Map<String, List<LedgerEvent>>,
        tradeLegsByTradeIdentity: Map<String, List<LedgerEvent>>,
    ) {
        val replay = when (
            val classification = TradeLedgerReplay.classify(
                trade,
                tradeLegsByRefId,
                tradeLegsByTradeIdentity,
            )
        ) {
            is TradeLedgerReplay.Classification.Replayable -> classification

            is TradeLedgerReplay.Classification.Unsupported ->
                throw IllegalArgumentException(classification.reason)
        }
        val effect = replay.ledgerEffect
        if (effect == null || effect.baseCheckpoint == null) {
            runningBalances.putIfAbsent(replay.base, BigDecimal.ZERO)
        }
        if (effect == null || effect.quoteCheckpoint == null) {
            runningBalances.putIfAbsent(replay.quote, BigDecimal.ZERO)
        }
        val baseCarry = reverseUncertainty[replay.base] ?: BigDecimal.ZERO
        val quoteCarry = reverseUncertainty[replay.quote] ?: BigDecimal.ZERO
        require(TradeLedgerReplay.reverseApply(replay, runningBalances, baseCarry, quoteCarry)) {
            "Missing tracked balance during historical reconstruction for ${trade.symbol}"
        }
        if (effect != null) {
            reverseUncertainty[replay.base] =
                carriedUncertainty(baseCarry, effect.baseCheckpoint, effect.baseRoundingAllowance)
            reverseUncertainty[replay.quote] =
                carriedUncertainty(quoteCarry, effect.quoteCheckpoint, effect.quoteRoundingAllowance)
        }
    }

    /** Undo one external ledger balance delta, respecting the resolved wallet scope. */
    private fun reverseApplyReward(
        event: LedgerEvent,
        runningBalances: MutableMap<String, BigDecimal>,
        reverseUncertainty: MutableMap<String, BigDecimal>,
        resolvedScopes: Map<String, AuthoritativeLedgerBalanceValidator.LedgerWalletScope>,
    ) {
        val symbol = Asset.normalizeLedgerAsset(event.asset).uppercase()
        if (symbol !in runningBalances) return
        val scope = resolvedScopes[event.ledgerId]
        if (scope != null && scope != AuthoritativeLedgerBalanceValidator.LedgerWalletScope.SPOT) {
            return
        }
        val netDelta = event.netBalanceDelta()
        val allowance = AuthoritativeLedgerBalanceValidator.allowedDifference(event)
        runningBalances[symbol] = if (event.hasAuthoritativeBalance) {
            // Mirror the validator's forward checkpoint: an authoritative row advanced this scope
            // to the recorded post-entry balance, so inverting the delta from that post-state
            // keeps reconstruction exactly inverse instead of accumulating per-row rounding drift.
            event.balance.subtract(netDelta)
        } else {
            runningBalances.getValue(symbol).subtract(netDelta)
        }
        reverseUncertainty[symbol] = if (event.hasAuthoritativeBalance) {
            allowance
        } else {
            (reverseUncertainty[symbol] ?: BigDecimal.ZERO).add(allowance)
        }
    }

    /**
     * Uncertainty carried by a nominal pre-event balance. An authoritative checkpoint is exact, so
     * it snaps the wallet and consumes whatever rounding the newer checkpoint-free rows had
     * accumulated; otherwise the row's own allowance joins the carry.
     */
    private fun carriedUncertainty(carried: BigDecimal, checkpoint: BigDecimal?, allowance: BigDecimal): BigDecimal =
        if (checkpoint != null) allowance else carried.add(allowance)

    /**
     * Apply one fill forward through the shared trade replay contract. Authoritative retained
     * ledger legs advance the state to their recorded post-entry checkpoints; trades without
     * retained legs fall back to the TradeRecord economics. This is the exact inverse of
     * [reverseApplyTrade], so replaying forward keeps every emitted point equal to the state
     * after its own event instead of inheriting a later checkpoint.
     */
    private fun applyForwardTrade(
        trade: TradeRecord,
        runningBalances: MutableMap<String, BigDecimal>,
        tradeLegsByRefId: Map<String, List<LedgerEvent>>,
        tradeLegsByTradeIdentity: Map<String, List<LedgerEvent>>,
    ) {
        val replay = when (
            val classification = TradeLedgerReplay.classify(
                trade,
                tradeLegsByRefId,
                tradeLegsByTradeIdentity,
            )
        ) {
            is TradeLedgerReplay.Classification.Replayable -> classification

            is TradeLedgerReplay.Classification.Unsupported ->
                throw IllegalArgumentException(classification.reason)
        }
        runningBalances.putIfAbsent(replay.base, BigDecimal.ZERO)
        runningBalances.putIfAbsent(replay.quote, BigDecimal.ZERO)
        val baseBalance = runningBalances.getValue(replay.base)
        val quoteBalance = runningBalances.getValue(replay.quote)
        val effect = replay.ledgerEffect
        if (effect != null) {
            runningBalances[replay.base] = effect.baseCheckpoint ?: baseBalance.add(effect.baseNetDelta)
            runningBalances[replay.quote] = effect.quoteCheckpoint ?: quoteBalance.add(effect.quoteNetDelta)
        } else if (replay.isBuy) {
            runningBalances[replay.base] = baseBalance.add(replay.volume)
            runningBalances[replay.quote] = quoteBalance.subtract(replay.quoteCost).subtract(replay.fee)
        } else {
            runningBalances[replay.base] = baseBalance.subtract(replay.volume)
            runningBalances[replay.quote] = quoteBalance.add(replay.quoteCost).subtract(replay.fee)
        }
    }

    /** Apply one external ledger balance delta forward, respecting the resolved wallet scope. */
    private fun applyForwardReward(
        event: LedgerEvent,
        runningBalances: MutableMap<String, BigDecimal>,
        resolvedScopes: Map<String, AuthoritativeLedgerBalanceValidator.LedgerWalletScope>,
    ) {
        val symbol = Asset.normalizeLedgerAsset(event.asset).uppercase()
        if (symbol !in runningBalances) return
        val scope = resolvedScopes[event.ledgerId]
        if (scope != null && scope != AuthoritativeLedgerBalanceValidator.LedgerWalletScope.SPOT) {
            return
        }
        runningBalances[symbol] = if (event.hasAuthoritativeBalance) {
            // An authoritative row records the post-entry balance, so the forward replay advances
            // straight to that checkpoint instead of accumulating per-row rounding drift.
            event.balance
        } else {
            runningBalances.getValue(symbol).add(event.netBalanceDelta())
        }
    }

    private data class ChainPoint(val asset: String, val pre: BigDecimal, val post: BigDecimal)

    private const val SAME_INSTANT_SEARCH_NODE_LIMIT = 10_000

    /** Applies one event in reverse-walk order, throwing when a tracked event cannot be inverted. */
    private fun reverseApplySingle(
        event: TimelineEvent,
        runningBalances: MutableMap<String, BigDecimal>,
        reverseUncertainty: MutableMap<String, BigDecimal>,
        tradeLegsByRefId: Map<String, List<LedgerEvent>>,
        tradeLegsByTradeIdentity: Map<String, List<LedgerEvent>>,
        resolvedScopes: Map<String, AuthoritativeLedgerBalanceValidator.LedgerWalletScope>,
    ) {
        when (event) {
            is TimelineEvent.TradeEvent ->
                reverseApplyTrade(
                    event.trade,
                    runningBalances,
                    reverseUncertainty,
                    tradeLegsByRefId,
                    tradeLegsByTradeIdentity,
                )

            is TimelineEvent.RewardEvent -> reverseApplyReward(
                event.event,
                runningBalances,
                reverseUncertainty,
                resolvedScopes,
            )

            is TimelineEvent.DailyCloseEvent -> Unit
        }
    }

    /**
     * Applies one same-instant group during the reverse walk. Events carrying authoritative
     * checkpoints are ordered by a bounded backtracking search against the live balances, so a
     * locally reversible cycle (A.post == B.pre && B.post == A.pre) resolves to the orientation
     * the surrounding chain supports; zero complete orderings, or several producing different
     * balances, fail closed. Events without checkpoint evidence keep their repository order
     * after the checkpointed chain.
     */
    private fun reverseApplySameInstantGroup(
        group: List<TimelineEvent>,
        runningBalances: MutableMap<String, BigDecimal>,
        reverseUncertainty: MutableMap<String, BigDecimal>,
        tradeLegsByRefId: Map<String, List<LedgerEvent>>,
        tradeLegsByTradeIdentity: Map<String, List<LedgerEvent>>,
        resolvedScopes: Map<String, AuthoritativeLedgerBalanceValidator.LedgerWalletScope>,
    ): List<TimelineEvent> {
        val replays = group.filterIsInstance<TimelineEvent.TradeEvent>().associateWith { tradeEvent ->
            runCatching {
                TradeLedgerReplay.classify(tradeEvent.trade, tradeLegsByRefId, tradeLegsByTradeIdentity)
            }.getOrNull()
        }
        val trackedSymbols = runningBalances.keys.toMutableSet()
        replays.values.filterIsInstance<TradeLedgerReplay.Classification.Replayable>().forEach {
            trackedSymbols += it.base
            trackedSymbols += it.quote
        }
        val applied = ArrayList<TimelineEvent>(group.size)
        val (constraining, nonConstraining) = splitGroup(
            group,
            trackedSymbols,
            runningBalances,
            replays,
            resolvedScopes,
        )
        if (constraining.size < 2) {
            // Nothing ambiguous to resolve: checkpointed events invert first, repository order after.
            constraining.forEach { event ->
                reverseApplySingle(
                    event,
                    runningBalances,
                    reverseUncertainty,
                    tradeLegsByRefId,
                    tradeLegsByTradeIdentity,
                    resolvedScopes,
                )
            }
            nonConstraining.forEach { event ->
                reverseApplySingle(
                    event,
                    runningBalances,
                    reverseUncertainty,
                    tradeLegsByRefId,
                    tradeLegsByTradeIdentity,
                    resolvedScopes,
                )
            }
            applied += constraining + nonConstraining
            return applied
        }

        val solutions = solveSameInstantReverseOrder(
            group,
            constraining,
            replays,
            runningBalances,
            reverseUncertainty,
        )
        when {
            solutions.isEmpty() ->
                throw IllegalArgumentException(
                    "No valid same-instant ordering at ${group.first().timestamp} for " +
                        "${constraining.size} checkpointed events",
                )

            solutions.drop(1).any { solution -> !sameTerminalState(solution, solutions.first()) } ->
                throw IllegalArgumentException(
                    "Ambiguous same-instant ordering at ${group.first().timestamp}: " +
                        "${solutions.size} complete orderings produce different balances",
                )
        }
        val winner = canonicalWinner(solutions)
        winner.order.forEach { event ->
            require(tryReverseConstrainingEvent(event, runningBalances, reverseUncertainty, replays)) {
                "Missing tracked balance during historical reconstruction for same-instant group at ${event.timestamp}"
            }
        }
        nonConstraining.forEach { event ->
            reverseApplySingle(
                event,
                runningBalances,
                reverseUncertainty,
                tradeLegsByRefId,
                tradeLegsByTradeIdentity,
                resolvedScopes,
            )
        }
        applied += winner.order + nonConstraining
        return applied
    }

    /** Checkpointed events that constrain the ordering, paired with everything applied after the chain. */
    private fun splitGroup(
        group: List<TimelineEvent>,
        trackedSymbols: Set<String>,
        runningBalances: Map<String, BigDecimal>,
        replays: Map<TimelineEvent.TradeEvent, TradeLedgerReplay.Classification?>,
        resolvedScopes: Map<String, AuthoritativeLedgerBalanceValidator.LedgerWalletScope>,
    ): Pair<List<TimelineEvent>, List<TimelineEvent>> {
        val constraining =
            group.indices
                .filter { chainPoints(group[it], trackedSymbols, replays, resolvedScopes).isNotEmpty() }
                .map { group[it] }
                .filter { event ->
                    // Rewards on assets the wallet no longer tracks cannot be validated against a
                    // balance and stay non-constraining, matching their unconditional snap below.
                    event !is TimelineEvent.RewardEvent ||
                        Asset.normalizeLedgerAsset(event.event.asset).uppercase() in runningBalances
                }
                .sortedBy(::sameInstantSortKey)
        val nonConstraining = group.filter { it !in constraining.toSet() }
        return constraining to nonConstraining
    }

    /**
     * Bounded backtracking search over reverse orders of one same-instant group. Every candidate
     * must be legally invertible from the current balances under the shared checkpoint contract;
     * a solution counts only when every checkpointed event is consumed. Non-constraining events
     * are applied by the caller after the chosen chain.
     */
    private fun solveSameInstantReverseOrder(
        group: List<TimelineEvent>,
        constraining: List<TimelineEvent>,
        replays: Map<TimelineEvent.TradeEvent, TradeLedgerReplay.Classification?>,
        runningBalances: Map<String, BigDecimal>,
        reverseUncertainty: Map<String, BigDecimal>,
    ): List<SameInstantSolution> {
        val solutions = mutableListOf<SameInstantSolution>()
        var visitedNodes = 0

        /** Assets a candidate reads or writes when inverted; disjoint touch sets commute. */
        val touchSets = group.associateWith { event ->
            when (event) {
                is TimelineEvent.TradeEvent ->
                    (replays[event] as? TradeLedgerReplay.Classification.Replayable)
                        ?.let { replay -> setOf(replay.base, replay.quote) }
                        ?: emptySet()

                is TimelineEvent.RewardEvent -> setOf(Asset.normalizeLedgerAsset(event.event.asset).uppercase())

                is TimelineEvent.DailyCloseEvent -> emptySet()
            }
        }

        fun search(
            remaining: List<TimelineEvent>,
            balances: MutableMap<String, BigDecimal>,
            carries: MutableMap<String, BigDecimal>,
            path: List<TimelineEvent>,
        ) {
            if (++visitedNodes > SAME_INSTANT_SEARCH_NODE_LIMIT) {
                throw IllegalArgumentException(
                    "Same-instant ordering search exceeded its $SAME_INSTANT_SEARCH_NODE_LIMIT-node budget " +
                        "at ${group.first().timestamp}",
                )
            }
            if (remaining.isEmpty()) {
                solutions += SameInstantSolution(path, balances.toMap())
                return
            }
            val touches = remaining.associateWith { touchSets.getValue(it) }
            // Candidates whose inversion touches no asset any other candidate touches commute
            // with everything still queued: their position cannot change any complete solution's
            // terminal state. Emitting them canonically first and searching only the entangled
            // remainder keeps the enumeration bounded (a dust sweep of disjoint assets would
            // otherwise enumerate k! equivalent orders) without losing ambiguity detection.
            val free = remaining.filter { candidate ->
                val own = touches.getValue(candidate)
                remaining.none { other -> other != candidate && touches.getValue(other).any(own::contains) }
            }
            if (free.isNotEmpty()) {
                for (candidate in free) {
                    if (!tryReverseConstrainingEvent(candidate, balances, carries, replays)) return
                }
                search(
                    remaining.filter { it !in free },
                    balances,
                    carries,
                    path + free,
                )
                return
            }
            for (candidate in remaining) {
                val candidateBalances = balances.toMutableMap()
                val candidateCarries = carries.toMutableMap()
                if (tryReverseConstrainingEvent(candidate, candidateBalances, candidateCarries, replays)) {
                    search(
                        remaining.filter { it != candidate },
                        candidateBalances,
                        candidateCarries,
                        path + candidate,
                    )
                }
            }
        }

        search(
            constraining,
            runningBalances.toMutableMap(),
            reverseUncertainty.toMutableMap(),
            emptyList(),
        )
        return solutions
    }

    /**
     * Attempts one checkpointed event's exact reverse application; mutates only on success.
     * Compatibility uses the same bounded allowance contract as [TradeLedgerReplay], never a
     * separate tolerance: authoritative post checkpoints must match the running state.
     */
    private fun tryReverseConstrainingEvent(
        event: TimelineEvent,
        runningBalances: MutableMap<String, BigDecimal>,
        reverseUncertainty: MutableMap<String, BigDecimal>,
        replays: Map<TimelineEvent.TradeEvent, TradeLedgerReplay.Classification?>,
    ): Boolean = when (event) {
        is TimelineEvent.TradeEvent -> {
            val replay = replays[event] as? TradeLedgerReplay.Classification.Replayable ?: return false
            val effect = replay.ledgerEffect ?: return false
            // Mirror [reverseApplyTrade]: an uncheckpointed leg is seeded at zero so its
            // arithmetic inversion has a balance to work from.
            if (effect.baseCheckpoint == null) runningBalances.putIfAbsent(replay.base, BigDecimal.ZERO)
            if (effect.quoteCheckpoint == null) runningBalances.putIfAbsent(replay.quote, BigDecimal.ZERO)
            val baseCarry = reverseUncertainty[replay.base] ?: BigDecimal.ZERO
            val quoteCarry = reverseUncertainty[replay.quote] ?: BigDecimal.ZERO
            if (!TradeLedgerReplay.reverseApply(replay, runningBalances, baseCarry, quoteCarry)) return false
            reverseUncertainty[replay.base] =
                carriedUncertainty(baseCarry, effect.baseCheckpoint, effect.baseRoundingAllowance)
            reverseUncertainty[replay.quote] =
                carriedUncertainty(quoteCarry, effect.quoteCheckpoint, effect.quoteRoundingAllowance)
            true
        }

        is TimelineEvent.RewardEvent -> {
            val symbol = Asset.normalizeLedgerAsset(event.event.asset).uppercase()
            val current = runningBalances[symbol] ?: return false
            val carry = reverseUncertainty[symbol] ?: BigDecimal.ZERO
            if (!TradeLedgerReplay.matchesCheckpoint(current, event.event.balance, carry)) return false
            runningBalances[symbol] = event.event.balance.subtract(event.event.netBalanceDelta())
            reverseUncertainty[symbol] = AuthoritativeLedgerBalanceValidator.allowedDifference(event.event)
            true
        }

        is TimelineEvent.DailyCloseEvent -> true
    }

    /**
     * Picks the canonical member of an economically identical solution set: lexicographically
     * smallest checkpoint-key sequence, independent of repository/input order.
     */
    private fun canonicalWinner(solutions: List<SameInstantSolution>): SameInstantSolution {
        var best = solutions.first()
        var bestKeys = best.order.map(::sameInstantSortKey)
        for (candidate in solutions.drop(1)) {
            val candidateKeys = candidate.order.map(::sameInstantSortKey)
            if (compareKeySequences(candidateKeys, bestKeys) < 0) {
                best = candidate
                bestKeys = candidateKeys
            }
        }
        return best
    }

    private fun compareKeySequences(left: List<String>, right: List<String>): Int {
        for (index in 0 until minOf(left.size, right.size)) {
            val compared = left[index].compareTo(right[index])
            if (compared != 0) return compared
        }
        return left.size.compareTo(right.size)
    }

    /** True when two solver outcomes leave every asset at the same balance. */
    private fun sameTerminalState(first: SameInstantSolution, second: SameInstantSolution): Boolean =
        first.balances.keys == second.balances.keys &&
            first.balances.keys.all { key ->
                first.balances.getValue(key).compareTo(second.balances.getValue(key)) == 0
            }

    /**
     * Content-derived ordering key used only to pick deterministically among economically
     * identical orders; never chronology evidence.
     */
    private fun sameInstantSortKey(event: TimelineEvent): String = when (event) {
        is TimelineEvent.TradeEvent ->
            "trade:" + (event.trade.tradeId?.takeIf(String::isNotBlank) ?: event.trade.id.toString())

        is TimelineEvent.RewardEvent -> "reward:" + event.event.ledgerId

        is TimelineEvent.DailyCloseEvent -> "close"
    }

    private class SameInstantSolution(val order: List<TimelineEvent>, val balances: Map<String, BigDecimal>)

    private fun chainPoints(
        event: TimelineEvent,
        trackedSymbols: Set<String>,
        replays: Map<TimelineEvent.TradeEvent, TradeLedgerReplay.Classification?>,
        resolvedScopes: Map<String, AuthoritativeLedgerBalanceValidator.LedgerWalletScope> = emptyMap(),
    ): List<ChainPoint> = when (event) {
        is TimelineEvent.TradeEvent -> {
            val replay = replays[event] as? TradeLedgerReplay.Classification.Replayable
            val effect = replay?.ledgerEffect
            if (replay == null || effect == null) {
                emptyList()
            } else {
                listOfNotNull(
                    effect.baseCheckpoint?.takeIf { effect.baseNetDelta.signum() != 0 }?.let {
                        ChainPoint(replay.base, it.subtract(effect.baseNetDelta), it)
                    },
                    effect.quoteCheckpoint?.takeIf { effect.quoteNetDelta.signum() != 0 }?.let {
                        ChainPoint(replay.quote, it.subtract(effect.quoteNetDelta), it)
                    },
                )
            }
        }

        is TimelineEvent.RewardEvent -> {
            val symbol = Asset.normalizeLedgerAsset(event.event.asset).uppercase()
            val scope = resolvedScopes[event.event.ledgerId]
            val delta = event.event.netBalanceDelta()
            if (scope != null && scope != AuthoritativeLedgerBalanceValidator.LedgerWalletScope.SPOT) {
                emptyList()
            } else if (event.event.hasAuthoritativeBalance && delta.signum() != 0 && symbol in trackedSymbols) {
                listOf(ChainPoint(symbol, event.event.balance.subtract(delta), event.event.balance))
            } else {
                emptyList()
            }
        }

        is TimelineEvent.DailyCloseEvent -> emptyList()
    }

    private fun getPriceForTimestamp(
        symbol: String,
        timestamp: Instant,
        ohlcData: Map<String, List<Pair<Long, BigDecimal>>>,
        tradePrices: Map<String, List<Pair<Instant, BigDecimal>>>,
        currentPrices: Map<String, BigDecimal>,
    ): BigDecimal {
        if (symbol.equals(Asset.USD, ignoreCase = true)) return BigDecimal.ONE

        val prices = ohlcData[symbol.uppercase()].orEmpty().filter { it.second.signum() > 0 }
        if (prices.isNotEmpty()) {
            val targetSec = timestamp.epochSecond
            return prices.filter { it.first <= targetSec }
                .maxByOrNull { it.first }
                ?.second
                ?: prices.minBy { it.first }.second
        }

        val tPrices = tradePrices[symbol.uppercase()].orEmpty().filter { it.second.signum() > 0 }
        if (tPrices.isNotEmpty()) {
            return findClosest(
                tPrices,
                timestamp.toEpochMilli(),
                { it.first.toEpochMilli() },
                { it.second },
            )
        }

        return currentPrices[symbol.uppercase()]?.takeIf { it.signum() > 0 }
            ?: throw HistoricalPriceUnavailableException(
                "No trustworthy price for $symbol at $timestamp during historical reconstruction.",
            )
    }

    /** Validates the same source precedence used by snapshot calculation without a zero fallback. */
    fun requireTrustworthyPrice(
        symbol: String,
        timestamp: Instant,
        ohlcData: Map<String, List<Pair<Long, BigDecimal>>>,
        tradePrices: Map<String, List<Pair<Instant, BigDecimal>>>,
        currentPrices: Map<String, BigDecimal>,
    ): BigDecimal = getPriceForTimestamp(symbol, timestamp, ohlcData, tradePrices, currentPrices)

    private fun buildSnapshotsChronological(
        rawPoints: List<RawHistoricalPoint>,
        allocations: List<Allocation>,
        settings: Settings,
        currentAth: BigDecimal,
    ): List<PortfolioSnapshot> {
        val snapshotsChronological = mutableListOf<PortfolioSnapshot>()
        var runningAth = currentAth

        for (point in rawPoints.asReversed()) {
            val exactPortfolioValue = point.exactPortfolioValue
            runningAth = updateAthForPoint(runningAth, exactPortfolioValue)

            val drawdownPct = RebalancerEngine.calculateDrawdown(exactPortfolioValue, runningAth)
            val fiatDeploymentPct = RebalancerEngine.calculateFiatDeployment(drawdownPct, settings)
            val effectiveUsdTarget = RebalancerEngine.calculateEffectiveUsdTarget(fiatDeploymentPct, allocations)
            val cryptoScaleFactor = RebalancerEngine.calculateCryptoScaleFactor(effectiveUsdTarget, allocations)
            val minimumOrderSize = settings.minimumOrderSizeUSD

            val assetSnapshots = mutableMapOf<String, PortfolioSnapshot.AssetSnapshot>()
            for ((symbol, balance, price, valueUSD, targetPercent) in point.calculatedAssets) {
                val symbolAsset = Asset(symbol)
                val metrics =
                    PortfolioCalculations.calculateAssetMetrics(
                        symbol = symbolAsset,
                        baseTargetPercent = BigDecimal.valueOf(targetPercent),
                        currentValueUSD = valueUSD,
                        totalPortfolioValueUSD = exactPortfolioValue,
                        effectiveUsdTarget = effectiveUsdTarget,
                        cryptoScaleFactor = cryptoScaleFactor,
                        minimumOrderSizeUSD = minimumOrderSize,
                    )

                assetSnapshots[symbol] =
                    PortfolioCalculations.createAssetSnapshot(
                        symbol = symbol,
                        balance = balance,
                        price = price,
                        valueUSD = valueUSD,
                        metrics = metrics,
                    )
            }

            val snapshot =
                PortfolioSnapshot(
                    timestamp = point.timestamp,
                    totalValueUSD = exactPortfolioValue.setScale(PrecisionConstants.SCALE_USD, RoundingMode.HALF_UP),
                    assets = assetSnapshots,
                    actions = emptyList(),
                    drawdownPercent = drawdownPct,
                    fiatDeploymentPercent = fiatDeploymentPct,
                    effectiveUsdTargetPercent = effectiveUsdTarget,
                    // These rows are derived by replaying retained trades and ledgers; they are
                    // not a balance request captured from Kraken at this timestamp.
                    balancesObservedAt = null,
                )

            snapshotsChronological.add(snapshot)
        }

        return snapshotsChronological.asReversed()
    }

    private fun updateAthForPoint(currentAth: BigDecimal, pointValue: BigDecimal): BigDecimal =
        if (pointValue > currentAth) pointValue else currentAth

    private fun <T> findClosest(
        list: List<T>,
        targetTime: Long,
        timeExtractor: (T) -> Long,
        valueExtractor: (T) -> BigDecimal,
    ): BigDecimal {
        var closestValue = valueExtractor(list[0])
        var minDiff = abs(timeExtractor(list[0]) - targetTime)
        for (item in list) {
            val diff = abs(timeExtractor(item) - targetTime)
            if (diff < minDiff) {
                minDiff = diff
                closestValue = valueExtractor(item)
            }
        }
        return closestValue
    }
}
