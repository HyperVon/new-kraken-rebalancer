package com.gemini.krakenbot.controller

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication

@Suppress("unused")
class CsrfTokenIssuanceTest : StringSpec() {
    init {
        "issued CSRF cookies retain browser protections and an authentic token is reused" {
            testApplication {
                routing {
                    get("/token") {
                        call.respondText(CsrfProtection.issueToken(call))
                    }
                }

                val first = client.get("/token")
                val token = first.bodyAsText()
                first.headers[HttpHeaders.SetCookie]?.contains("HttpOnly") shouldBe true
                first.headers[HttpHeaders.SetCookie]?.contains("SameSite=Strict") shouldBe true
                first.headers[HttpHeaders.SetCookie]?.contains("Path=/") shouldBe true

                val reused = client.get("/token") {
                    header(HttpHeaders.Cookie, "rebalancer-csrf=$token")
                }
                reused.bodyAsText() shouldBe token
                reused.headers[HttpHeaders.SetCookie] shouldBe null
            }
        }

        "currentToken refreshes a blank or unauthentic cookie" {
            testApplication {
                routing {
                    get("/token") {
                        call.respondText(CsrfProtection.currentToken(call))
                    }
                }

                listOf("rebalancer-csrf=", "rebalancer-csrf=forged-token").forEach { cookie ->
                    val response = client.get("/token") {
                        header(HttpHeaders.Cookie, cookie)
                    }
                    val token = response.bodyAsText()
                    (token != cookie.substringAfter('=')) shouldBe true
                    response.headers[HttpHeaders.SetCookie]?.substringBefore(';') shouldBe
                        "rebalancer-csrf=$token"
                }
            }
        }
    }
}
