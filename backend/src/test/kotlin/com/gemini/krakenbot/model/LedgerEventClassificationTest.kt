package com.gemini.krakenbot.model

import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.math.BigDecimal
import java.time.Instant

class LedgerEventClassificationTest : StringSpec() {
    override fun isolationMode() = IsolationMode.InstancePerTest

    private fun event(type: String, subtype: String? = null) = LedgerEvent(
        ledgerId = "classification-$type-${subtype.orEmpty()}",
        time = Instant.parse("2026-10-11T12:00:00Z"),
        type = type,
        subtype = subtype,
        asset = Asset.BTC,
        amount = BigDecimal.ONE,
    )

    init {
        "reward classification recognizes legacy types and documented Earn and transfer subtypes" {
            listOf(
                event(KrakenApiConstants.LEDGER_TYPE_STAKING),
                event(KrakenApiConstants.LEDGER_TYPE_DIVIDEND),
                event(KrakenApiConstants.LEDGER_TYPE_REWARD),
                event(KrakenApiConstants.LEDGER_TYPE_EARN, " Reward "),
                event(KrakenApiConstants.LEDGER_TYPE_TRANSFER, "airdrop"),
                event(KrakenApiConstants.LEDGER_TYPE_TRANSFER, "reward"),
            ).forEach { LedgerEvent.isRewardEvent(it) shouldBe true }

            listOf(
                event(KrakenApiConstants.LEDGER_TYPE_EARN),
                event(KrakenApiConstants.LEDGER_TYPE_EARN, "interest"),
                event(KrakenApiConstants.LEDGER_TYPE_TRANSFER, "spottostaking"),
                event(KrakenApiConstants.LEDGER_TYPE_DEPOSIT, "reward"),
            ).forEach { LedgerEvent.isRewardEvent(it) shouldBe false }
        }
    }
}
