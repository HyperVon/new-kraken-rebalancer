package com.gemini.krakenbot.controller

import com.gemini.krakenbot.TestFixtures
import com.gemini.krakenbot.view.util.FormFields
import com.gemini.krakenbot.view.util.HtmxHeaders
import com.gemini.krakenbot.view.util.Routes
import io.kotest.core.spec.IsolationMode
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotBeBlank
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.formUrlEncode
import io.ktor.http.parametersOf
import io.ktor.server.testing.testApplication
import io.mockk.coEvery
import io.mockk.every
import java.time.Instant

@Suppress("unused")
class ConfigSavePublicationResponseTest : DashboardControllerTestBase() {
    override fun isolationMode() = IsolationMode.InstancePerTest

    private fun settingsFields(csrf: String) = parametersOf(
        FormFields.CSRF_TOKEN to listOf(csrf),
        FormFields.LOOP_DELAY_SECONDS to listOf("75"),
        FormFields.DEVIATION_TRIGGER_PERCENT to listOf("3.25"),
        FormFields.MINIMUM_ORDER_SIZE_USD to listOf("7.5"),
        FormFields.DRY_RUN to listOf("on"),
        FormFields.SIMULATION to listOf("on"),
        FormFields.SYMBOLS to listOf("BTC", "ETH", "USD"),
        FormFields.TARGETS to listOf("45", "35", "20"),
        FormFields.COLORS to listOf("#FF0000", "", "#0000ff"),
        FormFields.SCORES to listOf("9.5", "0", ""),
    )

    private suspend fun postSettings(client: io.ktor.client.HttpClient, csrf: CsrfTestToken) =
        client.post(Routes.SETTINGS) {
            setBody(settingsFields(csrf.value).formUrlEncode())
            header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded.toString())
            header(HttpHeaders.Cookie, csrf.cookie)
            header(HttpHeaders.Origin, "http://localhost")
        }

    init {
        "settings save reports the pending-publication state returned by config service" {
            everyStub(publicationPending = true)

            testApplication {
                application { configureTestEnv() }
                val csrf = client.settingsCsrf()
                val response = postSettings(client, csrf)
                response.status shouldBe HttpStatusCode.OK
                response.headers[HtmxHeaders.HX_REDIRECT] shouldBe null
                val json = objectMapper.readTree(response.bodyAsText())
                json.fieldNames().asSequence().toSet() shouldBe setOf("configPublicationPending", "savedAt")
                json["configPublicationPending"].asBoolean() shouldBe true
                json["savedAt"].asText().shouldNotBeBlank()
                Instant.parse(json["savedAt"].asText())
            }
        }

        "settings save reports the immediate-publication state returned by config service" {
            everyStub(publicationPending = false)

            testApplication {
                application { configureTestEnv() }
                val csrf = client.settingsCsrf()
                val response = postSettings(client, csrf)
                response.status shouldBe HttpStatusCode.OK
                val json = objectMapper.readTree(response.bodyAsText())
                json["configPublicationPending"].asBoolean() shouldBe false
                Instant.parse(json["savedAt"].asText())
            }
        }
    }

    private fun everyStub(publicationPending: Boolean) {
        every { configService.getConfig() } returns TestFixtures.DEFAULT_TEST_CONFIG
        coEvery { configService.updateConfig(any()) } returns Unit
        coEvery { configService.isConfigPublicationPending() } returns publicationPending
    }
}
