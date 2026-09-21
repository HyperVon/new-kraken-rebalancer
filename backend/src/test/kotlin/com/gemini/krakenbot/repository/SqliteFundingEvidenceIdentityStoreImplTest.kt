package com.gemini.krakenbot.repository

import com.gemini.krakenbot.config.DatabaseConfig
import com.gemini.krakenbot.repository.impl.SqliteFundingEvidenceIdentityStoreImpl
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.v1.jdbc.Database
import java.util.UUID

class SqliteFundingEvidenceIdentityStoreImplTest : StringSpec() {
    override fun isolationMode() = IsolationMode.InstancePerTest

    init {
        "load and save failures degrade to no durable identity" {
            runTest {
                // A valid SQLite file WITHOUT the schema: every metadata statement fails.
                val brokenDatabase =
                    Database.connect(
                        "jdbc:sqlite:file:identity-store-no-schema-${UUID.randomUUID()}?mode=memory&cache=shared",
                    )
                val store = SqliteFundingEvidenceIdentityStoreImpl(brokenDatabase)

                store.load() shouldBe null
                // A failed identity write must never fail a trading path.
                store.save(
                    FundingEvidenceIdentityRecord(
                        fingerprint = "fingerprint",
                        identity = "identity",
                        updatedAtEpochSeconds = 1L,
                    ),
                )
            }
        }

        "round trips the durable identity" {
            runTest {
                val database = DatabaseConfig.init(
                    "jdbc:sqlite:file:funding-identity-${UUID.randomUUID()}?mode=memory&cache=shared",
                )
                val store = SqliteFundingEvidenceIdentityStoreImpl(database)
                store.load() shouldBe null

                val record = FundingEvidenceIdentityRecord(
                    fingerprint = "fingerprint-1",
                    identity = "scope|deposit|100|200",
                    updatedAtEpochSeconds = 42L,
                )
                store.save(record)

                val updated = record.copy(fingerprint = "fingerprint-2", updatedAtEpochSeconds = 43L)
                store.save(updated)
                store.load() shouldBe updated

                // A failed verification clears the record so the next request misses the
                // cache instead of certifying the previous batch's evidence.
                store.clear()
                store.load() shouldBe null
            }
        }
    }
}
