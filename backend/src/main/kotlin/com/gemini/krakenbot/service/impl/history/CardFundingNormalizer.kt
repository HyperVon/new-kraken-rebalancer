package com.gemini.krakenbot.service.impl.history

import com.gemini.krakenbot.model.Asset
import com.gemini.krakenbot.model.AssetDelta
import com.gemini.krakenbot.model.CardFeePriceProvider
import com.gemini.krakenbot.model.FundingEvidence
import com.gemini.krakenbot.model.FundingProvenanceResolver
import com.gemini.krakenbot.model.KrakenApiConstants
import com.gemini.krakenbot.model.LedgerEvent
import com.gemini.krakenbot.model.NormalizedFundingTransaction
import com.gemini.krakenbot.util.PrecisionConstants
import org.slf4j.LoggerFactory
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Duration

/**
 * Normalizes multi-leg card and consumer funding transactions (e.g. card-funded Buy Crypto)
 * into a single economic owner-capital event.
 *
 * Both the high-water mark (ATH) scaling in [PortfolioAnalyzerImpl] and the synthetic Buy & Hold
 * counterfactual in [RebalancerComparisonCalculator] consume the normalized result identically:
 * - Candidate transactions are identified primarily by non-blank exact [LedgerEvent.refid].
 * - Bounded timestamp window ([MAX_CARD_TRANSACTION_SPAN_SECONDS]) ensures proximity without
 *   requiring byte-for-byte identical timestamps.
 * - Confirmed card purchases require complete transaction shapes: external deposit + USD spend
 *   + non-USD receive. Incomplete plumbing fails closed as [NormalizedFundingTransaction.Ambiguous].
 * - Cross-asset fees are converted to USD at event-time historical prices before subtraction.
 * - Synthetic Buy & Hold invests [NormalizedFundingTransaction.OwnerContribution.netOwnerCapitalUsd]
 *   strictly by original inception weights; actual asset conversion legs are consumed as plumbing
 *   evidence and are never replayed.
 */
object CardFundingNormalizer {
    private val log = LoggerFactory.getLogger(CardFundingNormalizer::class.java)

    /** Maximum allowed time span between legs sharing a refid before treating as ambiguous. */
    const val MAX_CARD_TRANSACTION_SPAN_SECONDS: Long = 120L
    val MAX_CARD_TRANSACTION_SPAN: Duration = Duration.ofSeconds(MAX_CARD_TRANSACTION_SPAN_SECONDS)

    fun isFundingLeg(event: LedgerEvent): Boolean =
        event.type.equals(KrakenApiConstants.LEDGER_TYPE_DEPOSIT, ignoreCase = true) ||
            event.type.equals(KrakenApiConstants.LEDGER_TYPE_WITHDRAWAL, ignoreCase = true)

    fun isSpendLeg(event: LedgerEvent): Boolean =
        event.type.equals(KrakenApiConstants.LEDGER_TYPE_SPEND, ignoreCase = true)

    fun isReceiveLeg(event: LedgerEvent): Boolean =
        event.type.equals(KrakenApiConstants.LEDGER_TYPE_RECEIVE, ignoreCase = true)

    fun isPassthroughLeg(event: LedgerEvent): Boolean = isSpendLeg(event) || isReceiveLeg(event)

    fun isUsd(asset: String): Boolean {
        val norm = Asset.normalizeLedgerAsset(asset).uppercase()
        return norm == Asset.USD || norm == "ZUSD"
    }

    /**
     * Groups raw ledger events by exact non-blank refid.
     * Rows without a refid are excluded and must never be joined with others.
     */
    fun identifyCandidateGroups(events: Collection<LedgerEvent>): Map<String, List<LedgerEvent>> =
        events.filter { !it.refid.isNullOrBlank() }
            .groupBy { it.refid!!.trim() }

    /**
     * Normalizes all candidate card funding groups in [events].
     */
    suspend fun normalizeAll(
        events: Collection<LedgerEvent>,
        provenanceResolver: FundingProvenanceResolver,
        priceProvider: CardFeePriceProvider,
    ): List<NormalizedFundingTransaction> {
        val groups = identifyCandidateGroups(events)
        if (groups.isEmpty()) return emptyList()

        val results = mutableListOf<NormalizedFundingTransaction>()
        for ((refid, group) in groups) {
            val normalized = normalizeGroup(refid, group, provenanceResolver, priceProvider)
            if (normalized !is NormalizedFundingTransaction.NotApplicable) {
                results.add(normalized)
            }
        }
        return results
    }

    /**
     * Normalizes a single group of ledger events sharing [refid].
     */
    suspend fun normalizeGroup(
        refid: String,
        group: List<LedgerEvent>,
        provenanceResolver: FundingProvenanceResolver,
        priceProvider: CardFeePriceProvider,
    ): NormalizedFundingTransaction {
        if (group.isEmpty()) return NormalizedFundingTransaction.NotApplicable

        val hasFunding = group.any(::isFundingLeg)
        val hasPassthrough = group.any(::isPassthroughLeg)

        // Only groups containing funding or plumbing legs are relevant to card normalization
        if (!hasFunding && !hasPassthrough) {
            return NormalizedFundingTransaction.NotApplicable
        }

        val minTime = group.minOf { it.time }
        val maxTime = group.maxOf { it.time }
        val span = Duration.between(minTime, maxTime).abs()

        // Proximity constraint: legs of a single transaction must be within bounded window
        if (span > MAX_CARD_TRANSACTION_SPAN) {
            log.warn(
                "Card funding legs with refid {} span {}s, exceeding maximum allowed span of {}s; failing closed",
                refid,
                span.seconds,
                MAX_CARD_TRANSACTION_SPAN_SECONDS,
            )
            return NormalizedFundingTransaction.Ambiguous(
                refid = refid,
                unavailableAt = maxTime,
                reason =
                "Legs with refid $refid span ${span.seconds}s, " +
                    "exceeding maximum allowed span of ${MAX_CARD_TRANSACTION_SPAN_SECONDS}s",
            )
        }

        // Validate allowed leg types
        val unexpectedLeg = group.firstOrNull { !isFundingLeg(it) && !isPassthroughLeg(it) }
        if (unexpectedLeg != null) {
            log.warn(
                "Card funding group with refid {} contains unexpected ledger type {}; failing closed",
                refid,
                unexpectedLeg.type,
            )
            return NormalizedFundingTransaction.Ambiguous(
                refid = refid,
                unavailableAt = unexpectedLeg.time,
                reason = "Group contains unexpected leg type: ${unexpectedLeg.type}",
            )
        }

        val fundingLegs = group.filter(::isFundingLeg)
        val spendLegs = group.filter(::isSpendLeg)
        val receiveLegs = group.filter(::isReceiveLeg)

        // Missing funding leg when passthrough exists: does not manufacture owner capital
        if (fundingLegs.isEmpty()) {
            return NormalizedFundingTransaction.NotApplicable
        }

        // Verify every funding leg before deciding whether the group is card
        // plumbing. A single unresolved sibling must never be silently dropped
        // from an otherwise external owner-capital event.
        val fundingEvidence = fundingLegs.associateWith(provenanceResolver::resolve)
        val externalFunding = fundingLegs.filter { fundingEvidence.getValue(it) == FundingEvidence.EXTERNAL }
        val internalFunding = fundingLegs.filter { fundingEvidence.getValue(it) == FundingEvidence.INTERNAL }
        val unresolvedFunding = fundingLegs.filter { fundingEvidence.getValue(it) == FundingEvidence.UNRESOLVED }
        val hasCardEvidence = fundingLegs.any(provenanceResolver::isCardFunding)

        if (externalFunding.isNotEmpty() && internalFunding.isNotEmpty()) {
            return NormalizedFundingTransaction.Ambiguous(
                refid = refid,
                unavailableAt = minTime,
                reason = "Funding group mixes external and internal funding provenance",
            )
        }

        if (unresolvedFunding.isNotEmpty() &&
            (externalFunding.isNotEmpty() || internalFunding.isNotEmpty())
        ) {
            log.warn("Card funding group with refid {} has unresolved funding siblings; failing closed", refid)
            return NormalizedFundingTransaction.Ambiguous(
                refid = refid,
                unavailableAt = minTime,
                reason = "Funding group contains unresolved funding provenance",
            )
        }

        // An all-internal group is not owner capital. An entirely unproven
        // plain funding row likewise remains outside this normalizer; the
        // classifier owns the separate decision to defer it.
        if (internalFunding.isNotEmpty()) {
            return NormalizedFundingTransaction.NotApplicable
        }

        if (externalFunding.isEmpty()) {
            return if (hasPassthrough || hasCardEvidence) {
                log.warn("Card funding group with refid {} has no proven external funding; failing closed", refid)
                NormalizedFundingTransaction.Ambiguous(
                    refid = refid,
                    unavailableAt = minTime,
                    reason = "Funding legs in card group cannot be proven external",
                )
            } else {
                NormalizedFundingTransaction.NotApplicable
            }
        }

        val isDeposit = externalFunding.any {
            it.type.equals(KrakenApiConstants.LEDGER_TYPE_DEPOSIT, ignoreCase = true)
        }
        val isWithdrawal = externalFunding.any {
            it.type.equals(KrakenApiConstants.LEDGER_TYPE_WITHDRAWAL, ignoreCase = true)
        }

        if (isDeposit && isWithdrawal) {
            return NormalizedFundingTransaction.Ambiguous(
                refid = refid,
                unavailableAt = minTime,
                reason = "Group contains conflicting deposit and withdrawal funding legs",
            )
        }

        if (externalFunding.size > 1) {
            return NormalizedFundingTransaction.Ambiguous(
                refid = refid,
                unavailableAt = minTime,
                reason = "Multiple external funding legs are unsupported for one normalized group",
            )
        }

        val hasNonUsdLeg = group.any { !isUsd(it.asset) }

        // Confirmed card/consumer funding must wait for its complete plumbing
        // shape. Ordinary confirmed Wire/ACH deposits remain simple owner
        // capital and are handled by LedgerFlowClassifier instead.
        if (spendLegs.isEmpty() && receiveLegs.isEmpty()) {
            return if (hasCardEvidence) {
                NormalizedFundingTransaction.Ambiguous(
                    refid = refid,
                    unavailableAt = minTime,
                    reason = "Confirmed card funding is missing spend and receive plumbing legs",
                )
            } else {
                NormalizedFundingTransaction.NotApplicable
            }
        }

        if (hasNonUsdLeg || hasCardEvidence) {
            // Mixed-asset card Buy Crypto or confirmed card-funded purchase
            if (isDeposit) {
                if (spendLegs.isEmpty()) {
                    log.warn("Card deposit with refid {} missing USD spend plumbing leg; failing closed", refid)
                    return NormalizedFundingTransaction.Ambiguous(
                        refid = refid,
                        unavailableAt = minTime,
                        reason = "Card deposit missing USD spend plumbing leg",
                    )
                }
                if (receiveLegs.isEmpty()) {
                    log.warn("Card deposit with refid {} missing crypto receive plumbing leg; failing closed", refid)
                    return NormalizedFundingTransaction.Ambiguous(
                        refid = refid,
                        unavailableAt = minTime,
                        reason = "Card deposit missing crypto receive plumbing leg",
                    )
                }
                if (fundingLegs.any { !isUsd(it.asset) }) {
                    return NormalizedFundingTransaction.Ambiguous(
                        refid = refid,
                        unavailableAt = minTime,
                        reason = "Card deposit funding leg must be USD",
                    )
                }
                if (spendLegs.any { !isUsd(it.asset) }) {
                    return NormalizedFundingTransaction.Ambiguous(
                        refid = refid,
                        unavailableAt = minTime,
                        reason = "Spend plumbing leg must be USD",
                    )
                }
                if (receiveLegs.any { isUsd(it.asset) }) {
                    return NormalizedFundingTransaction.Ambiguous(
                        refid = refid,
                        unavailableAt = minTime,
                        reason = "Receive plumbing leg must be non-USD for Buy Crypto",
                    )
                }
                if (fundingLegs.any { it.netBalanceDelta() <= BigDecimal.ZERO } ||
                    spendLegs.any { it.netBalanceDelta() >= BigDecimal.ZERO } ||
                    receiveLegs.any { it.netBalanceDelta() <= BigDecimal.ZERO }
                ) {
                    return NormalizedFundingTransaction.Ambiguous(
                        refid = refid,
                        unavailableAt = minTime,
                        reason = "Conflicting directions in card funding legs",
                    )
                }
            } else {
                if (receiveLegs.isEmpty()) {
                    return NormalizedFundingTransaction.Ambiguous(
                        refid = refid,
                        unavailableAt = minTime,
                        reason = "Card withdrawal missing receive plumbing leg",
                    )
                }
                if (spendLegs.isEmpty()) {
                    return NormalizedFundingTransaction.Ambiguous(
                        refid = refid,
                        unavailableAt = minTime,
                        reason = "Card withdrawal missing spend plumbing leg",
                    )
                }
                if (fundingLegs.any { it.netBalanceDelta() >= BigDecimal.ZERO } ||
                    receiveLegs.any { it.netBalanceDelta() <= BigDecimal.ZERO } ||
                    spendLegs.any { it.netBalanceDelta() >= BigDecimal.ZERO }
                ) {
                    return NormalizedFundingTransaction.Ambiguous(
                        refid = refid,
                        unavailableAt = minTime,
                        reason = "Conflicting directions in card withdrawal legs",
                    )
                }
            }

            // Currency-aware fee valuation across all legs
            var totalFeeUsd = BigDecimal.ZERO
            for (leg in group) {
                if (leg.fee > BigDecimal.ZERO) {
                    val feeAsset = Asset.normalizeLedgerAsset(leg.asset).uppercase()
                    val feeUsd = if (isUsd(feeAsset)) {
                        leg.fee
                    } else {
                        val price = priceProvider.getPrice(feeAsset, leg.time)
                        if (price == null || price <= BigDecimal.ZERO) {
                            log.warn("Cannot price fee asset {} at {} for card refid {}", feeAsset, leg.time, refid)
                            return NormalizedFundingTransaction.UnpriceableFee(
                                refid = refid,
                                asset = feeAsset,
                                unavailableAt = leg.time,
                            )
                        }
                        leg.fee.multiply(price).setScale(PrecisionConstants.SCALE_USD, RoundingMode.HALF_UP)
                    }
                    totalFeeUsd = totalFeeUsd.add(feeUsd)
                }
            }

            val grossFundingUsd = fundingLegs.fold(BigDecimal.ZERO) { acc, leg -> acc.add(leg.amount) }
            val representative = fundingLegs.minWith(compareBy({ it.time }, { it.ledgerId }))
            val sourceIds = group.map { it.ledgerId }.distinct()

            return if (isDeposit) {
                val netOwnerCapitalUsd = grossFundingUsd.subtract(totalFeeUsd)
                if (netOwnerCapitalUsd <= BigDecimal.ZERO) {
                    return NormalizedFundingTransaction.Ambiguous(
                        refid = refid,
                        unavailableAt = minTime,
                        reason = "Net capital after fees ($netOwnerCapitalUsd) is not positive for deposit",
                    )
                }
                NormalizedFundingTransaction.OwnerContribution(
                    refid = refid,
                    eventTime = representative.time,
                    grossFundingUsd = grossFundingUsd,
                    feeUsd = totalFeeUsd,
                    netOwnerCapitalUsd = netOwnerCapitalUsd,
                    actualPortfolioDeltas = actualPortfolioDeltas(group),
                    sourceLedgerIds = sourceIds,
                    representativeLedgerId = representative.ledgerId,
                )
            } else {
                val netOwnerCapitalUsd = grossFundingUsd.add(totalFeeUsd)
                if (netOwnerCapitalUsd >= BigDecimal.ZERO) {
                    return NormalizedFundingTransaction.Ambiguous(
                        refid = refid,
                        unavailableAt = minTime,
                        reason = "Net capital after fees ($netOwnerCapitalUsd) is not negative for withdrawal",
                    )
                }
                NormalizedFundingTransaction.OwnerWithdrawal(
                    refid = refid,
                    eventTime = representative.time,
                    grossFundingUsd = grossFundingUsd,
                    feeUsd = totalFeeUsd,
                    netOwnerCapitalUsd = netOwnerCapitalUsd,
                    actualPortfolioDeltas = actualPortfolioDeltas(group),
                    sourceLedgerIds = sourceIds,
                    representativeLedgerId = representative.ledgerId,
                )
            }
        }

        // USD-only funding plumbing
        val representative = fundingLegs.minWith(compareBy({ it.time }, { it.ledgerId }))
        val sourceIds = group.map { it.ledgerId }.distinct()
        val totalFeesUsd = group.fold(BigDecimal.ZERO) { acc, leg -> acc.add(leg.fee) }

        return if (isDeposit) {
            val grossFundingUsd = fundingLegs.fold(BigDecimal.ZERO) { acc, leg -> acc.add(leg.amount) }
            val totalSpendUsd = spendLegs.fold(BigDecimal.ZERO) { acc, leg -> acc.add(leg.amount.abs()) }
            val net = grossFundingUsd.subtract(totalSpendUsd).subtract(totalFeesUsd)
            if (net.signum() == 0) {
                log.warn("USD funding plumbing with refid {} nets to zero; cannot erase owner capital", refid)
                NormalizedFundingTransaction.Ambiguous(
                    refid = refid,
                    unavailableAt = minTime,
                    reason = "USD funding plumbing nets to zero; cannot erase owner capital",
                )
            } else if (net.signum() > 0) {
                NormalizedFundingTransaction.OwnerContribution(
                    refid = refid,
                    eventTime = representative.time,
                    grossFundingUsd = grossFundingUsd,
                    feeUsd = totalFeesUsd,
                    netOwnerCapitalUsd = net,
                    actualPortfolioDeltas = actualPortfolioDeltas(group),
                    sourceLedgerIds = sourceIds,
                    representativeLedgerId = representative.ledgerId,
                )
            } else {
                // Deposit with larger spend: keep the owner deposit and the
                // balance-changing spend separately typed rather than
                // inventing an owner withdrawal.
                NormalizedFundingTransaction.NotApplicable
            }
        } else {
            val net = fundingLegs.fold(BigDecimal.ZERO) { acc, leg -> acc.add(leg.netBalanceDelta()) }
                .add(receiveLegs.fold(BigDecimal.ZERO) { acc, leg -> acc.add(leg.netBalanceDelta()) })
            if (net.signum() == 0) {
                log.warn("USD withdrawal plumbing with refid {} nets to zero", refid)
                NormalizedFundingTransaction.Ambiguous(
                    refid = refid,
                    unavailableAt = minTime,
                    reason = "USD withdrawal plumbing nets to zero",
                )
            } else if (net.signum() < 0) {
                val grossFundingUsd = fundingLegs.fold(BigDecimal.ZERO) { acc, leg -> acc.add(leg.amount) }
                NormalizedFundingTransaction.OwnerWithdrawal(
                    refid = refid,
                    eventTime = representative.time,
                    grossFundingUsd = grossFundingUsd,
                    feeUsd = totalFeesUsd,
                    netOwnerCapitalUsd = net,
                    actualPortfolioDeltas = actualPortfolioDeltas(group),
                    sourceLedgerIds = sourceIds,
                    representativeLedgerId = representative.ledgerId,
                )
            } else {
                // Withdrawal with larger receive: keep the owner withdrawal
                // and the balance-changing receive separately typed rather
                // than inventing an owner contribution.
                NormalizedFundingTransaction.NotApplicable
            }
        }
    }

    private fun actualPortfolioDeltas(group: Collection<LedgerEvent>): List<AssetDelta> = group.map { event ->
        AssetDelta(
            asset = Asset.normalizeLedgerAsset(event.asset).uppercase(),
            amount = event.netBalanceDelta(),
        )
    }
}
