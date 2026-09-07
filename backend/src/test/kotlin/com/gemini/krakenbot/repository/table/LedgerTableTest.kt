package com.gemini.krakenbot.repository.table

import com.gemini.krakenbot.TestFixtures
import com.gemini.krakenbot.config.DatabaseConfig
import com.gemini.krakenbot.model.KrakenApiConstants
import com.gemini.krakenbot.model.LedgerEvent
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.comparables.shouldBeEqualComparingTo
import io.kotest.matchers.shouldBe
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.math.BigDecimal
import java.time.Instant

@Suppress("unused")
class LedgerTableTest : StringSpec() {
    override fun isolationMode() = IsolationMode.InstancePerTest

    init {
        "applyTo and toModel round-trip all ledger event fields" {
            val db = DatabaseConfig.init(TestFixtures.MEMORY_)
            val original = LedgerEvent(
                ledgerId = "ledger-123",
                refid = "tx-456",
                time = Instant.parse("2026-06-15T12:00:00Z"),
                type = KrakenApiConstants.LEDGER_TYPE_STAKING,
                subtype = "auto-compound",
                aclass = "currency",
                asset = "DOT",
                amount = BigDecimal("5.50000000"),
                fee = BigDecimal("0.0010"),
                balance = BigDecimal("105.50000000"),
                hasAuthoritativeBalance = true,
            )

            transaction(db) {
                LedgerTable.insert {
                    LedgerTable.applyTo(it, original)
                }

                val row = LedgerTable.selectAll().single()
                val loaded = LedgerTable.toModel(row)

                loaded.ledgerId shouldBe original.ledgerId
                loaded.refid shouldBe original.refid
                loaded.time shouldBe original.time
                loaded.type shouldBe original.type
                loaded.subtype shouldBe original.subtype
                loaded.aclass shouldBe original.aclass
                loaded.asset shouldBe original.asset
                loaded.amount.shouldBeEqualComparingTo(original.amount)
                loaded.fee.shouldBeEqualComparingTo(original.fee)
                loaded.balance.shouldBeEqualComparingTo(original.balance)
                loaded.hasAuthoritativeBalance shouldBe true
                loaded.hasAuthoritativeFee shouldBe true
                loaded.hasValidFee shouldBe true

                LedgerTable.insert {
                    LedgerTable.applyTo(
                        it,
                        original.copy(
                            ledgerId = "ledger-8dp-fee",
                            fee = BigDecimal("0.01001234"),
                            balance = BigDecimal("105.48998766"),
                        ),
                    )
                }
                val eightDpFee = LedgerTable.selectAll()
                    .single { it[LedgerTable.ledgerId] == "ledger-8dp-fee" }
                    .let(LedgerTable::toModel)
                eightDpFee.fee.shouldBeEqualComparingTo(BigDecimal("0.01001234"))
                eightDpFee.balance.shouldBeEqualComparingTo(BigDecimal("105.48998766"))
                eightDpFee.hasAuthoritativeBalance shouldBe true
                eightDpFee.hasAuthoritativeFee shouldBe true

                LedgerTable.insert {
                    LedgerTable.applyTo(
                        it,
                        original.copy(
                            ledgerId = "ledger-invalid-fee",
                            fee = BigDecimal("-0.01000000"),
                            hasAuthoritativeFee = true,
                            hasValidFee = false,
                        ),
                    )
                }
                val invalidFee = LedgerTable.selectAll()
                    .single { it[LedgerTable.ledgerId] == "ledger-invalid-fee" }
                    .let(LedgerTable::toModel)
                invalidFee.hasAuthoritativeFee shouldBe true
                invalidFee.hasValidFee shouldBe false

                LedgerTable.insert {
                    LedgerTable.applyTo(
                        it,
                        original.copy(
                            ledgerId = "ledger-zero-balance",
                            balance = BigDecimal.ZERO,
                            hasAuthoritativeBalance = false,
                        ),
                    )
                }
                val zeroBalance = LedgerTable.selectAll()
                    .single { it[LedgerTable.ledgerId] == "ledger-zero-balance" }
                    .let(LedgerTable::toModel)
                zeroBalance.hasAuthoritativeBalance shouldBe false
                zeroBalance.hasAuthoritativeFee shouldBe true
                zeroBalance.hasValidFee shouldBe true
            }
        }
    }
}
