package com.gemini.krakenbot.service.impl.history

import com.gemini.krakenbot.model.Asset
import com.gemini.krakenbot.model.KrakenApiConstants
import com.gemini.krakenbot.model.LedgerEvent
import com.gemini.krakenbot.model.LedgerFlowClassifier
import com.gemini.krakenbot.util.PrecisionConstants
import java.math.BigDecimal
import java.time.Instant

/**
 * Validates Kraken's post-entry ledger balances without confusing distinct wallet scopes.
 *
 * Kraken's `balance` is authoritative for the ledger row's own scope, not a universal balance
 * for every row carrying the same asset code. Trade rows are included for continuity validation,
 * while [InceptionRecoveryService] still replays TradesHistory for trade economics. Rows sharing
 * an asset and timestamp are solved as a bounded group so the validator never relies on an
 * arbitrary ledger-id order.
 */
object AuthoritativeLedgerBalanceValidator {
    private const val SPOT_SCOPE = "spot"
    private const val STAKING_SCOPE = "staking"
    private const val FUTURES_SCOPE = "futures"
    private const val OPAQUE_STAKING_SCOPE_PREFIX = "staking-opaque-"
    private const val MAX_SAME_TIMESTAMP_GROUP_SIZE = 12
    private const val MAX_VALID_SOLUTIONS = 32
    private const val MAX_GROUP_SEARCH_NODES = 50_000
    private const val MAX_ASSET_SEARCH_NODES = 1_000_000
    private val ZERO = BigDecimal.ZERO
    private val TWO = BigDecimal("2")
    private val FOUR_DECIMAL_UNIT = BigDecimal("0.0001")
    private val BALANCE_ROUNDING_ALLOWANCE = BigDecimal.ONE
        .movePointLeft(PrecisionConstants.SCALE_CRYPTO)
        .divide(TWO)

    /**
     * Wallet scope resolved for a ledger row by the authoritative-balance search.
     *
     * Asset identity remains separate from this disposition: SOL in [SPOT] and SOL in [STAKING]
     * are different balance scopes even though they normalize to the same configured symbol.
     */
    enum class LedgerWalletScope {
        SPOT,
        STAKING,
        FUTURES,
        OPAQUE_STAKING,
    }

    data class ValidationResult(
        val authoritativeCheckpointCount: Int,
        val validatedCheckpointCount: Int,
        val tradeCheckpointCount: Int,
        val groupedEventCheckpointCount: Int,
        val sameTimestampCheckpointCount: Int,
        val flexibleCheckpointCount: Int,
        val nonAuthoritativeEventCount: Int,
        val scopeCount: Int,
        /** Resolved wallet scope for each ledger row that the validator could safely assign. */
        val resolvedScopes: Map<String, LedgerWalletScope> = emptyMap(),
        val failure: ValidationFailure? = null,
    ) {
        val isValid: Boolean get() = failure == null
    }

    /** A failure diagnostic contains no ledger IDs, refids, order IDs, or account identifiers. */
    data class ValidationFailure(
        val asset: String,
        val scope: String?,
        val previousTime: Instant?,
        val previousType: String?,
        val previousSubtype: String?,
        val currentTime: Instant,
        val currentType: String,
        val currentSubtype: String?,
        val expected: BigDecimal?,
        val observed: BigDecimal?,
        val delta: BigDecimal,
        val detail: String,
    ) {
        val diagnostic: String
            get() = buildString {
                append("inconsistent ledger balance")
                append(" asset=").append(sanitize(asset))
                append(" scope=").append(sanitize(scope ?: "unknown"))
                append(" time=").append(currentTime)
                append(" currentType=").append(sanitize(currentType))
                append(" currentSubtype=").append(sanitize(currentSubtype ?: ""))
                previousTime?.let {
                    append(" previousTime=").append(it)
                    append(" previousType=").append(sanitize(previousType ?: "unknown"))
                    append(" previousSubtype=").append(sanitize(previousSubtype ?: ""))
                }
                append(" expected=").append(format(expected))
                append(" observed=").append(format(observed))
                append(" delta=").append(format(delta))
                append(" detail=").append(sanitize(detail))
            }

        /** Compact metadata-safe form; the full sanitized evidence remains in [diagnostic]. */
        val reason: String
            get() = buildString {
                append("balance ").append(sanitize(asset).take(8))
                append(" e=").append(compact(expected))
                append(" o=").append(compact(observed))
                append(" d=").append(compact(delta))
            }
    }

    private data class ScopeState(val balance: BigDecimal, val time: Instant, val type: String, val subtype: String?)

    private data class Counters(
        val validatedCheckpointCount: Int = 0,
        val tradeCheckpointCount: Int = 0,
        val groupedEventCheckpointCount: Int = 0,
        val sameTimestampCheckpointCount: Int = 0,
        val flexibleCheckpointCount: Int = 0,
    )

    private data class ReplayState(
        val scopes: Map<String, ScopeState> = emptyMap(),
        val resolvedScopes: Map<String, LedgerWalletScope> = emptyMap(),
        val counters: Counters = Counters(),
    )

    private data class EventCandidate(val state: ReplayState, val scope: String)

    private data class EventApplication(val candidates: List<EventCandidate>, val failure: ValidationFailure?)

    private data class GroupSolution(val state: ReplayState)

    private data class GroupSearch(
        val solutions: List<GroupSolution>,
        val firstFailure: ValidationFailure?,
        val truncated: Boolean,
    )

    private data class AssetSearch(
        val solutions: List<ReplayState>,
        val firstFailure: ValidationFailure?,
        val truncated: Boolean,
    )

    private class SearchBudget(private val limit: Int) {
        private var consumed = 0

        fun tryConsume(): Boolean {
            if (consumed >= limit) return false
            consumed += 1
            return true
        }
    }

    /**
     * Validates every supplied ledger row. The result is independent of database access and can
     * therefore be used by both recovery and read-only forensic replay tests.
     */
    fun validate(events: List<LedgerEvent>): ValidationResult {
        val authoritativeCount = events.count(LedgerEvent::hasAuthoritativeBalance)
        val nonAuthoritativeCount = events.size - authoritativeCount
        if (events.isEmpty()) {
            return ValidationResult(
                authoritativeCheckpointCount = 0,
                validatedCheckpointCount = 0,
                tradeCheckpointCount = 0,
                groupedEventCheckpointCount = 0,
                sameTimestampCheckpointCount = 0,
                flexibleCheckpointCount = 0,
                nonAuthoritativeEventCount = 0,
                scopeCount = 0,
            )
        }

        val duplicateIds = events.groupingBy { it.ledgerId }.eachCount().any { (_, count) -> count > 1 }
        if (duplicateIds) {
            return invalid(
                events = events,
                failure = failure(
                    asset = normalizeAsset(events.first().asset),
                    scope = null,
                    previous = null,
                    current = events.first(),
                    expected = null,
                    observed = events.first().balance.takeIf { events.first().hasAuthoritativeBalance },
                    detail = "duplicate ledger identity",
                ),
            )
        }

        val invalidFee = events.firstOrNull {
            it.fee.signum() < 0 ||
                !it.hasValidFee ||
                (it.fee.signum() != 0 && !it.hasAuthoritativeFee)
        }
        if (invalidFee != null) {
            return invalid(
                events = events,
                failure = failure(
                    asset = normalizeAsset(invalidFee.asset),
                    scope = null,
                    previous = null,
                    current = invalidFee,
                    expected = null,
                    observed = invalidFee.balance.takeIf { invalidFee.hasAuthoritativeBalance },
                    detail = "invalid or non-authoritative fee",
                ),
            )
        }

        val invalidAmount = events.firstOrNull { !LedgerFlowClassifier.hasValidAmountShape(it) }
        if (invalidAmount != null) {
            return invalid(
                events = events,
                failure = failure(
                    asset = normalizeAsset(invalidAmount.asset),
                    scope = null,
                    previous = null,
                    current = invalidAmount,
                    expected = null,
                    observed = invalidAmount.balance.takeIf { invalidAmount.hasAuthoritativeBalance },
                    detail = "invalid or malformed ledger amount",
                ),
            )
        }

        val unsupportedLedger = events.firstOrNull { event ->
            event.type.trim().lowercase() !in SUPPORTED_LEDGER_TYPES
        }
        if (unsupportedLedger != null) {
            return invalid(
                events = events,
                failure = failure(
                    asset = normalizeAsset(unsupportedLedger.asset),
                    scope = null,
                    previous = null,
                    current = unsupportedLedger,
                    expected = null,
                    observed = unsupportedLedger.balance.takeIf { unsupportedLedger.hasAuthoritativeBalance },
                    detail = "unsupported ledger type",
                ),
            )
        }

        val eventsByRefid = events
            .mapNotNull { event ->
                event.refid?.trim()?.takeIf(String::isNotEmpty)?.let { refid -> refid to event }
            }
            .groupBy({ it.first }, { it.second })
        val incompleteConversion = events.firstOrNull { event ->
            if (!isConversion(event)) return@firstOrNull false
            val refid = event.refid?.trim()?.takeIf(String::isNotEmpty)
            refid == null || !LedgerFlowClassifier.isCompleteConversionGroup(eventsByRefid[refid].orEmpty())
        }
        if (incompleteConversion != null) {
            return invalid(
                events = events,
                failure = failure(
                    asset = normalizeAsset(incompleteConversion.asset),
                    scope = null,
                    previous = null,
                    current = incompleteConversion,
                    expected = null,
                    observed = incompleteConversion.balance.takeIf { incompleteConversion.hasAuthoritativeBalance },
                    detail = "conversion is not a complete linked two-leg group",
                ),
            )
        }
        val incompleteInternalTransfer = events.firstOrNull { event ->
            if (!LedgerFlowClassifier.isDocumentedInternalTransfer(event)) return@firstOrNull false
            val refid = event.refid?.trim()?.takeIf(String::isNotEmpty)
            refid == null || !LedgerFlowClassifier.isCompleteInternalTransferGroup(eventsByRefid[refid].orEmpty())
        }
        if (incompleteInternalTransfer != null) {
            return invalid(
                events = events,
                failure = failure(
                    asset = normalizeAsset(incompleteInternalTransfer.asset),
                    scope = null,
                    previous = null,
                    current = incompleteInternalTransfer,
                    expected = null,
                    observed = incompleteInternalTransfer.balance.takeIf {
                        incompleteInternalTransfer.hasAuthoritativeBalance
                    },
                    detail = "internal transfer is not a complete linked group",
                ),
            )
        }
        // The preceding check has already rejected every documented internal row without a
        // complete, nonblank-refid pair, so this pass only needs to find an unknown subtype scope.
        val unscopedInternalTransfer = events
            .filter(LedgerFlowClassifier::isDocumentedInternalTransfer)
            .firstOrNull { fixedScope(it) == null }
        if (unscopedInternalTransfer != null) {
            return invalid(
                events = events,
                failure = failure(
                    asset = normalizeAsset(unscopedInternalTransfer.asset),
                    scope = null,
                    previous = null,
                    current = unscopedInternalTransfer,
                    expected = null,
                    observed = unscopedInternalTransfer.balance.takeIf {
                        unscopedInternalTransfer.hasAuthoritativeBalance
                    },
                    detail = "internal transfer subtype has no known balance scope",
                ),
            )
        }

        val linkedGroupSizes = events
            .mapNotNull { it.refid?.trim()?.takeIf(String::isNotEmpty) }
            .groupingBy { it }
            .eachCount()
        var totalState = ReplayState()
        var scopeCount = 0
        val validationBudget = SearchBudget(MAX_ASSET_SEARCH_NODES)
        val eventsByAsset = events
            .groupBy { normalizeAsset(it.asset) }
            .toSortedMap()

        for ((asset, assetEvents) in eventsByAsset) {
            val eventsByTime = assetEvents.groupBy { it.time }.toSortedMap()
            val oversized = eventsByTime.values.firstOrNull { it.size > MAX_SAME_TIMESTAMP_GROUP_SIZE }
            if (oversized != null) {
                return invalid(
                    events = events,
                    failure = failure(
                        asset = asset,
                        scope = null,
                        previous = null,
                        current = oversized.first(),
                        expected = null,
                        observed = oversized.first().balance.takeIf { oversized.first().hasAuthoritativeBalance },
                        detail = "same-timestamp group exceeds bounded search",
                    ),
                    counters = totalState.counters,
                    scopeCount = scopeCount,
                )
            }

            val nonzeroDeltaIds = assetEvents
                .filter { it.netBalanceDelta().signum() != 0 }
                .map { it.ledgerId }
                .toSet()
            val search = solveAsset(
                asset = asset,
                groups = eventsByTime.values.toList(),
                linkedGroupSizes = linkedGroupSizes,
                validationBudget = validationBudget,
                nonzeroDeltaIds = nonzeroDeltaIds,
            )
            if (search.truncated) {
                return invalid(
                    events = events,
                    failure = failure(
                        asset = asset,
                        scope = null,
                        previous = null,
                        current = assetEvents.first(),
                        expected = null,
                        observed = assetEvents.first().balance.takeIf { assetEvents.first().hasAuthoritativeBalance },
                        detail = "balance scope search exceeds bounded alternatives",
                    ),
                    counters = totalState.counters,
                    scopeCount = scopeCount,
                )
            }
            if (search.solutions.isEmpty()) {
                return invalid(
                    events = events,
                    failure = search.firstFailure ?: failure(
                        asset = asset,
                        scope = null,
                        previous = null,
                        current = assetEvents.first(),
                        expected = null,
                        observed = assetEvents.first().balance.takeIf { assetEvents.first().hasAuthoritativeBalance },
                        detail = "no valid balance-continuity path",
                    ),
                    counters = totalState.counters,
                    scopeCount = scopeCount,
                )
            }
            if (search.solutions.map(::aggregateBalanceSignature).distinct().size > 1) {
                return invalid(
                    events = events,
                    failure = failure(
                        asset = asset,
                        scope = null,
                        previous = null,
                        current = assetEvents.first(),
                        expected = null,
                        observed = assetEvents.first().balance.takeIf { assetEvents.first().hasAuthoritativeBalance },
                        detail = "ambiguous wallet scopes produce different aggregate balances",
                    ),
                    counters = totalState.counters,
                    scopeCount = scopeCount,
                )
            }
            if (search.solutions.map { replayScopeAssignmentSignature(it, nonzeroDeltaIds) }.distinct().size > 1) {
                return invalid(
                    events = events,
                    failure = failure(
                        asset = asset,
                        scope = null,
                        previous = null,
                        current = assetEvents.first(),
                        expected = null,
                        observed = assetEvents.first().balance.takeIf { assetEvents.first().hasAuthoritativeBalance },
                        detail = "ambiguous wallet scopes produce different replay semantics",
                    ),
                    counters = totalState.counters,
                    scopeCount = scopeCount,
                )
            }
            val state = search.solutions.minWith(
                compareBy<ReplayState> { it.scopes.size }.thenBy { it.signature(nonzeroDeltaIds) },
            )
            totalState = totalState.copy(
                resolvedScopes = totalState.resolvedScopes + state.resolvedScopes,
                counters = totalState.counters.merge(state.counters),
            )
            scopeCount += state.scopes.size
        }

        return ValidationResult(
            authoritativeCheckpointCount = authoritativeCount,
            validatedCheckpointCount = totalState.counters.validatedCheckpointCount,
            tradeCheckpointCount = totalState.counters.tradeCheckpointCount,
            groupedEventCheckpointCount = totalState.counters.groupedEventCheckpointCount,
            sameTimestampCheckpointCount = totalState.counters.sameTimestampCheckpointCount,
            flexibleCheckpointCount = totalState.counters.flexibleCheckpointCount,
            nonAuthoritativeEventCount = nonAuthoritativeCount,
            scopeCount = scopeCount,
            resolvedScopes = totalState.resolvedScopes,
        )
    }

    private fun solveGroup(
        asset: String,
        group: List<LedgerEvent>,
        initialState: ReplayState,
        linkedGroupSizes: Map<String, Int>,
        validationBudget: SearchBudget,
        nonzeroDeltaIds: Set<String>,
    ): GroupSearch {
        val solutions = mutableListOf<GroupSolution>()
        val failures = mutableListOf<ValidationFailure>()
        val sameTimestampGroup = group.size > 1
        val visitedStates = mutableSetOf<String>()
        var groupNodes = 0
        var truncated = false

        fun search(state: ReplayState, remaining: List<LedgerEvent>) {
            if (truncated) return
            val stateKey = buildString {
                append(state.signature(nonzeroDeltaIds))
                append("|")
                remaining.asSequence().map(LedgerEvent::ledgerId).sorted().joinTo(this, ",")
            }
            if (!visitedStates.add(stateKey)) return
            groupNodes += 1
            if (groupNodes > MAX_GROUP_SEARCH_NODES || !validationBudget.tryConsume()) {
                truncated = true
                return
            }
            if (remaining.isEmpty()) {
                if (solutions.none { it.state.signature(nonzeroDeltaIds) == state.signature(nonzeroDeltaIds) }) {
                    solutions += GroupSolution(state)
                    if (solutions.size > MAX_VALID_SOLUTIONS) truncated = true
                }
                return
            }
            for (event in remaining) {
                val application = applyEvent(
                    asset = asset,
                    event = event,
                    state = state,
                    sameTimestampGroup = sameTimestampGroup,
                    linkedGroup = event.refid?.trim()?.let { linkedGroupSizes[it] ?: 0 } ?: 0,
                )
                if (application.candidates.isEmpty()) {
                    application.failure?.let(failures::add)
                    continue
                }
                val nextRemaining = remaining - event
                for (candidate in application.candidates) {
                    search(
                        state = candidate.state,
                        remaining = nextRemaining,
                    )
                    if (truncated) return
                }
            }
        }

        search(initialState, group)
        return GroupSearch(
            solutions = solutions,
            firstFailure = failures.firstOrNull(),
            truncated = truncated,
        )
    }

    private fun solveAsset(
        asset: String,
        groups: List<List<LedgerEvent>>,
        linkedGroupSizes: Map<String, Int>,
        validationBudget: SearchBudget,
        nonzeroDeltaIds: Set<String>,
    ): AssetSearch {
        val solutions = mutableListOf<ReplayState>()
        val failures = mutableListOf<ValidationFailure>()
        val visitedStates = mutableSetOf<String>()
        var truncated = false

        fun search(index: Int, state: ReplayState) {
            if (truncated) return
            if (!visitedStates.add("$index|${state.signature(nonzeroDeltaIds)}")) return
            if (!validationBudget.tryConsume()) {
                truncated = true
                return
            }
            if (index == groups.size) {
                if (solutions.none { it.signature(nonzeroDeltaIds) == state.signature(nonzeroDeltaIds) }) {
                    solutions += state
                    if (solutions.size > MAX_VALID_SOLUTIONS) truncated = true
                }
                return
            }
            val groupSearch = solveGroup(
                asset = asset,
                group = groups[index],
                initialState = state,
                linkedGroupSizes = linkedGroupSizes,
                validationBudget = validationBudget,
                nonzeroDeltaIds = nonzeroDeltaIds,
            )
            if (groupSearch.truncated) {
                truncated = true
                return
            }
            if (groupSearch.solutions.isEmpty()) {
                groupSearch.firstFailure?.let(failures::add)
                return
            }
            for (solution in groupSearch.solutions) {
                search(index + 1, solution.state)
                if (truncated) return
            }
        }

        search(index = 0, state = ReplayState())
        return AssetSearch(
            solutions = solutions,
            firstFailure = failures.firstOrNull(),
            truncated = truncated,
        )
    }

    private fun ReplayState.signature(nonzeroDeltaIds: Set<String>): String = buildString {
        scopes.toSortedMap().forEach { (scope, state) ->
            append(scope).append('=').append(state.balance.toPlainString()).append(';')
        }
        append('#')
        resolvedScopes.entries
            .filter { it.key in nonzeroDeltaIds }
            .sortedBy { it.key }
            .forEach { (id, scope) ->
                append(id).append('=').append(scope.name).append(';')
            }
    }

    private fun replayScopeAssignmentSignature(state: ReplayState, nonzeroDeltaIds: Set<String>): String =
        state.resolvedScopes.entries
            .filter { it.key in nonzeroDeltaIds }
            .sortedBy { it.key }
            .joinToString("|") { (ledgerId, scope) ->
                "$ledgerId=${scope.name}"
            }

    private fun aggregateBalanceSignature(state: ReplayState): String = state.scopes.values
        .fold(ZERO) { total, scope -> total.add(scope.balance) }
        .stripTrailingZeros()
        .toPlainString()

    private fun applyEvent(
        asset: String,
        event: LedgerEvent,
        state: ReplayState,
        sameTimestampGroup: Boolean,
        linkedGroup: Int,
    ): EventApplication {
        val flexibleKind = flexibleKind(event)
        if (!event.hasAuthoritativeBalance) {
            val fixed = fixedScope(event)
            when (flexibleKind) {
                FlexibleKind.STAKING -> {
                    // A staking row without a balance cannot identify a wallet scope. Preserve
                    // its economics for replay elsewhere, but do not invent a checkpoint scope.
                    if (state.scopes.size == 1) {
                        return applyToScope(
                            asset = asset,
                            event = event,
                            state = state,
                            scope = state.scopes.keys.single(),
                            sameTimestampGroup = sameTimestampGroup,
                            linkedGroup = linkedGroup,
                            flexible = false,
                            allowNonAuthoritativeZero = false,
                        )
                    }
                    return skippedNonAuthoritative(state)
                }

                FlexibleKind.DUST -> {
                    // A dust sweep can be used only when exactly one existing scope closes. If no
                    // scope fits, it is a non-authoritative row with no usable checkpoint; if
                    // several scopes fit, accepting it would hide an ambiguous balance mutation.
                    val candidates = state.scopes.keys.sorted().flatMap { scope ->
                        applyToScope(
                            asset = asset,
                            event = event,
                            state = state,
                            scope = scope,
                            sameTimestampGroup = sameTimestampGroup,
                            linkedGroup = linkedGroup,
                            flexible = true,
                            allowNonAuthoritativeZero = true,
                        ).candidates
                    }
                    if (candidates.size == 1) {
                        return EventApplication(candidates, null)
                    }
                    if (candidates.size > 1) {
                        return EventApplication(
                            candidates = emptyList(),
                            failure = failure(
                                asset = asset,
                                scope = null,
                                previous = null,
                                current = event,
                                expected = null,
                                observed = null,
                                detail = "non-authoritative sweep has ambiguous wallet scope",
                            ),
                        )
                    }
                    return skippedNonAuthoritative(state)
                }

                null -> {
                    if (fixed != null && fixed in state.scopes) {
                        return applyToScope(
                            asset = asset,
                            event = event,
                            state = state,
                            scope = fixed,
                            sameTimestampGroup = sameTimestampGroup,
                            linkedGroup = linkedGroup,
                            flexible = false,
                            allowNonAuthoritativeZero = false,
                        )
                    }
                    return skippedNonAuthoritative(
                        state = state,
                        event = event,
                        scope = fixed.takeIf { LedgerFlowClassifier.isDocumentedInternalTransfer(event) },
                    )
                }
            }
        }
        if (flexibleKind != null) {
            val candidates = mutableListOf<EventCandidate>()
            val failures = mutableListOf<ValidationFailure>()
            val candidateScopes = if (flexibleKind == FlexibleKind.STAKING) {
                buildList {
                    addAll(
                        state.scopes.keys.filter {
                            isStakingScope(it) &&
                                state.scopes.getValue(it).balance.signum() != 0
                        },
                    )
                    // Some Kraken staking rows continue the Spot balance scope while other rows
                    // start a zero-based staking sub-ledger. Both are retained as candidates and
                    // resolved across later checkpoints rather than by a timestamp tie-breaker.
                    if (SPOT_SCOPE in state.scopes) add(SPOT_SCOPE)
                }.distinct().sorted()
            } else {
                state.scopes.keys.sorted()
            }
            for (scope in candidateScopes) {
                val application = applyToScope(
                    asset = asset,
                    event = event,
                    state = state,
                    scope = scope,
                    sameTimestampGroup = sameTimestampGroup,
                    linkedGroup = linkedGroup,
                    flexible = true,
                    allowNonAuthoritativeZero = flexibleKind == FlexibleKind.DUST,
                )
                candidates += application.candidates
                application.failure?.let(failures::add)
            }
            // Keep every wallet-scope candidate. A canonical staking scope is a useful preference
            // only when it remains compatible with later checkpoints; discarding Spot here can
            // reject valid histories where Kraken continues the Spot scope for staking activity.
            val preferredCandidates = candidates.toMutableList()

            if (flexibleKind == FlexibleKind.STAKING && preferredCandidates.isEmpty() &&
                event.hasAuthoritativeBalance &&
                within(event.netBalanceDelta(), event.balance, allowedDifference(event))
            ) {
                val scope = nextOpaqueStakingScope(state.scopes)
                preferredCandidates += applyToScope(
                    asset = asset,
                    event = event,
                    state = state,
                    scope = scope,
                    sameTimestampGroup = sameTimestampGroup,
                    linkedGroup = linkedGroup,
                    flexible = true,
                    allowNonAuthoritativeZero = false,
                ).candidates
            }

            if (flexibleKind == FlexibleKind.DUST && event.hasAuthoritativeBalance &&
                state.scopes.isEmpty()
            ) {
                preferredCandidates += applyToScope(
                    asset = asset,
                    event = event,
                    state = state,
                    scope = SPOT_SCOPE,
                    sameTimestampGroup = sameTimestampGroup,
                    linkedGroup = linkedGroup,
                    flexible = true,
                    allowNonAuthoritativeZero = true,
                ).candidates
            }

            if (flexibleKind == FlexibleKind.DUST && event.hasAuthoritativeBalance &&
                preferredCandidates.map { aggregateBalanceSignature(it.state) }.distinct().size > 1
            ) {
                return EventApplication(
                    candidates = emptyList(),
                    failure = failure(
                        asset = asset,
                        scope = null,
                        previous = null,
                        current = event,
                        expected = null,
                        observed = event.balance,
                        detail = "authoritative sweep has ambiguous wallet scope and aggregate balance",
                    ),
                )
            }

            return EventApplication(
                candidates = preferredCandidates.distinctBy { it.scope to it.state },
                failure = if (preferredCandidates.isEmpty()) {
                    failures.firstOrNull() ?: failure(
                        asset = asset,
                        scope = null,
                        previous = null,
                        current = event,
                        expected = null,
                        observed = event.balance.takeIf { event.hasAuthoritativeBalance },
                        detail = "flexible ledger row cannot be assigned to a wallet scope",
                    )
                } else {
                    null
                },
            )
        }

        val scope = fixedScope(event)
        if (scope == null) {
            return EventApplication(
                candidates = emptyList(),
                failure = failure(
                    asset = asset,
                    scope = null,
                    previous = null,
                    current = event,
                    expected = null,
                    observed = event.balance.takeIf { event.hasAuthoritativeBalance },
                    detail = "internal transfer subtype has no known balance scope",
                ),
            )
        }
        return applyToScope(
            asset = asset,
            event = event,
            state = state,
            scope = scope,
            sameTimestampGroup = sameTimestampGroup,
            linkedGroup = linkedGroup,
            flexible = false,
            allowNonAuthoritativeZero = false,
        )
    }

    private fun skippedNonAuthoritative(
        state: ReplayState,
        event: LedgerEvent? = null,
        scope: String? = null,
    ): EventApplication {
        val nextState = if (event != null && scope != null) {
            state.copy(resolvedScopes = state.resolvedScopes + (event.ledgerId to ledgerWalletScope(scope)))
        } else {
            state
        }
        return EventApplication(
            candidates = listOf(EventCandidate(nextState, scope ?: "unvalidated")),
            failure = null,
        )
    }

    private fun applyToScope(
        asset: String,
        event: LedgerEvent,
        state: ReplayState,
        scope: String,
        sameTimestampGroup: Boolean,
        linkedGroup: Int,
        flexible: Boolean,
        allowNonAuthoritativeZero: Boolean,
    ): EventApplication {
        val previous = state.scopes[scope]
        if (previous == null && !event.hasAuthoritativeBalance) {
            return EventApplication(
                candidates = emptyList(),
                failure = failure(
                    asset = asset,
                    scope = scope,
                    previous = null,
                    current = event,
                    expected = null,
                    observed = null,
                    detail = "non-authoritative row cannot start a balance scope",
                ),
            )
        }

        val expected = previous?.balance?.add(event.netBalanceDelta())
        if (previous != null) {
            when {
                event.hasAuthoritativeBalance &&
                    !within(expected!!, event.balance, allowedDifference(event)) -> {
                    return EventApplication(
                        candidates = emptyList(),
                        failure = failure(
                            asset = asset,
                            scope = scope,
                            previous = previous,
                            current = event,
                            expected = expected,
                            observed = event.balance,
                            detail = if (flexible) {
                                "wallet scope candidate does not reconcile"
                            } else {
                                "post-entry balance does not reconcile"
                            },
                        ),
                    )
                }

                !event.hasAuthoritativeBalance && allowNonAuthoritativeZero &&
                    !within(expected!!, ZERO, allowedDifference(event)) -> {
                    return EventApplication(
                        candidates = emptyList(),
                        failure = failure(
                            asset = asset,
                            scope = scope,
                            previous = previous,
                            current = event,
                            expected = expected,
                            observed = null,
                            detail = "non-authoritative sweep does not close the wallet scope",
                        ),
                    )
                }

                !event.hasAuthoritativeBalance && flexible && !allowNonAuthoritativeZero -> {
                    return EventApplication(
                        candidates = emptyList(),
                        failure = failure(
                            asset = asset,
                            scope = scope,
                            previous = previous,
                            current = event,
                            expected = expected,
                            observed = null,
                            detail = "non-authoritative flexible row cannot identify a scope",
                        ),
                    )
                }
            }
        }

        val nextBalance = if (event.hasAuthoritativeBalance) event.balance else expected!!
        val nextState = state.copy(
            scopes = state.scopes + (
                scope to ScopeState(
                    balance = nextBalance,
                    time = event.time,
                    type = event.type,
                    subtype = event.subtype,
                )
                ),
            resolvedScopes = state.resolvedScopes + (event.ledgerId to ledgerWalletScope(scope)),
            counters = state.counters.after(
                event = event,
                sameTimestampGroup = sameTimestampGroup,
                linkedGroup = linkedGroup,
                flexible = flexible,
            ),
        )
        return EventApplication(
            candidates = listOf(EventCandidate(nextState, scope)),
            failure = null,
        )
    }

    private fun Counters.after(
        event: LedgerEvent,
        sameTimestampGroup: Boolean,
        linkedGroup: Int,
        flexible: Boolean,
    ): Counters = copy(
        validatedCheckpointCount = validatedCheckpointCount + if (event.hasAuthoritativeBalance) 1 else 0,
        tradeCheckpointCount = tradeCheckpointCount +
            if (event.hasAuthoritativeBalance && isTrade(event)) 1 else 0,
        groupedEventCheckpointCount = groupedEventCheckpointCount +
            if (event.hasAuthoritativeBalance && linkedGroup > 1) 1 else 0,
        sameTimestampCheckpointCount = sameTimestampCheckpointCount +
            if (event.hasAuthoritativeBalance && sameTimestampGroup) 1 else 0,
        flexibleCheckpointCount = flexibleCheckpointCount + if (flexible) 1 else 0,
    )

    private fun Counters.merge(other: Counters): Counters = Counters(
        validatedCheckpointCount = validatedCheckpointCount + other.validatedCheckpointCount,
        tradeCheckpointCount = tradeCheckpointCount + other.tradeCheckpointCount,
        groupedEventCheckpointCount = groupedEventCheckpointCount + other.groupedEventCheckpointCount,
        sameTimestampCheckpointCount = sameTimestampCheckpointCount + other.sameTimestampCheckpointCount,
        flexibleCheckpointCount = flexibleCheckpointCount + other.flexibleCheckpointCount,
    )

    private enum class FlexibleKind {
        STAKING,
        DUST,
    }

    private fun flexibleKind(event: LedgerEvent): FlexibleKind? = when {
        event.type.equals(KrakenApiConstants.LEDGER_TYPE_STAKING, ignoreCase = true) ->
            FlexibleKind.STAKING

        isDustSweep(event) -> FlexibleKind.DUST

        else -> null
    }

    /**
     * Maps only documented transfer markers to their balance scope. A bare transfer remains a
     * normal Spot event because it may be an external airdrop or promotion credit.
     */
    private fun fixedScope(event: LedgerEvent): String? {
        if (!LedgerFlowClassifier.isDocumentedInternalScopeMarker(event)) return SPOT_SCOPE
        if (!LedgerFlowClassifier.isDocumentedInternalTransfer(event)) return null
        val subtype = normalizeSubtype(event.subtype)
        return when (subtype) {
            "spottostaking", "stakingfromspot" ->
                if (event.netBalanceDelta().signum() < 0) SPOT_SCOPE else STAKING_SCOPE

            "stakingtospot", "spotfromstaking" ->
                if (event.netBalanceDelta().signum() < 0) STAKING_SCOPE else SPOT_SCOPE

            "spottofutures" -> if (event.netBalanceDelta().signum() < 0) SPOT_SCOPE else FUTURES_SCOPE

            "spotfromfutures" -> if (event.netBalanceDelta().signum() < 0) FUTURES_SCOPE else SPOT_SCOPE

            "spottospot", "spotfromspot" -> SPOT_SCOPE

            else -> null
        }
    }

    private fun isDustSweep(event: LedgerEvent): Boolean = (
        event.type.equals(KrakenApiConstants.LEDGER_TYPE_SPEND, ignoreCase = true) ||
            event.type.equals(KrakenApiConstants.LEDGER_TYPE_RECEIVE, ignoreCase = true)
        ) && normalizeSubtype(event.subtype) == "dustsweeping"

    private fun isTrade(event: LedgerEvent): Boolean =
        event.type.equals(KrakenApiConstants.LEDGER_TYPE_TRADE, ignoreCase = true)

    private fun isConversion(event: LedgerEvent): Boolean =
        event.type.equals(KrakenApiConstants.LEDGER_TYPE_CONVERSION, ignoreCase = true)

    private fun nextOpaqueStakingScope(scopes: Map<String, ScopeState>): String {
        var index = 1
        while ("$OPAQUE_STAKING_SCOPE_PREFIX$index" in scopes) index += 1
        return "$OPAQUE_STAKING_SCOPE_PREFIX$index"
    }

    private fun isStakingScope(scope: String): Boolean =
        scope == STAKING_SCOPE || scope.startsWith(OPAQUE_STAKING_SCOPE_PREFIX)

    private fun ledgerWalletScope(scope: String): LedgerWalletScope = when {
        scope == SPOT_SCOPE -> LedgerWalletScope.SPOT
        scope == STAKING_SCOPE -> LedgerWalletScope.STAKING
        scope == FUTURES_SCOPE -> LedgerWalletScope.FUTURES
        scope.startsWith(OPAQUE_STAKING_SCOPE_PREFIX) -> LedgerWalletScope.OPAQUE_STAKING
        else -> error("unknown ledger wallet scope")
    }

    private fun allowedDifference(event: LedgerEvent): BigDecimal =
        BALANCE_ROUNDING_ALLOWANCE.add(feeRoundingAllowance(event))

    /**
     * Existing SQLite databases contain both four-decimal fees and eight-decimal fees. A fee
     * whose value is exactly representable at four decimals may also arrive padded to eight by
     * the parser, so the legacy envelope is selected by value as well as stored scale.
     */
    private fun feeRoundingAllowance(event: LedgerEvent): BigDecimal {
        if (event.fee.signum() == 0) return ZERO
        val isLegacyPrecision = if (event.fee.scale() <= PrecisionConstants.SCALE_FEE) {
            true
        } else {
            event.fee.remainder(FOUR_DECIMAL_UNIT).signum() == 0
        }
        val scale = if (isLegacyPrecision) PrecisionConstants.SCALE_FEE else event.fee.scale()
        return BigDecimal.ONE.movePointLeft(scale).divide(TWO)
    }

    private fun within(first: BigDecimal, second: BigDecimal, tolerance: BigDecimal): Boolean =
        first.subtract(second).abs().compareTo(tolerance) <= 0

    private fun normalizeAsset(asset: String): String = Asset.normalizeLedgerAsset(asset).uppercase()

    private fun normalizeSubtype(subtype: String?): String = subtype.orEmpty()
        .lowercase()
        .replace("_", "")
        .replace("-", "")
        .replace(" ", "")

    private fun invalid(
        events: List<LedgerEvent>,
        failure: ValidationFailure,
        counters: Counters = Counters(),
        scopeCount: Int = 0,
    ): ValidationResult = ValidationResult(
        authoritativeCheckpointCount = events.count(LedgerEvent::hasAuthoritativeBalance),
        validatedCheckpointCount = counters.validatedCheckpointCount,
        tradeCheckpointCount = counters.tradeCheckpointCount,
        groupedEventCheckpointCount = counters.groupedEventCheckpointCount,
        sameTimestampCheckpointCount = counters.sameTimestampCheckpointCount,
        flexibleCheckpointCount = counters.flexibleCheckpointCount,
        nonAuthoritativeEventCount = events.count { !it.hasAuthoritativeBalance },
        scopeCount = scopeCount,
        failure = failure,
    )

    private fun failure(
        asset: String,
        scope: String?,
        previous: ScopeState?,
        current: LedgerEvent,
        expected: BigDecimal?,
        observed: BigDecimal?,
        detail: String,
    ): ValidationFailure = ValidationFailure(
        asset = asset,
        scope = scope,
        previousTime = previous?.time,
        previousType = previous?.type,
        previousSubtype = previous?.subtype,
        currentTime = current.time,
        currentType = current.type,
        currentSubtype = current.subtype,
        expected = expected,
        observed = observed,
        delta = current.netBalanceDelta(),
        detail = detail,
    )

    private fun format(value: BigDecimal?): String = value?.toPlainString() ?: "n/a"

    private fun compact(value: BigDecimal?): String = format(value).take(10)

    private fun sanitize(value: String): String = value
        .trim()
        .take(80)
        .replace(Regex("[^A-Za-z0-9_.:/+\\- ]"), "_")

    private val SUPPORTED_LEDGER_TYPES =
        (setOf(KrakenApiConstants.LEDGER_TYPE_TRADE) + LedgerEvent.EXTERNAL_BALANCE_TYPES)
            .map(String::lowercase)
            .toSet()
}
