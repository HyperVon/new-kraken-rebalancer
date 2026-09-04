package com.gemini.krakenbot.repository

import com.gemini.krakenbot.TestFixtures
import com.gemini.krakenbot.config.DatabaseConfig
import com.gemini.krakenbot.model.KrakenApiConstants
import com.gemini.krakenbot.model.LedgerEvent
import com.gemini.krakenbot.model.SyncMetadataKeys
import com.gemini.krakenbot.repository.impl.SqliteLedgerRepositoryImpl
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.comparables.shouldBeEqualComparingTo
import io.kotest.matchers.shouldBe
import java.math.BigDecimal
import java.time.Instant

@Suppress("unused")
class SqliteLedgerRepositoryImplTest : StringSpec() {

    override fun isolationMode() = IsolationMode.InstancePerTest

    private val db = DatabaseConfig.init(TestFixtures.MEMORY_)
    private val repository = SqliteLedgerRepositoryImpl(db)

    private val t0 = Instant.parse("2026-06-01T00:00:00Z")
    private val t1 = Instant.parse("2026-06-02T00:00:00Z")
    private val t2 = Instant.parse("2026-06-03T00:00:00Z")

    private fun event(
        timestamp: Instant,
        ledgerId: String,
        asset: String = "XBT",
        amount: String = "0.1",
        type: String = KrakenApiConstants.LEDGER_TYPE_STAKING,
    ): LedgerEvent = LedgerEvent(
        ledgerId = ledgerId,
        time = timestamp,
        type = type,
        asset = asset,
        amount = BigDecimal(amount),
    )

    init {
        "saveLedgers persists entries and returns the inserted count" {
            val inserted = repository.saveLedgers(listOf(event(t1, "ref-1"), event(t2, "ref-2")))
            inserted shouldBe 2
            repository.getLedgersInRange(t0, t2).size shouldBe 2
        }

        "saveLedgers skips duplicates on the (ledger id, timestamp, asset, type) identity" {
            repository.saveLedgers(listOf(event(t1, "ref-1")))
            val secondAttempt = repository.saveLedgers(listOf(event(t1, "ref-1")))
            secondAttempt shouldBe 0
            repository.getLedgersInRange(t0, t2).size shouldBe 1
        }

        "saveLedgers keeps the same ledger id at different timestamps as distinct entries" {
            repository.saveLedgers(listOf(event(t1, "ref-1"), event(t2, "ref-1")))
            repository.getLedgersInRange(t0, t2).size shouldBe 2
        }

        "getLedgersInRange is inclusive on both bounds and returns newest first" {
            repository.saveLedgers(listOf(event(t0, "ref-0"), event(t1, "ref-1"), event(t2, "ref-2")))
            val inRange = repository.getLedgersInRange(t0, t1)
            inRange.map { it.ledgerId } shouldBe listOf("ref-1", "ref-0")
        }

        "getLatestLedgerTime is null when empty and returns the newest entry after inserts" {
            repository.getLatestLedgerTime() shouldBe null
            repository.saveLedgers(listOf(event(t1, "ref-1"), event(t2, "ref-2")))
            repository.getLatestLedgerTime() shouldBe t2
        }

        "pruneLedgersOlderThan removes only entries before the cutoff" {
            repository.saveLedgers(listOf(event(t1, "ref-1"), event(t2, "ref-2")))
            val pruned = repository.pruneLedgersOlderThan(t2)
            pruned shouldBe 1
            repository.getLedgersInRange(Instant.EPOCH, t2).map { it.ledgerId } shouldBe listOf("ref-2")
        }

        "pruneLedgersOlderThan retains entries at or after inception epoch" {
            repository.setSyncMetadata(
                SyncMetadataKeys.DETECTED_INCEPTION_EPOCH_MS,
                t1.toEpochMilli().toString(),
            )
            repository.saveLedgers(listOf(event(t0, "ref-0"), event(t1, "ref-1"), event(t2, "ref-2")))
            // Cutoff is t2. ref-0 is before inception -> pruned.
            // ref-1 is at inception (>= inception - 5s) -> retained!
            // ref-2 is at t2 -> retained!
            val pruned = repository.pruneLedgersOlderThan(t2)
            pruned shouldBe 1
            repository.getLedgersInRange(Instant.EPOCH, t2).map { it.ledgerId } shouldBe listOf("ref-2", "ref-1")
        }

        "sync metadata roundtrips through the shared history_sync_metadata table" {
            repository.setSyncMetadata(TestFixtures.SYNC_KEY, TestFixtures.SYNC_VAL)
            repository.getSyncMetadata(TestFixtures.SYNC_KEY) shouldBe TestFixtures.SYNC_VAL
        }

        "seeded flag persists via the LEDGERS_SEEDED metadata key" {
            repository.isLedgersSeeded() shouldBe false
            repository.setLedgersSeeded(true)
            repository.isLedgersSeeded() shouldBe true
            repository.getSyncMetadata(SyncMetadataKeys.LEDGERS_SEEDED) shouldBe "true"
            repository.setLedgersSeeded(false)
            repository.isLedgersSeeded() shouldBe false
        }

        "round-tripped ledger entries preserve all fields including dividend metadata" {
            val original =
                LedgerEvent(
                    ledgerId = "ledger-x",
                    refid = "tx-ref",
                    time = t1,
                    type = KrakenApiConstants.LEDGER_TYPE_DIVIDEND,
                    subtype = "in-kind",
                    aclass = "currency",
                    asset = "STRC",
                    amount = BigDecimal("1.25"),
                    fee = BigDecimal("0.01"),
                    balance = BigDecimal("9.99"),
                )
            repository.saveLedgers(listOf(original))
            val loaded = repository.getLedgersInRange(t0, t2).single()
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
