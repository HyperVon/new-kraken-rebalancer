package com.gemini.krakenbot

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.gemini.krakenbot.model.PortfolioSnapshot
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.math.BigDecimal
import java.time.Instant

class SerializationParityTest : StringSpec() {
    override fun isolationMode() = IsolationMode.InstancePerTest

    private val mapper = jacksonObjectMapper().findAndRegisterModules()

    init {
        "current cycle snapshot serialization contains current fields only" {
            val snapshot = TestFixtures.emptySnapshot(
                Instant.parse("2026-10-10T12:00:00Z"),
                BigDecimal("15000.50"),
            )

            val json = mapper.writeValueAsString(snapshot)

            json shouldContain "\"timestamp\":"
            json shouldContain "\"totalValueUSD\":15000.50"
            json.lowercase() shouldNotContain "drawdown"
            json.lowercase() shouldNotContain "deployment"
            val decoded: PortfolioSnapshot = mapper.readValue(json)
            decoded.totalValueUSD shouldBe snapshot.totalValueUSD
            decoded.timestamp shouldBe snapshot.timestamp
        }
    }
}
