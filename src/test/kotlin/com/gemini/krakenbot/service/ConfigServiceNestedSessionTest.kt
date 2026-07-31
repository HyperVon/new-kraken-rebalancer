@file:OptIn(ExperimentalCoroutinesApi::class)

package com.gemini.krakenbot.service

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.gemini.krakenbot.TestFixtures
import com.gemini.krakenbot.config.Allocation
import com.gemini.krakenbot.config.AppConfig
import com.gemini.krakenbot.config.KrakenCredentials
import com.gemini.krakenbot.model.Asset
import com.gemini.krakenbot.service.impl.ConfigServiceImpl
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import java.nio.file.Files

class ConfigServiceNestedSessionTest : StringSpec() {
    override fun isolationMode() = IsolationMode.InstancePerTest

    private val objectMapper = jacksonObjectMapper()

    private fun newService(tempFile: java.io.File): ConfigServiceImpl {
        objectMapper.writeValue(
            tempFile,
            TestFixtures.config(
                settings = TestFixtures.settings(loopDelaySeconds = 60L),
                allocations = listOf(Allocation(Asset.USD, 100.0)),
            ),
        )
        return ConfigServiceImpl(objectMapper, tempFile.absolutePath)
    }

    init {
        "nested execution session keeps updates staged until the outermost session ends" {
            runTest {
                val tempFile =
                    Files
                        .createTempDirectory("cq14-m7")
                        .resolve("nested-config.json")
                        .toFile()
                val service = newService(tempFile)
                val events = mutableListOf<com.gemini.krakenbot.config.Settings>()
                val job = launch {
                    service.watchConfigChanges().collect { events.add(it) }
                }
                advanceUntilIdle()
                events.size shouldBe 1

                val originalConfig = service.getConfig()
                val innerUpdate = originalConfig.copy(
                    settings = originalConfig.settings.copy(loopDelaySeconds = 120L),
                )
                val outerUpdate = originalConfig.copy(
                    settings = originalConfig.settings.copy(loopDelaySeconds = 180L),
                )

                // Enter two nested sessions: depth 1 then depth 2.
                service.beginExecutionSession()
                service.beginExecutionSession()

                // An inner update stages into pendingConfig but must NOT publish while depth > 0.
                service.updateConfig(innerUpdate)
                advanceUntilIdle()
                events.size shouldBe 1
                service.getConfig() shouldBe originalConfig

                // Close the inner session (depth 2 -> 1): still nested, no publication.
                service.endExecutionSession()
                advanceUntilIdle()
                events.size shouldBe 1
                service.getConfig() shouldBe originalConfig

                // A second update inside the remaining outer session overwrites the staged config.
                service.updateConfig(outerUpdate)
                advanceUntilIdle()
                events.size shouldBe 1
                service.getConfig() shouldBe originalConfig

                // Close the outer session (depth 1 -> 0): the staged config publishes exactly once.
                service.endExecutionSession()
                advanceUntilIdle()
                events.size shouldBe 2
                events[1].loopDelaySeconds shouldBe 180L
                service.getConfig().settings.loopDelaySeconds shouldBe 180L

                job.cancel()
            }
        }

        "endExecutionSession on depth 0 throws IllegalStateException with the documented message" {
            val tempFile =
                Files
                    .createTempDirectory("cq14-m7")
                    .resolve("no-session.json")
                    .toFile()
            val service = newService(tempFile)

            val ex = shouldThrow<IllegalStateException> {
                service.endExecutionSession()
            }
            ex.message shouldBe "No execution session is active."

            // The service must remain usable after the rejected call: a fresh begin/end pair
            // should publish normally, proving the depth counter was not corrupted.
            val updated = service.getConfig().copy(
                kraken = KrakenCredentials("k", "s"),
                settings = service.getConfig().settings.copy(loopDelaySeconds = 90L),
            )
            service.beginExecutionSession()
            service.updateConfig(updated)
            service.endExecutionSession()
            service.getConfig().settings.loopDelaySeconds shouldBe 90L
        }
    }
}
