package com.gemini.krakenbot.repository.table

import com.gemini.krakenbot.TestFixtures
import com.gemini.krakenbot.config.DatabaseConfig
import com.gemini.krakenbot.model.OrderSide
import com.gemini.krakenbot.model.OrderSubmissionState
import com.gemini.krakenbot.model.TradeRecord
import com.gemini.krakenbot.model.TradeSource
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
class TradeTableTest : StringSpec() {
    override fun isolationMode() = IsolationMode.InstancePerTest

    init {
        "applyTo and toModel round-trip all trade record fields" {
            val db = DatabaseConfig.init(TestFixtures.MEMORY_)
            val original = TradeRecord(
                timestamp = Instant.parse("2026-07-01T10:00:00Z"),
                pair = "BTCUSD",
                side = OrderSide.BUY.name,
                symbol = "BTC",
                volume = BigDecimal("0.50000000"),
                usdAmount = BigDecimal("30000.00"),
                success = true,
                dryRun = false,
                errorMessage = null,
                price = BigDecimal("60000.00000000"),
                fee = BigDecimal("15.0000"),
                slippagePercent = BigDecimal("0.0200"),
                expectedPrice = BigDecimal("59990.00000000"),
                source = TradeSource.LOCAL_ESTIMATE,
                cycleId = "cycle-uuid",
                orderTxid = "kraken-tx-123",
                tradeId = "kraken-trade-456",
                clientOrderId = "cl-ord-789",
                submissionState = OrderSubmissionState.PENDING,
            )

            transaction(db) {
                val insertedId = TradeTable.insert {
                    TradeTable.applyTo(it, original)
                }[TradeTable.id]

                val row = TradeTable.selectAll().single()
                val loaded = TradeTable.toModel(row)

                loaded.id shouldBe insertedId
                loaded.timestamp shouldBe original.timestamp
                loaded.pair shouldBe original.pair
                loaded.side shouldBe original.side
                loaded.symbol shouldBe original.symbol
                loaded.volume.shouldBeEqualComparingTo(original.volume)
                loaded.usdAmount.shouldBeEqualComparingTo(original.usdAmount)
                loaded.success shouldBe original.success
                loaded.dryRun shouldBe original.dryRun
                loaded.errorMessage shouldBe original.errorMessage
                loaded.price.shouldBeEqualComparingTo(original.price)
                loaded.fee.shouldBeEqualComparingTo(original.fee)
                loaded.slippagePercent?.shouldBeEqualComparingTo(requireNotNull(original.slippagePercent))
                loaded.expectedPrice?.shouldBeEqualComparingTo(requireNotNull(original.expectedPrice))
                loaded.source shouldBe original.source
                loaded.cycleId shouldBe original.cycleId
                loaded.orderTxid shouldBe original.orderTxid
                loaded.tradeId shouldBe original.tradeId
                loaded.clientOrderId shouldBe original.clientOrderId
                loaded.submissionState shouldBe original.submissionState
            }
        }
    }
}
