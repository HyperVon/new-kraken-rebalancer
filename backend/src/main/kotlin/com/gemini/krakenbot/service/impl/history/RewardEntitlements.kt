package com.gemini.krakenbot.service.impl.history

import com.gemini.krakenbot.model.KrakenApiConstants
import com.gemini.krakenbot.model.LedgerEvent

/**
 * Explicit, evidence-backed mapping of a holding-dependent reward asset to the qualifying
 * source exposure that generated it. A reward is mirrored into the synthetic Buy & Hold
 * portfolio only when that portfolio owns positive qualifying exposure immediately before
 * the reward event — not when it already happens to hold the reward asset itself.
 *
 * Same-asset rewards (reward asset == qualifying asset) need no entry here: the existing
 * held-reward-asset rule already covers them and keeps its behavior.
 *
 * Currently supported cross-asset product semantics:
 * - Kraken BTC staking is powered by Babylon and pays rewards in BABY, so a `staking`
 *   ledger row crediting BABY is economically a BTC-exposure reward. Reward asset is
 *   therefore NOT the staked asset in this chain: the qualifying source exposure is BTC
 *   even when B&H holds no BABY beforehand, allowing BABY to become a legitimate new
 *   synthetic holding received in kind.
 *
 * No entry means no supported cross-asset entitlement: unsupported reward assets stay
 * actual-only when the anchor does not hold them, never inferred from timing, amount,
 * price, balance correlation, symbol similarity, or cadence.
 */
object RewardEntitlements {
    data class Entitlement(
        val rewardAsset: String,
        val qualifyingAsset: String,
        /** True when the mapping credits an asset B&H may not already hold (new synthetic holding). */
        val crossAsset: Boolean,
    )

    private val supportedEntries: Map<String, Entitlement> = listOf(
        Entitlement(rewardAsset = "BABY", qualifyingAsset = "BTC", crossAsset = true),
    ).associateBy { it.rewardAsset.uppercase() }

    /**
     * The qualifying exposure asset for this reward event under a supported semantic rule,
     * or null when the reward asset has no documented cross-asset source (then the caller
     * falls back to the reward-asset-held rule).
     */
    fun qualifyingSourceAsset(event: LedgerEvent): String? = when (event.type.trim().lowercase()) {
        KrakenApiConstants.LEDGER_TYPE_STAKING ->
            supportedEntries[event.asset.trim().uppercase()]?.takeIf { it.crossAsset }?.qualifyingAsset

        else -> null
    }
}
