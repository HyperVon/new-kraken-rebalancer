package com.gemini.krakenbot.repository

import com.fasterxml.jackson.databind.ObjectMapper
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
import io.mockk.every
import io.mockk.mockk
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

        "keeps reachability selection evidence separate from consumed candles" {
            runTest {
                val database = DatabaseConfig.init(
                    "jdbc:sqlite:file:comparison-cache-frontier-${UUID.randomUUID()}?mode=memory&cache=shared",
                )
                val mapper = jacksonObjectMapper().apply {
                    registerModule(JavaTimeModule())
                    disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                }
                val repository = SqliteRebalancerComparisonCacheRepositoryImpl(database, mapper)
                val selection = listOf(OhlcReachabilityDependency("XBTUSD", 15, 1_800_000_000L))
                val consumed = listOf(
                    ConsumedOhlcDependency(
                        pair = "XBTUSD",
                        intervalMinutes = 1440,
                        sinceEpochSecond = 1_700_000_000L,
                        upToEpochSecond = 1_700_086_400L,
                        fetchedAtEpochSecond = 1_800_000_000L,
                        freshnessDeadlineEpochSecond = 1_800_003_600L,
                        candleContentHash = "daily-candle-hash",
                    ),
                )
                repository.save(0L, 1L, "fingerprint", comparison(), consumed, selection)

                repository.load(0L, 1L)?.let { entry ->
                    entry.ohlcDependencies shouldBe consumed
                    entry.ohlcReachabilityDependencies shouldBe selection
                }

                // Background candle revalidation changes only consumed data metadata.
                repository.updateOhlcDependencies(0L, 1L, emptyList())
                repository.load(0L, 1L)?.let { entry ->
                    entry.ohlcDependencies shouldBe emptyList()
                    entry.ohlcReachabilityDependencies shouldBe selection
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

        "round trips, updates, and deletes consumed OHLC dependencies" {
            runTest {
                val database = DatabaseConfig.init(
                    "jdbc:sqlite:file:comparison-cache-deps-${UUID.randomUUID()}?mode=memory&cache=shared",
                )
                val mapper = jacksonObjectMapper().apply {
                    registerModule(JavaTimeModule())
                    disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                }
                val repository = SqliteRebalancerComparisonCacheRepositoryImpl(database, mapper)
                val from = 1000L
                val to = 2000L
                val dep1 = ConsumedOhlcDependency(
                    pair = "XXBTZUSD",
                    intervalMinutes = 60,
                    sinceEpochSecond = 500L,
                    upToEpochSecond = 550L,
                    fetchedAtEpochSecond = 600L,
                    freshnessDeadlineEpochSecond = 700L,
                    candleContentHash = "hash1",
                )
                val comparison = comparison()

                repository.save(from, to, "fp-1", comparison, listOf(dep1))
                val loaded = repository.load(from, to)
                loaded shouldBe RebalancerComparisonCacheEntry(
                    inputFingerprint = "fp-1",
                    comparison = comparison,
                    ohlcDependencies = listOf(dep1),
                )

                val updatedDep = dep1.copy(freshnessDeadlineEpochSecond = 900L)
                repository.updateOhlcDependencies(from, to, listOf(updatedDep))
                val loadedAfterUpdate = repository.load(from, to)
                loadedAfterUpdate?.ohlcDependencies shouldBe listOf(updatedDep)

                repository.delete(from, to)
                repository.load(from, to) shouldBe null
            }
        }

        "handles serialization failure gracefully" {
            runTest {
                val database = DatabaseConfig.init(
                    "jdbc:sqlite:file:comparison-cache-err-${UUID.randomUUID()}?mode=memory&cache=shared",
                )
                val failingMapper = mockk<ObjectMapper>()
                every { failingMapper.writeValueAsString(any()) } throws RuntimeException("serialization boom")
                val repository = SqliteRebalancerComparisonCacheRepositoryImpl(database, failingMapper)
                repository.save(0L, 1L, "fp", comparison(), emptyList())
                repository.updateOhlcDependencies(0L, 1L, emptyList())
                repository.load(0L, 1L) shouldBe null
            }
        }

        "treats a corrupt ohlcDependenciesJson as a cache miss instead of validating it as empty" {
            runTest {
                val database = DatabaseConfig.init(
                    "jdbc:sqlite:file:comparison-cache-corrupt-${UUID.randomUUID()}?mode=memory&cache=shared",
                )
                val mapper = jacksonObjectMapper().apply {
                    registerModule(JavaTimeModule())
                    disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                }
                val repository = SqliteRebalancerComparisonCacheRepositoryImpl(database, mapper)
                val comparison = comparison()
                val comparisonJson = mapper.writeValueAsString(comparison)
                transaction(database) {
                    RebalancerComparisonCacheTable.insert {
                        it[fromEpochMillis] = 100L
                        it[toEpochMillis] = 200L
                        it[inputFingerprint] = "fp"
                        it[resultJson] = comparisonJson
                        it[ohlcDependenciesJson] = "corrupt json string {"
                        it[calculatedAtEpochMillis] = 1000L
                    }
                }
                // An empty manifest carries no freshness requirements: defaulting here would
                // serve the cached result as a Hit without ever revalidating its OHLC evidence.
                repository.load(100L, 200L) shouldBe null
            }
        }

        "conditional dependency update writes only when the stored list still matches" {
            runTest {
                val database = DatabaseConfig.init(
                    "jdbc:sqlite:file:comparison-cache-cas-${UUID.randomUUID()}?mode=memory&cache=shared",
                )
                val mapper = jacksonObjectMapper().apply {
                    registerModule(JavaTimeModule())
                    disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                }
                val repository = SqliteRebalancerComparisonCacheRepositoryImpl(database, mapper)
                val from = 1000L
                val to = 2000L
                val dep = ConsumedOhlcDependency(
                    pair = "XXBTZUSD",
                    intervalMinutes = 60,
                    sinceEpochSecond = 500L,
                    upToEpochSecond = 550L,
                    fetchedAtEpochSecond = 600L,
                    freshnessDeadlineEpochSecond = 700L,
                    candleContentHash = "hash1",
                )
                repository.save(from, to, "fp-1", comparison(), listOf(dep))

                val refreshed = dep.copy(freshnessDeadlineEpochSecond = 900L)
                repository.updateOhlcDependenciesIfExpected(from, to, listOf(dep), listOf(refreshed)) shouldBe true
                repository.load(from, to)?.ohlcDependencies shouldBe listOf(refreshed)

                // A concurrent replay replaced the manifest meanwhile: the stale write is
                // dropped and the replayed entry stays intact.
                val replayed = dep.copy(candleContentHash = "hash2", freshnessDeadlineEpochSecond = 950L)
                repository.updateOhlcDependencies(from, to, listOf(replayed))
                repository.updateOhlcDependenciesIfExpected(from, to, listOf(refreshed), listOf(dep)) shouldBe false
                repository.load(from, to)?.ohlcDependencies shouldBe listOf(replayed)

                repository.delete(from, to)
                repository.updateOhlcDependenciesIfExpected(from, to, listOf(replayed), listOf(dep)) shouldBe false
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
