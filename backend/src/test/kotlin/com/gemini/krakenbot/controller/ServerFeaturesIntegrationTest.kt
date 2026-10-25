@file:Suppress("unused")

package com.gemini.krakenbot.controller

import com.gemini.krakenbot.TestFixtures
import com.gemini.krakenbot.config.ErrorHandlingConfig.configureErrorHandling
import com.gemini.krakenbot.config.configureCachingAndConditionalHeaders
import com.gemini.krakenbot.config.configureCompression
import io.kotest.core.spec.IsolationMode
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.formUrlEncode
import io.ktor.http.parametersOf
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.mockk.every

class ServerFeaturesIntegrationTest : DashboardControllerTestBase() {
    override fun isolationMode() = IsolationMode.InstancePerTest

    private var paused = false

    init {
        beforeTest {
            paused = false
            every { configService.getConfig() } returns TestFixtures.DEFAULT_TEST_CONFIG
            every { portfolioManager.isLoopPaused() } answers { paused }
            every { portfolioManager.pauseLoop() } answers { paused = true }
            every { portfolioManager.resumeLoop() } answers { paused = false }
        }

        "compression feature compresses html responses" {
            testApplication {
                application {
                    configureTestEnv()
                    configureCompression()
                }
                val response = client.get("/") {
                    header(HttpHeaders.AcceptEncoding, TestFixtures.GZIP)
                }
                response.status shouldBe HttpStatusCode.OK
                response.headers[HttpHeaders.ContentEncoding] shouldBe TestFixtures.GZIP
            }
        }

        "caching features set cache headers on CSS stylesheet responses" {
            testApplication {
                application {
                    configureTestEnv()
                    configureCachingAndConditionalHeaders()
                }
                val response = client.get("/static/style.css")
                response.status shouldBe HttpStatusCode.OK
                response.headers[HttpHeaders.CacheControl] shouldContain "max-age=86400"
                val stylesheet = response.bodyAsText()
                stylesheet shouldContain ".nav-bar"
            }
        }

        "error handling hides internal 500 details while preserving safe 400 details" {
            testApplication {
                application {
                    configureErrorHandling()
                    routing {
                        get("/test/internal-error") {
                            throw RuntimeException("SQL failed at /private/app/kraken-rebalancer.db")
                        }
                        get("/test/invalid-request") {
                            throw IllegalArgumentException("Allocation total must equal 100%")
                        }
                    }
                }

                val internalError = client.get("/test/internal-error")
                internalError.status shouldBe HttpStatusCode.InternalServerError
                internalError.bodyAsText() shouldContain "An unexpected error occurred."
                internalError.bodyAsText() shouldNotContain "/private/app/kraken-rebalancer.db"

                val invalidRequest = client.get("/test/invalid-request")
                invalidRequest.status shouldBe HttpStatusCode.BadRequest
                invalidRequest.bodyAsText() shouldContain "Allocation total must equal 100%"
            }
        }

        "pause endpoint sets the loop paused state and health reflects it" {
            testApplication {
                application { configureTestEnv() }
                val csrf = client.settingsCsrf()
                val pauseResponse = client.post("/api/pause") {
                    setBody(parametersOf("csrfToken", csrf.value).formUrlEncode())
                    header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded.toString())
                    header(HttpHeaders.Cookie, csrf.cookie)
                }
                pauseResponse.status shouldBe HttpStatusCode.OK
                pauseResponse.headers["HX-Refresh"] shouldBe "true"
                pauseResponse.bodyAsText() shouldContain "true"
                client.get("/api/health").bodyAsText() shouldContain "\"paused\":true"
            }
        }

        "resume endpoint clears the paused state" {
            testApplication {
                application { configureTestEnv() }
                val csrf = client.settingsCsrf()
                client.post("/api/pause") {
                    setBody(parametersOf("csrfToken", csrf.value).formUrlEncode())
                    header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded.toString())
                    header(HttpHeaders.Cookie, csrf.cookie)
                }
                val resumeResponse = client.post("/api/resume") {
                    setBody(parametersOf("csrfToken", csrf.value).formUrlEncode())
                    header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded.toString())
                    header(HttpHeaders.Cookie, csrf.cookie)
                }

                resumeResponse.status shouldBe HttpStatusCode.OK
                resumeResponse.headers["HX-Refresh"] shouldBe "true"
                resumeResponse.bodyAsText() shouldContain "false"
                client.get("/api/health").bodyAsText() shouldContain "\"paused\":false"
            }
        }
    }
}
