@file:OptIn(ExperimentalCoroutinesApi::class)

package com.gemini.krakenbot.service.impl.history

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.gemini.krakenbot.TestFixtures
import com.gemini.krakenbot.config.Allocation
import com.gemini.krakenbot.config.AppConfig
import com.gemini.krakenbot.config.DatabaseConfig
import com.gemini.krakenbot.config.KrakenCredentials
import com.gemini.krakenbot.model.Asset
import com.gemini.krakenbot.model.SyncMetadataKeys
import com.gemini.krakenbot.model.TradeRecord
import com.gemini.krakenbot.model.TradeSource
import com.gemini.krakenbot.repository.impl.SqliteLedgerRepositoryImpl
import com.gemini.krakenbot.repository.impl.SqliteTradeRepositoryImpl
import com.gemini.krakenbot.service.ConfigService
import com.gemini.krakenbot.service.FakeKrakenService
import com.gemini.krakenbot.service.KrakenService
import com.gemini.krakenbot.service.RecoveryTradeHistoryService
import com.gemini.krakenbot.service.impl.ConfigServiceImpl
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.math.BigDecimal
import java.nio.file.Files
import java.time.Instant

/**
 * Proves one continuity proof observes exactly one credential generation.
 *
 * Unlike the mock-`ConfigService` suites, these tests use a real
 * [ConfigServiceImpl]: `updateConfig` during the guard's execution session is
 * genuinely staged (invisible to `getConfig()` until the session ends), which
 * is the production mechanism under test. The exchange fake derives per-call
 * visibility from the live config, so any generation leak would surface as a
 * mixed-generation observation or a wrongly bound digest.
 */
class AccountHistoryScopePinningTest : StringSpec() {
    override fun isolationMode() = IsolationMode.InstancePerTest

    private val objectMapper = jacksonObjectMapper()
    private val database = DatabaseConfig.init(TestFixtures.MEMORY_)
    private val tradeRepository = SqliteTradeRepositoryImpl(database)
    private val ledgerRepository = SqliteLedgerRepositoryImpl(database)

    private val keyA = "pin-key-A"
    private val keyB = "pin-key-B"
    private val secretA = "cGluLXNlY3JldC1B"
    private val secretB = "cGluLXNlY3JldC1C"
    private val markerBase = Instant.parse("2026-01-01T00:00:00Z")

    private fun configWith(key: String, secret: String): AppConfig = TestFixtures.config(
        settings = TestFixtures.settings(loopDelaySeconds = 60L),
        allocations = listOf(Allocation(Asset.USD, 100.0)),
        kraken = KrakenCredentials(key, secret),
    )

    private fun newConfigService(kraken: KrakenCredentials): ConfigServiceImpl {
        val file = Files.createTempDirectory("scope-pinning").resolve("rebalancer-config.json").toFile()
        objectMapper.writeValue(file, configWith(keyA, secretA).copy(kraken = kraken))
        return ConfigServiceImpl(objectMapper, file.absolutePath)
    }

    /**
     * Exchange double whose per-call account visibility follows the *live*
     * config generation, recording every generation observed by proof calls.
     */
    private inner class GenerationAwareFake(
        val configs: ConfigService,
        val inner: FakeKrakenService = FakeKrakenService(),
    ) : KrakenService by inner,
        RecoveryTradeHistoryService {
        val observedGenerations = mutableListOf<String>()
        var gate: (suspend () -> Unit)? = null

        private fun generation(): String = configs.getConfig().kraken.apiKey.value

        override suspend fun getFundingEvidenceScope(): String {
            observedGenerations += generation()
            return "scope-${generation()}"
        }

        override suspend fun getRecoveryTradeHistoryUntil(
            startSec: Long?,
            offset: Int?,
            endSec: Long?,
        ): List<TradeRecord> {
            observedGenerations += generation()
            gate?.invoke()
            return inner.getRecoveryTradeHistoryUntil(startSec, offset, endSec)
        }
    }

    private fun apiFill(id: String, timestamp: Instant): TradeRecord = TestFixtures.tradeRecord(
        timestamp = timestamp,
        pair = Asset.BTC_USD_PAIR,
        side = "buy",
        symbol = Asset.BTC,
        volume = BigDecimal("0.01"),
        usdAmount = BigDecimal("100.00"),
        price = BigDecimal("10000.00"),
        source = TradeSource.API_FILL,
        tradeId = id,
    )

    private suspend fun saveLegacyPair(oldId: String = "pin-old-fill", newId: String = "pin-new-fill") {
        tradeRepository.saveTrade(apiFill(oldId, markerBase))
        tradeRepository.saveTrade(apiFill(newId, markerBase.plusSeconds(100_000L)))
    }

    private fun GenerationAwareFake.seeOnly(generation: String, vararg ids: String) {
        inner.tradeHistorySupplier = { _, _ ->
            if (configs.getConfig().kraken.apiKey.value == generation) {
                ids.map { apiFill(it, markerBase) }
            } else {
                emptyList()
            }
        }
    }

    init {
        "mid-proof credential flip stays pinned to the starting generation" {
            runTest {
                val configs = newConfigService(KrakenCredentials(keyA, secretA))
                val kraken = GenerationAwareFake(configs)
                val guard = AccountHistoryScopeGuard(kraken, tradeRepository, ledgerRepository, configs)
                saveLegacyPair()
                kraken.seeOnly(keyA, "pin-old-fill", "pin-new-fill")

                val enteredProof = CompletableDeferred<Unit>()
                val releaseProof = CompletableDeferred<Unit>()
                var firstCall = true
                kraken.gate = {
                    if (firstCall) {
                        firstCall = false
                        enteredProof.complete(Unit)
                        releaseProof.await()
                    }
                }
                val background = async { guard.validateAccountScope() }
                runCurrent()
                enteredProof.await()

                // Flip credentials while the proof is suspended: the session
                // must stage this instead of publishing it.
                configs.updateConfig(configWith(keyB, secretB))
                configs.getConfig().kraken.apiKey.value shouldBe keyA
                releaseProof.complete(Unit)
                val result = background.await()

                // Every proof call observed generation A, and the persisted
                // binding is exactly generation A's fingerprint at version 2.
                kraken.observedGenerations.toSet() shouldBe setOf(keyA)
                result.status shouldBe AccountScopeValidationStatus.VALID
                tradeRepository.getSyncMetadata(
                    SyncMetadataKeys.INCEPTION_ACCOUNT_SCOPE_DIGEST,
                ) shouldBe AccountHistoryScopeGuard.digestAccountScope("scope-$keyA")
                tradeRepository.getSyncMetadata(
                    SyncMetadataKeys.INCEPTION_ACCOUNT_SCOPE_BINDING_VERSION,
                ) shouldBe AccountHistoryScopeGuard.CURRENT_BINDING_VERSION
                // The staged flip publishes once the session ends.
                configs.getConfig().kraken.apiKey.value shouldBe keyB
            }
        }

        "ABA credential flip cannot mix generations inside one proof" {
            runTest {
                val configs = newConfigService(KrakenCredentials(keyA, secretA))
                val kraken = GenerationAwareFake(configs)
                val guard = AccountHistoryScopeGuard(kraken, tradeRepository, ledgerRepository, configs)
                saveLegacyPair()
                kraken.seeOnly(keyA, "pin-old-fill", "pin-new-fill")

                val enteredProof = CompletableDeferred<Unit>()
                val releaseProof = CompletableDeferred<Unit>()
                var firstCall = true
                kraken.gate = {
                    if (firstCall) {
                        firstCall = false
                        enteredProof.complete(Unit)
                        releaseProof.await()
                    }
                }
                val background = async { guard.validateAccountScope() }
                runCurrent()
                enteredProof.await()

                configs.updateConfig(configWith(keyB, secretB))
                configs.updateConfig(configWith(keyA, secretA))
                releaseProof.complete(Unit)
                val result = background.await()

                // No window ever saw B: the proof could not consume one marker
                // from A and another from B and pass on the ending fingerprint.
                kraken.observedGenerations.toSet() shouldBe setOf(keyA)
                result.status shouldBe AccountScopeValidationStatus.VALID
                tradeRepository.getSyncMetadata(
                    SyncMetadataKeys.INCEPTION_ACCOUNT_SCOPE_DIGEST,
                ) shouldBe AccountHistoryScopeGuard.digestAccountScope("scope-$keyA")
            }
        }

        "flip to unmatched credentials mid-proof stays unbound" {
            runTest {
                val configs = newConfigService(KrakenCredentials(keyA, secretA))
                val kraken = GenerationAwareFake(configs)
                val guard = AccountHistoryScopeGuard(kraken, tradeRepository, ledgerRepository, configs)
                // Retained markers belong to generation B, but the proof starts
                // under generation A.
                saveLegacyPair(oldId = "gen-B-old", newId = "gen-B-new")
                kraken.seeOnly(keyB, "gen-B-old", "gen-B-new")

                val enteredProof = CompletableDeferred<Unit>()
                val releaseProof = CompletableDeferred<Unit>()
                var firstCall = true
                kraken.gate = {
                    if (firstCall) {
                        firstCall = false
                        enteredProof.complete(Unit)
                        releaseProof.await()
                    }
                }
                val background = async { guard.validateAccountScope() }
                runCurrent()
                enteredProof.await()

                // Flipping to B mid-proof must not let the proof see B: it
                // stays pinned to A, finds no overlap, and binds nothing.
                configs.updateConfig(configWith(keyB, secretB))
                releaseProof.complete(Unit)
                val result = background.await()

                kraken.observedGenerations.toSet() shouldBe setOf(keyA)
                result.status shouldBe AccountScopeValidationStatus.UNBOUND_EXISTING_HISTORY
                result.isValid shouldBe false
                tradeRepository.getSyncMetadata(
                    SyncMetadataKeys.INCEPTION_ACCOUNT_SCOPE_DIGEST,
                ) shouldBe null
            }
        }

        "live to simulation flip mid-proof cannot mix preflight with simulated proof" {
            runTest {
                val configs = newConfigService(KrakenCredentials(keyA, secretA))
                val kraken = GenerationAwareFake(configs)
                val guard = AccountHistoryScopeGuard(kraken, tradeRepository, ledgerRepository, configs)
                saveLegacyPair()
                kraken.seeOnly(keyA, "pin-old-fill", "pin-new-fill")

                val enteredProof = CompletableDeferred<Unit>()
                val releaseProof = CompletableDeferred<Unit>()
                var firstCall = true
                kraken.gate = {
                    if (firstCall) {
                        firstCall = false
                        enteredProof.complete(Unit)
                        releaseProof.await()
                    }
                }
                val background = async { guard.validateAccountScope() }
                runCurrent()
                enteredProof.await()

                // Flip to simulation while the proof is suspended: the session
                // stages it, so preflight and proof stay on the live generation.
                configs.updateConfig(
                    configWith(
                        keyA,
                        secretA,
                    ).copy(settings = TestFixtures.settings(loopDelaySeconds = 60L, simulation = true)),
                )
                releaseProof.complete(Unit)
                val result = background.await()

                kraken.observedGenerations.toSet() shouldBe setOf(keyA)
                result.status shouldBe AccountScopeValidationStatus.VALID
                tradeRepository.getSyncMetadata(
                    SyncMetadataKeys.INCEPTION_ACCOUNT_SCOPE_DIGEST,
                ) shouldBe AccountHistoryScopeGuard.digestAccountScope("scope-$keyA")
                configs.getConfig().settings.simulation shouldBe true
            }
        }

        "credential replacement mid-proof cannot change the proven generation" {
            runTest {
                val configs = newConfigService(KrakenCredentials(keyA, secretA))
                val kraken = GenerationAwareFake(configs)
                val guard = AccountHistoryScopeGuard(kraken, tradeRepository, ledgerRepository, configs)
                saveLegacyPair()
                kraken.seeOnly(keyA, "pin-old-fill", "pin-new-fill")

                val enteredProof = CompletableDeferred<Unit>()
                val releaseProof = CompletableDeferred<Unit>()
                var firstCall = true
                kraken.gate = {
                    if (firstCall) {
                        firstCall = false
                        enteredProof.complete(Unit)
                        releaseProof.await()
                    }
                }
                val background = async { guard.validateAccountScope() }
                runCurrent()
                enteredProof.await()

                // Validity was checked against A before the session; the proof
                // must observe that same generation throughout.
                configs.updateConfig(configWith(keyB, secretB))
                releaseProof.complete(Unit)
                val result = background.await()

                kraken.observedGenerations.toSet() shouldBe setOf(keyA)
                result.status shouldBe AccountScopeValidationStatus.VALID
                tradeRepository.getSyncMetadata(
                    SyncMetadataKeys.INCEPTION_ACCOUNT_SCOPE_DIGEST,
                ) shouldBe AccountHistoryScopeGuard.digestAccountScope("scope-$keyA")
            }
        }

        "simulation starting generation returns SIMULATION without proof" {
            runTest {
                val configs = newConfigService(KrakenCredentials(keyA, secretA))
                configs.updateConfig(
                    configWith(
                        keyA,
                        secretA,
                    ).copy(settings = TestFixtures.settings(loopDelaySeconds = 60L, simulation = true)),
                )
                val kraken = GenerationAwareFake(configs)
                val guard = AccountHistoryScopeGuard(kraken, tradeRepository, ledgerRepository, configs)
                saveLegacyPair()

                val result = guard.validateAccountScope()

                result.status shouldBe AccountScopeValidationStatus.SIMULATION
                kraken.observedGenerations shouldBe emptyList()
                tradeRepository.getSyncMetadata(
                    SyncMetadataKeys.INCEPTION_ACCOUNT_SCOPE_DIGEST,
                ) shouldBe null
            }
        }

        "cancellation during a pinned proof releases session and mutex without writing" {
            runTest {
                val configs = newConfigService(KrakenCredentials(keyA, secretA))
                val kraken = GenerationAwareFake(configs)
                val guard = AccountHistoryScopeGuard(kraken, tradeRepository, ledgerRepository, configs)
                saveLegacyPair()
                kraken.seeOnly(keyA, "pin-old-fill", "pin-new-fill")

                val enteredProof = CompletableDeferred<Unit>()
                val hangForever = CompletableDeferred<Unit>()
                kraken.gate = {
                    enteredProof.complete(Unit)
                    hangForever.await()
                }
                val background = launch { guard.validateAccountScope() }
                runCurrent()
                enteredProof.await()

                background.cancelAndJoin()

                // Session depth is back to zero: a subsequent update publishes
                // immediately instead of staging forever...
                configs.updateConfig(configWith(keyB, secretB))
                configs.getConfig().kraken.apiKey.value shouldBe keyB
                // ...the validation mutex is free, and nothing was bound.
                guard.readLocalTrustState().status shouldBe AccountScopeValidationStatus.UNBOUND_EXISTING_HISTORY
                tradeRepository.getSyncMetadata(
                    SyncMetadataKeys.INCEPTION_ACCOUNT_SCOPE_DIGEST,
                ) shouldBe null
            }
        }
    }
}
