package com.gemini.krakenbot.repository

import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.gemini.krakenbot.config.DatabaseConfig
import com.gemini.krakenbot.model.ComparisonAvailability
import com.gemini.krakenbot.model.ComparisonConfidence
import com.gemini.krakenbot.model.RebalancerComparison
import com.gemini.krakenbot.model.RebalancerComparisonPoint
import com.gemini.krakenbot.repository.impl.SqliteRebalancerComparisonCacheRepositoryImpl
import com.gemini.krakenbot.repository.table.RebalancerComparisonCacheTable
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

class SqliteRebalancerComparisonCacheRepositoryImplTest : StringSpec() {
    override fun isolationMode() = IsolationMode.InstancePerTest

    init {
        "round trips and replaces one successful result per display range" {
            runTest {
                val database = DatabaseConfig.init(
                    "jdbc:sqlite:file:comparison-cache-${UUID.randomUUID()}?mode=memory&cache=shared",
                )
                val mapper = jacksonObjectMapper().apply {
                    registerModule(JavaTimeModule())
                    disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                }
                val repository = SqliteRebalancerComparisonCacheRepositoryImpl(database, mapper)
                val from = Instant.parse("2026-01-01T00:00:00Z").toEpochMilli()
                val to = Instant.parse("2026-01-02T00:00:00Z").toEpochMilli()
                val comparison = comparison()

                repository.load(from, to) shouldBe null
                repository.save(from, to, "fingerprint-1", comparison)
                repository.load(from, to)?.let {
                    it.inputFingerprint shouldBe "fingerprint-1"
                    it.comparison shouldBe comparison
                }

                repository.save(from, to, "fingerprint-2", comparison.copy(latestDifferenceUSD = BigDecimal("3.00")))
                repository.load(from, to)?.let {
                    it.inputFingerprint shouldBe "fingerprint-2"
                    it.comparison.latestDifferenceUSD shouldBe BigDecimal("3.00")
                }
            }
        }

        "does not persist unavailable results" {
            runTest {
                val database = DatabaseConfig.init(
                    "jdbc:sqlite:file:comparison-cache-unavailable-${UUID.randomUUID()}?mode=memory&cache=shared",
                )
                val mapper = jacksonObjectMapper().apply {
                    registerModule(JavaTimeModule())
                    disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                }
                val repository = SqliteRebalancerComparisonCacheRepositoryImpl(database, mapper)
                val unavailable = RebalancerComparison(
                    availability = ComparisonAvailability.UNAVAILABLE,
                    confidence = null,
                    baselineTimestamp = Instant.EPOCH,
                    points = emptyList(),
                    latestDifferenceUSD = null,
                    latestDifferencePercent = null,
                    unavailableReason = com.gemini.krakenbot.model.ComparisonUnavailableReason.MISSING_PRICE,
                    unavailableAt = Instant.EPOCH,
                )

                repository.save(0L, 1L, "fingerprint", unavailable)
                repository.load(0L, 1L) shouldBe null

                transaction(database) {
                    RebalancerComparisonCacheTable.insert {
                        it[RebalancerComparisonCacheTable.fromEpochMillis] = 0L
                        it[RebalancerComparisonCacheTable.toEpochMillis] = 1L
                        it[RebalancerComparisonCacheTable.inputFingerprint] = "fingerprint"
                        it[RebalancerComparisonCacheTable.resultJson] = mapper.writeValueAsString(unavailable)
                        it[RebalancerComparisonCacheTable.calculatedAtEpochMillis] = 1L
                    }
                }
                repository.load(0L, 1L) shouldBe null
            }
        }

        "treats a corrupted durable payload as a cache miss" {
            runTest {
                val database = DatabaseConfig.init(
                    "jdbc:sqlite:file:comparison-cache-corrupt-${UUID.randomUUID()}?mode=memory&cache=shared",
                )
                val mapper = jacksonObjectMapper()
                val repository = SqliteRebalancerComparisonCacheRepositoryImpl(database, mapper)

                transaction(database) {
                    RebalancerComparisonCacheTable.insert {
                        it[RebalancerComparisonCacheTable.fromEpochMillis] = 0L
                        it[RebalancerComparisonCacheTable.toEpochMillis] = 1L
                        it[RebalancerComparisonCacheTable.inputFingerprint] = "fingerprint"
                        it[RebalancerComparisonCacheTable.resultJson] = "not-json"
                        it[RebalancerComparisonCacheTable.calculatedAtEpochMillis] = 1L
                    }
                }

                repository.load(0L, 1L) shouldBe null
            }
        }

        "prunes superseded source ranges and keeps the newest result reusable" {
            runTest {
                val database = DatabaseConfig.init(
                    "jdbc:sqlite:file:comparison-cache-prune-${UUID.randomUUID()}?mode=memory&cache=shared",
                )
                val mapper = jacksonObjectMapper().apply {
                    registerModule(JavaTimeModule())
                    disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                }
                val repository = SqliteRebalancerComparisonCacheRepositoryImpl(database, mapper)
                val hourMillis = 3_600_000L

                // Advancing certified horizons save one row each; only the newest three
                // successful source ranges are retained.
                (1L..5L).forEach { index ->
                    repository.save(0L, index * hourMillis, "fingerprint-$index", comparison())
                }

                val retainedRows = transaction(database) {
                    RebalancerComparisonCacheTable.selectAll().map {
                        it[RebalancerComparisonCacheTable.fromEpochMillis] to
                            it[RebalancerComparisonCacheTable.toEpochMillis]
                    }
                }
                retainedRows.size shouldBe 3
                retainedRows.map { it.second }.toSet() shouldBe setOf(3L * hourMillis, 4L * hourMillis, 5L * hourMillis)

                // The newest valid result survives pruning and remains loadable after a
                // repository restart on the same database.
                val restarted = SqliteRebalancerComparisonCacheRepositoryImpl(database, mapper)
                restarted.load(0L, 5L * hourMillis)?.inputFingerprint shouldBe "fingerprint-5"
                restarted.load(0L, 1L * hourMillis) shouldBe null
            }
        }
    }

    private fun comparison() = RebalancerComparison(
        availability = ComparisonAvailability.AVAILABLE,
        confidence = ComparisonConfidence.RECONCILED,
        baselineTimestamp = Instant.EPOCH,
        points = listOf(
            point(Instant.EPOCH, "100.00"),
            point(Instant.ofEpochSecond(3600), "103.00"),
        ),
        latestDifferenceUSD = BigDecimal("3.00"),
        latestDifferencePercent = BigDecimal("3.00"),
        unavailableReason = null,
        unavailableAt = null,
    )

    private fun point(timestamp: Instant, rebalancerValue: String) = RebalancerComparisonPoint(
        timestamp = timestamp,
        rebalancerValueUSD = BigDecimal(rebalancerValue),
        buyAndHoldValueUSD = BigDecimal("100.00"),
        differenceUSD = BigDecimal(rebalancerValue).subtract(BigDecimal("100.00")),
        differencePercent = BigDecimal(rebalancerValue).subtract(BigDecimal("100.00")),
    )
}
