package com.gemini.krakenbot.controller

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.ktor.http.HttpHeaders
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.header
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify

@Suppress("unused")
class CsrfTokenIssuanceTest : StringSpec() {
    init {

        "currentToken returns existing cookie when present and non-blank" {
            val call = mockk<ApplicationCall>(relaxed = true)
            every { call.request.cookies["rebalancer-csrf"] } returns "existing-token"
            CsrfProtection.currentToken(call) shouldBe "existing-token"
        }

        "currentToken falls back to issueToken when cookie blank" {
            val call = mockk<ApplicationCall>(relaxed = true)
            every { call.request.cookies["rebalancer-csrf"] } returns "   "
            val token = CsrfProtection.currentToken(call)
            token.isNotBlank() shouldBe true
            verify { call.response.header(HttpHeaders.SetCookie, any<String>()) }
        }

        "currentToken falls back to issueToken when cookie absent" {
            val call = mockk<ApplicationCall>(relaxed = true)
            every { call.request.cookies["rebalancer-csrf"] } returns null
            val token = CsrfProtection.currentToken(call)
            token.isNotBlank() shouldBe true
            verify { call.response.header(HttpHeaders.SetCookie, any<String>()) }
        }

        "issueToken reuses existing cookie when present" {
            val call = mockk<ApplicationCall>(relaxed = true)
            every { call.request.cookies["rebalancer-csrf"] } returns "reused-token"
            CsrfProtection.issueToken(call) shouldBe "reused-token"
        }

        "issueToken rotates a new token when cookie blank" {
            val call = mockk<ApplicationCall>(relaxed = true)
            every { call.request.cookies["rebalancer-csrf"] } returns ""
            val token = CsrfProtection.issueToken(call)
            token.isNotBlank() shouldBe true
            verify { call.response.header(HttpHeaders.SetCookie, any<String>()) }
        }

        "issueToken rotates a new token when cookie absent" {
            val call = mockk<ApplicationCall>(relaxed = true)
            every { call.request.cookies["rebalancer-csrf"] } returns null
            val token = CsrfProtection.issueToken(call)
            token.isNotBlank() shouldBe true
            verify { call.response.header(HttpHeaders.SetCookie, any<String>()) }
        }
    }
}
