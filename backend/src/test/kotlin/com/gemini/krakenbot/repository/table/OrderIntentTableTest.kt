package com.gemini.krakenbot.repository.table

import com.gemini.krakenbot.TestFixtures
import com.gemini.krakenbot.config.DatabaseConfig
import com.gemini.krakenbot.model.OrderIntent
import com.gemini.krakenbot.model.OrderIntentState
import com.gemini.krakenbot.model.OrderSide
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
class OrderIntentTableTest : StringSpec() {
    override fun isolationMode() = IsolationMode.InstancePerTest

    init {
        "applyPending and toModel round-trip order intent fields" {
            val db = DatabaseConfig.init(TestFixtures.MEMORY_)
            val original = OrderIntent(
                cycleId = "cycle-789",
                clientOrderId = "cl-ord-abc",
                clientOrderIdAmbiguous = false,
                pair = "ETHUSD",
                symbol = "ETH",
                side = OrderSide.BUY.name,
                volume = BigDecimal("2.50000000"),
                usdAmount = BigDecimal("7500.00"),
                expectedPrice = BigDecimal("3000.00000000"),
                createdAt = Instant.parse("2026-07-02T15:30:00Z"),
                state = OrderIntentState.PENDING,
                localTradeId = 1,
            )

            transaction(db) {
                TradeTable.insert {
                    it[TradeTable.timestamp] = original.createdAt.toEpochMilli()
                    it[TradeTable.pair] = original.pair
                    it[TradeTable.side] = original.side
                    it[TradeTable.symbol] = original.symbol
                    it[TradeTable.volume] = original.volume
                    it[TradeTable.usdAmount] = original.usdAmount
                    it[TradeTable.success] = false
                    it[TradeTable.dryRun] = false
                    it[TradeTable.price] = original.expectedPrice ?: BigDecimal.ONE
                    it[TradeTable.fee] = BigDecimal.ZERO
                }
                val insertedId = OrderIntentTable.insert {
                    OrderIntentTable.applyPending(it, original)
                }[OrderIntentTable.id]

                val row = OrderIntentTable.selectAll().single()
                val loaded = OrderIntentTable.toModel(row)

                loaded.id shouldBe insertedId
                loaded.cycleId shouldBe original.cycleId
                loaded.clientOrderId shouldBe original.clientOrderId
                loaded.clientOrderIdAmbiguous shouldBe original.clientOrderIdAmbiguous
                loaded.pair shouldBe original.pair
                loaded.symbol shouldBe original.symbol
                loaded.side shouldBe original.side
                loaded.volume.shouldBeEqualComparingTo(original.volume)
                loaded.usdAmount.shouldBeEqualComparingTo(original.usdAmount)
                loaded.expectedPrice?.shouldBeEqualComparingTo(requireNotNull(original.expectedPrice))
                loaded.createdAt shouldBe original.createdAt
                loaded.state shouldBe OrderIntentState.PENDING
                loaded.orderTxid shouldBe null
                loaded.errorMessage shouldBe null
                loaded.resolvedAt shouldBe null
                loaded.resolutionEvidence shouldBe null
                loaded.localTradeId shouldBe 1
            }
        }
    }
}
