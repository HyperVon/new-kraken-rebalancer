package com.gemini.krakenbot.repository.table

import com.gemini.krakenbot.TestFixtures
import com.gemini.krakenbot.config.DatabaseConfig
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

class LedgerTableTest : StringSpec() {
    override fun isolationMode() = IsolationMode.InstancePerTest

    init {
        "applyTo and toModel round-trip all ledger event fields" {
            val db = DatabaseConfig.init(TestFixtures.MEMORY_)
            val original = LedgerEvent(
                ledgerId = "ledger-123",
                refid = "tx-456",
                time = Instant.parse("2026-06-15T12:00:00Z"),
                type = LedgerEvent.TYPE_STAKING,
                subtype = "auto-compound",
                aclass = "currency",
                asset = "DOT",
                amount = BigDecimal("5.50000000"),
                fee = BigDecimal("0.0010"),
                balance = BigDecimal("105.50000000"),
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
            }
        }
    }
}
