package com.gemini.krakenbot.controller

import com.gemini.krakenbot.view.util.FormFields
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.URLProtocol
import io.ktor.http.formUrlEncode
import io.ktor.http.parametersOf
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication

private data class IssuedCsrfToken(val value: String, val cookie: String)

private fun Route.csrfTestRoutes() {
    get("/issue-csrf") {
        call.respondText(CsrfProtection.issueToken(call))
    }
    post("/test-csrf") {
        val valid = CsrfProtection.isValid(call, call.receiveParameters())
        call.respondText(
            if (valid) "OK" else "FORBIDDEN",
            status = if (valid) HttpStatusCode.OK else HttpStatusCode.Forbidden,
        )
    }
    post("/recover-stale-csrf") {
        val parameters = call.receiveParameters()
        if (!CsrfProtection.isRefreshableStaleTokenPair(call, parameters)) {
            call.respondText("FORBIDDEN", status = HttpStatusCode.Forbidden)
        } else {
            call.respondText(CsrfProtection.rotateToken(call))
        }
    }
}

private suspend fun HttpClient.issueCsrf(requestProtocol: URLProtocol = URLProtocol.HTTP): IssuedCsrfToken {
    val response = get("/issue-csrf") {
        url { protocol = requestProtocol }
    }
    val token = response.bodyAsText()
    val cookie = response.headers[HttpHeaders.SetCookie]?.substringBefore(';')
        ?: error("CSRF issuance did not set a cookie")
    return IssuedCsrfToken(token, cookie)
}

private suspend fun HttpClient.postCsrf(
    csrf: IssuedCsrfToken,
    host: String? = "localhost",
    origin: String? = "http://localhost",
    referer: String? = null,
    refererHeaders: List<String> = referer?.let { listOf(it) }.orEmpty(),
    formTokens: List<String>? = listOf(csrf.value),
    cookieHeader: String? = csrf.cookie,
    originHeaders: List<String> = origin?.let { listOf(it) }.orEmpty(),
    forwardedHost: String? = null,
    forwardedProto: String? = null,
    requestProtocol: URLProtocol = URLProtocol.HTTP,
) = post("/test-csrf") {
    url { protocol = requestProtocol }
    host?.let { header(HttpHeaders.Host, it) }
    cookieHeader?.let { header(HttpHeaders.Cookie, it) }
    originHeaders.forEach { headers.append(HttpHeaders.Origin, it) }
    refererHeaders.forEach { headers.append(HttpHeaders.Referrer, it) }
    forwardedHost?.let { header("X-Forwarded-Host", it) }
    forwardedProto?.let { header("X-Forwarded-Proto", it) }
    header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded.toString())
    setBody((formTokens?.let { parametersOf(FormFields.CSRF_TOKEN, it) } ?: parametersOf()).formUrlEncode())
}

private suspend fun HttpClient.postStaleCsrf(
    token: String,
    host: String = "localhost",
    origin: String? = "http://localhost",
    referer: String? = null,
    refererHeaders: List<String> = referer?.let { listOf(it) }.orEmpty(),
    originHeaders: List<String> = origin?.let { listOf(it) }.orEmpty(),
    cookieToken: String? = token,
    formTokens: List<String>? = listOf(token),
) = post("/recover-stale-csrf") {
    header(HttpHeaders.Host, host)
    cookieToken?.let { header(HttpHeaders.Cookie, "rebalancer-csrf=$it") }
    originHeaders.forEach { headers.append(HttpHeaders.Origin, it) }
    refererHeaders.forEach { headers.append(HttpHeaders.Referrer, it) }
    header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded.toString())
    setBody((formTokens?.let { parametersOf(FormFields.CSRF_TOKEN, it) } ?: parametersOf()).formUrlEncode())
}

@Suppress("unused")
class CsrfProtectionTest : StringSpec() {
    init {
        "issued token is reused only when authentic and a forged cookie is rotated" {
            testApplication {
                routing { csrfTestRoutes() }

                val issued = client.issueCsrf()
                val reused = client.get("/issue-csrf") {
                    header(HttpHeaders.Cookie, issued.cookie)
                }
                reused.bodyAsText() shouldBe issued.value
                reused.headers[HttpHeaders.SetCookie] shouldBe null

                val forged = client.get("/issue-csrf") {
                    header(HttpHeaders.Cookie, "rebalancer-csrf=attacker-value")
                }
                val refreshed = forged.bodyAsText()
                (refreshed != "attacker-value") shouldBe true
                forged.headers[HttpHeaders.SetCookie]?.substringBefore(';') shouldBe "rebalancer-csrf=$refreshed"

                val malformedShape = "A".repeat(87)
                val malformedShapeResponse = client.get("/issue-csrf") {
                    header(HttpHeaders.Cookie, "rebalancer-csrf=$malformedShape")
                }
                val replacedMalformedShape = malformedShapeResponse.bodyAsText()
                (replacedMalformedShape != malformedShape) shouldBe true
                malformedShapeResponse.headers[HttpHeaders.SetCookie]
                    ?.substringBefore(';') shouldBe "rebalancer-csrf=$replacedMalformedShape"

                val rejected = client.postCsrf(
                    csrf = IssuedCsrfToken("attacker-value", "rebalancer-csrf=attacker-value"),
                )
                rejected.status shouldBe HttpStatusCode.Forbidden
            }
        }

        "HTTPS cookie issuance is secure and accepts a matching HTTPS mutation origin" {
            testApplication {
                routing { csrfTestRoutes() }

                val httpResponse = client.get("/issue-csrf")
                (httpResponse.headers[HttpHeaders.SetCookie]?.contains("; Secure") == true) shouldBe false

                val httpsResponse = client.get("/issue-csrf") {
                    url { protocol = URLProtocol.HTTPS }
                }
                val httpsToken = httpsResponse.bodyAsText()
                val setCookie = httpsResponse.headers[HttpHeaders.SetCookie]
                    ?: error("HTTPS CSRF issuance did not set a cookie")
                (setCookie.contains("; Secure")) shouldBe true

                val response = client.postCsrf(
                    csrf = IssuedCsrfToken(httpsToken, setCookie.substringBefore(';')),
                    origin = "https://localhost",
                    requestProtocol = URLProtocol.HTTPS,
                )
                response.status shouldBe HttpStatusCode.OK
            }
        }

        "same LAN request origin accepts an authentic double-submit token" {
            testApplication {
                routing { csrfTestRoutes() }

                val csrf = client.issueCsrf()
                val response = client.postCsrf(
                    csrf = csrf,
                    host = "192.168.1.50:8080",
                    origin = "http://192.168.1.50:8080",
                )
                response.status shouldBe HttpStatusCode.OK
            }
        }

        "a different LAN address or port cannot authorize a mutation" {
            testApplication {
                routing { csrfTestRoutes() }

                val csrf = client.issueCsrf()
                client.postCsrf(
                    csrf = csrf,
                    host = "192.168.1.50:8080",
                    origin = "http://192.168.1.51:8080",
                ).status shouldBe HttpStatusCode.Forbidden
                client.postCsrf(
                    csrf = csrf,
                    host = "192.168.1.50:8080",
                    origin = "http://192.168.1.50:8081",
                ).status shouldBe HttpStatusCode.Forbidden
            }
        }

        "a public attacker hostname cannot authorize its own DNS-rebound origin" {
            testApplication {
                routing { csrfTestRoutes() }

                val csrf = client.issueCsrf()
                val origin = "http://attacker.example:8080"
                client.postCsrf(
                    csrf = csrf,
                    host = "attacker.example:8080",
                    origin = origin,
                ).status shouldBe HttpStatusCode.Forbidden

                val staleRecovery = client.postStaleCsrf(
                    token = "A".repeat(43) + "." + "B".repeat(43),
                    host = "attacker.example:8080",
                    origin = origin,
                )
                staleRecovery.status shouldBe HttpStatusCode.Forbidden
                staleRecovery.headers[HttpHeaders.SetCookie] shouldBe null
            }
        }

        "same-origin comparison normalizes host case and the default port" {
            testApplication {
                routing { csrfTestRoutes() }

                val csrf = client.issueCsrf()
                val response = client.postCsrf(
                    csrf = csrf,
                    host = "LOCALHOST",
                    origin = "HTTP://localhost:80",
                )
                response.status shouldBe HttpStatusCode.OK
            }
        }

        "origin normalization uses scheme defaults when Host is absent or names the default port" {
            testApplication {
                routing { csrfTestRoutes() }

                val csrf = client.issueCsrf()
                client.postCsrf(
                    csrf = csrf,
                    host = null,
                    origin = "http://localhost",
                ).status shouldBe HttpStatusCode.OK
                client.postCsrf(
                    csrf = csrf,
                    host = "localhost:80",
                    origin = "http://localhost",
                ).status shouldBe HttpStatusCode.OK
                client.postCsrf(
                    csrf = csrf,
                    host = null,
                    origin = "https://localhost",
                    requestProtocol = URLProtocol.HTTPS,
                ).status shouldBe HttpStatusCode.OK
                client.postCsrf(
                    csrf = csrf,
                    host = "localhost:443",
                    origin = "https://localhost",
                    requestProtocol = URLProtocol.HTTPS,
                ).status shouldBe HttpStatusCode.OK
            }
        }

        "same-origin comparison accepts both inclusive TCP port boundaries" {
            testApplication {
                routing { csrfTestRoutes() }

                val csrf = client.issueCsrf()
                client.postCsrf(
                    csrf = csrf,
                    host = "localhost:1",
                    origin = "http://localhost:1",
                ).status shouldBe HttpStatusCode.OK
                client.postCsrf(
                    csrf = csrf,
                    host = "localhost:65535",
                    origin = "http://localhost:65535",
                ).status shouldBe HttpStatusCode.OK
            }
        }

        "same-origin referer is accepted while public and malformed origins are rejected" {
            testApplication {
                routing { csrfTestRoutes() }

                val csrf = client.issueCsrf()
                client.postCsrf(
                    csrf = csrf,
                    origin = null,
                    referer = "http://localhost/settings?tab=1#top",
                ).status shouldBe HttpStatusCode.OK
                client.postCsrf(
                    csrf = csrf,
                    origin = null,
                    referer = "http://localhost/settings,tab=1",
                ).status shouldBe HttpStatusCode.OK
                client.postCsrf(
                    csrf = csrf,
                    origin = null,
                    referer = "http://localhost/settings,mailto:operator@example.test",
                ).status shouldBe HttpStatusCode.OK
                client.postCsrf(
                    csrf = csrf,
                    origin = null,
                    referer = "mailto:operator@example.test,http://localhost/settings",
                ).status shouldBe HttpStatusCode.Forbidden
                client.postCsrf(csrf = csrf, origin = "https://evil.example").status shouldBe
                    HttpStatusCode.Forbidden
                client.postCsrf(csrf = csrf, origin = "https://localhost").status shouldBe
                    HttpStatusCode.Forbidden
                client.postCsrf(csrf = csrf, origin = "http://localhost:0").status shouldBe
                    HttpStatusCode.Forbidden
                client.postCsrf(csrf = csrf, origin = "http://localhost:65536").status shouldBe
                    HttpStatusCode.Forbidden
                client.postCsrf(csrf = csrf, origin = "http://localhost:999999999999").status shouldBe
                    HttpStatusCode.Forbidden
                client.postCsrf(csrf = csrf, origin = "http://localhost:").status shouldBe
                    HttpStatusCode.Forbidden
                client.postCsrf(csrf = csrf, origin = "null").status shouldBe HttpStatusCode.Forbidden
                client.postCsrf(csrf = csrf, origin = "http://user@localhost").status shouldBe
                    HttpStatusCode.Forbidden
                client.postCsrf(csrf = csrf, origin = "http://localhost/path").status shouldBe
                    HttpStatusCode.Forbidden
            }
        }

        "missing origin metadata permits only an authentic token and duplicate origin or form values fail closed" {
            testApplication {
                routing { csrfTestRoutes() }

                val csrf = client.issueCsrf()
                client.postCsrf(csrf = csrf, origin = null).status shouldBe HttpStatusCode.OK
                client.postCsrf(
                    csrf = csrf,
                    origin = null,
                    formTokens = listOf("invalid-token"),
                ).status shouldBe HttpStatusCode.Forbidden
                client.postCsrf(csrf = csrf, originHeaders = listOf("http://localhost", "http://localhost"))
                    .status shouldBe HttpStatusCode.Forbidden
                client.postCsrf(
                    csrf = csrf,
                    formTokens = listOf(csrf.value, csrf.value),
                ).status shouldBe HttpStatusCode.Forbidden
            }
        }

        "mutations require an authentic cookie and one matching authentic form token" {
            testApplication {
                routing { csrfTestRoutes() }

                val csrf = client.issueCsrf()
                val differentCsrf = client.issueCsrf()

                client.postCsrf(csrf = csrf, cookieHeader = null).status shouldBe HttpStatusCode.Forbidden
                client.postCsrf(csrf = csrf, formTokens = null).status shouldBe HttpStatusCode.Forbidden
                client.postCsrf(
                    csrf = csrf,
                    formTokens = listOf("forged-form-token"),
                ).status shouldBe HttpStatusCode.Forbidden
                client.postCsrf(
                    csrf = csrf,
                    formTokens = listOf("A".repeat(87)),
                ).status shouldBe HttpStatusCode.Forbidden
                client.postCsrf(
                    csrf = csrf,
                    formTokens = listOf(differentCsrf.value),
                ).status shouldBe HttpStatusCode.Forbidden
            }
        }

        "a missing Origin rejects mismatched and comma-coalesced Referer values" {
            testApplication {
                routing { csrfTestRoutes() }

                val csrf = client.issueCsrf()
                client.postCsrf(
                    csrf = csrf,
                    origin = null,
                    referer = "http://different.local/settings",
                ).status shouldBe HttpStatusCode.Forbidden
                client.postCsrf(
                    csrf = csrf,
                    origin = null,
                    referer = "http://localhost/settings,http://localhost/settings",
                ).status shouldBe HttpStatusCode.Forbidden
                client.postCsrf(
                    csrf = csrf,
                    origin = null,
                    refererHeaders = listOf(
                        "http://localhost/settings",
                        "http://localhost/settings",
                    ),
                ).status shouldBe HttpStatusCode.Forbidden
            }
        }

        "unconfigured forwarded headers do not create an alternate accepted origin" {
            testApplication {
                routing { csrfTestRoutes() }

                val csrf = client.issueCsrf()
                val response = client.postCsrf(
                    csrf = csrf,
                    host = "192.168.1.50:8080",
                    origin = "http://192.168.1.51:8080",
                    forwardedHost = "192.168.1.51:8080",
                    forwardedProto = "http",
                )
                response.status shouldBe HttpStatusCode.Forbidden
            }
        }

        "a matching stale token pair refreshes only on an explicit exact origin" {
            testApplication {
                routing { csrfTestRoutes() }

                // This has the signed-token shape but cannot authenticate with the current
                // process key, as happens when a tab retains a token from before restart.
                val staleToken = "A".repeat(43) + "." + "B".repeat(43)
                val recovered = client.postStaleCsrf(staleToken)
                recovered.status shouldBe HttpStatusCode.OK
                val freshToken = recovered.bodyAsText()
                (freshToken != staleToken) shouldBe true
                recovered.headers[HttpHeaders.SetCookie]
                    ?.substringBefore(';') shouldBe "rebalancer-csrf=$freshToken"

                val wrongPort = client.postStaleCsrf(
                    token = staleToken,
                    host = "192.168.1.50:8080",
                    origin = "http://192.168.1.50:8081",
                )
                wrongPort.status shouldBe HttpStatusCode.Forbidden
                wrongPort.headers[HttpHeaders.SetCookie] shouldBe null

                val validLanOrigin = client.postStaleCsrf(
                    token = staleToken,
                    host = "192.168.1.50:8080",
                    origin = "http://192.168.1.50:8080",
                )
                validLanOrigin.status shouldBe HttpStatusCode.OK

                val noOrigin = client.postStaleCsrf(token = staleToken, origin = null)
                noOrigin.status shouldBe HttpStatusCode.Forbidden
                noOrigin.headers[HttpHeaders.SetCookie] shouldBe null

                val malformed = client.postStaleCsrf(token = "forged-value")
                malformed.status shouldBe HttpStatusCode.Forbidden
                malformed.headers[HttpHeaders.SetCookie] shouldBe null

                val authentic = client.issueCsrf()
                val currentToken = client.postStaleCsrf(token = authentic.value)
                currentToken.status shouldBe HttpStatusCode.Forbidden
                currentToken.headers[HttpHeaders.SetCookie] shouldBe null

                val legacyToken = "A".repeat(43)
                val legacyRecovery = client.postStaleCsrf(token = legacyToken)
                legacyRecovery.status shouldBe HttpStatusCode.OK
                (legacyRecovery.bodyAsText() != legacyToken) shouldBe true

                client.postStaleCsrf(
                    token = staleToken,
                    formTokens = listOf("different-token"),
                ).status shouldBe HttpStatusCode.Forbidden
                client.postStaleCsrf(
                    token = staleToken,
                    formTokens = listOf(staleToken, staleToken),
                ).status shouldBe HttpStatusCode.Forbidden
                client.postStaleCsrf(token = staleToken, formTokens = null).status shouldBe
                    HttpStatusCode.Forbidden
                client.postStaleCsrf(token = staleToken, cookieToken = null).status shouldBe
                    HttpStatusCode.Forbidden
                client.postStaleCsrf(
                    token = staleToken,
                    originHeaders = listOf("http://localhost", "http://localhost"),
                ).status shouldBe HttpStatusCode.Forbidden
                val duplicateReferer = client.postStaleCsrf(
                    token = staleToken,
                    origin = null,
                    refererHeaders = listOf(
                        "http://localhost/settings",
                        "http://localhost/settings",
                    ),
                )
                duplicateReferer.status shouldBe HttpStatusCode.Forbidden
                duplicateReferer.headers[HttpHeaders.SetCookie] shouldBe null
                client.postStaleCsrf(
                    token = staleToken,
                    origin = null,
                    referer = "http://localhost/settings?tab=security#csrf",
                ).status shouldBe HttpStatusCode.OK
                client.postStaleCsrf(
                    token = staleToken,
                    origin = null,
                    referer = "http://localhost:8080/settings",
                ).status shouldBe HttpStatusCode.Forbidden
            }
        }

        "IPv6 origins and malformed host authorities fail closed when they do not match the request" {
            testApplication {
                routing { csrfTestRoutes() }

                val csrf = client.issueCsrf()
                client.postCsrf(csrf = csrf, origin = "http://[::1]").status shouldBe
                    HttpStatusCode.Forbidden
                client.postCsrf(csrf = csrf, origin = "http://[::1]:8080").status shouldBe
                    HttpStatusCode.Forbidden
                client.postCsrf(
                    csrf = csrf,
                    origin = "http://[::1]suffix:8080",
                ).status shouldBe HttpStatusCode.Forbidden
                client.postCsrf(
                    csrf = csrf,
                    origin = "http://::1",
                ).status shouldBe HttpStatusCode.Forbidden
                client.postCsrf(
                    csrf = csrf,
                    host = "app.local.:8080",
                    origin = "http://app.local:8080",
                ).status shouldBe HttpStatusCode.OK
                client.postCsrf(csrf = csrf, origin = "http://localhost?tab=1").status shouldBe
                    HttpStatusCode.Forbidden
                client.postCsrf(csrf = csrf, origin = "http://localhost#settings").status shouldBe
                    HttpStatusCode.Forbidden
                client.postCsrf(csrf = csrf, origin = "ftp://localhost").status shouldBe
                    HttpStatusCode.Forbidden
            }
        }

        "equivalent IPv6 loopback literals match the same bracketed request origin" {
            testApplication {
                routing { csrfTestRoutes() }

                val csrf = client.issueCsrf()
                val response = client.postCsrf(
                    csrf = csrf,
                    host = "[0:0:0:0:0:0:0:1]:8080",
                    origin = "http://[::1]:8080",
                )

                response.status shouldBe HttpStatusCode.OK
                client.postCsrf(
                    csrf = csrf,
                    host = "[::1]",
                    origin = "http://[0:0:0:0:0:0:0:1]",
                ).status shouldBe HttpStatusCode.OK
            }
        }

        "request origin normalization rejects malformed hosts and ports" {
            testApplication {
                routing { csrfTestRoutes() }

                val csrf = client.issueCsrf()
                listOf(
                    ".",
                    "bad host:80",
                    "bad_name.local",
                    "localhost:0",
                    "localhost:999999999999",
                    "localhost:not-a-port",
                    "localhost:",
                    ":80",
                    "::1",
                    "[::1",
                    "[::1]suffix",
                    "localhost:80:90",
                    "[::g1]",
                ).forEach { host ->
                    client.postCsrf(
                        csrf = csrf,
                        host = host,
                        origin = "http://localhost",
                    ).status shouldBe HttpStatusCode.Forbidden
                }
            }
        }

        "a U-label private LAN host matches its ASCII Origin form" {
            testApplication {
                routing { csrfTestRoutes() }

                val csrf = client.issueCsrf()
                client.postCsrf(
                    csrf = csrf,
                    host = "bücher.local:8080",
                    origin = "http://xn--bcher-kva.local:8080",
                ).status shouldBe HttpStatusCode.OK
            }
        }

        "stale token recovery rejects malformed, mismatched, or comma-coalesced Referer values" {
            testApplication {
                routing { csrfTestRoutes() }

                val staleToken = "A".repeat(43) + "." + "B".repeat(43)
                listOf(
                    "http://different.local/settings",
                    "http://localhost@evil.local/settings",
                    "http://localhost/settings,http://localhost/settings",
                    "relative,http://localhost/settings",
                    "http://localhost/settings,http://[",
                    "settings",
                    "mailto:operator@example.test",
                    "http:///settings",
                    "http://[",
                    "http://[,http://localhost/settings",
                ).forEach { referer ->
                    val response = client.postStaleCsrf(
                        token = staleToken,
                        origin = null,
                        referer = referer,
                    )
                    response.status shouldBe HttpStatusCode.Forbidden
                    response.headers[HttpHeaders.SetCookie] shouldBe null
                }

                val malformedSignedShape = "A".repeat(43) + ".!" + "B".repeat(42)
                val malformedRecovery = client.postStaleCsrf(token = malformedSignedShape)
                malformedRecovery.status shouldBe HttpStatusCode.Forbidden
                malformedRecovery.headers[HttpHeaders.SetCookie] shouldBe null
            }
        }

        "malformed request authorities with forbidden delimiters or bracketed ports fail closed" {
            testApplication {
                routing { csrfTestRoutes() }

                val csrf = client.issueCsrf()
                listOf(
                    "",
                    "localhost/path",
                    "localhost?tab=1",
                    "localhost#settings",
                    "localhost\\settings",
                    "localhost,localhost",
                    "localhost@evil.example",
                    "[::1]:",
                    "[::1]:0",
                    "[::1]:65536",
                    "[::g1]",
                ).forEach { authority ->
                    client.postCsrf(
                        csrf = csrf,
                        host = authority,
                        origin = "http://localhost",
                    ).status shouldBe HttpStatusCode.Forbidden
                }
            }
        }

        "bracketed IPv6 origins normalize the default port and reject malformed ports" {
            testApplication {
                routing { csrfTestRoutes() }

                val csrf = client.issueCsrf()
                client.postCsrf(
                    csrf = csrf,
                    host = "[::1]",
                    origin = "http://[::1]:80",
                ).status shouldBe HttpStatusCode.OK

                listOf(
                    "http://[::1]:",
                    "http://[::1]:0",
                    "http://[::1]:65536",
                ).forEach { origin ->
                    client.postCsrf(
                        csrf = csrf,
                        host = "[::1]",
                        origin = origin,
                    ).status shouldBe HttpStatusCode.Forbidden
                }
            }
        }

        "an IPv4-mapped IPv6 request authority is not normalized as an IPv4 origin" {
            testApplication {
                routing { csrfTestRoutes() }

                val csrf = client.issueCsrf()
                client.postCsrf(
                    csrf = csrf,
                    host = "[::ffff:127.0.0.1]",
                    origin = "http://127.0.0.1",
                ).status shouldBe HttpStatusCode.Forbidden
            }
        }

        "a signed-shaped stale cookie is replaced and cannot authorize a mutation" {
            testApplication {
                routing { csrfTestRoutes() }

                val staleToken = "A".repeat(43) + "." + "B".repeat(43)
                val refreshed = client.get("/issue-csrf") {
                    header(HttpHeaders.Cookie, "rebalancer-csrf=$staleToken")
                }
                val freshToken = refreshed.bodyAsText()
                (freshToken != staleToken) shouldBe true
                refreshed.headers[HttpHeaders.SetCookie]
                    ?.substringBefore(';') shouldBe "rebalancer-csrf=$freshToken"

                client.postCsrf(
                    csrf = IssuedCsrfToken(staleToken, "rebalancer-csrf=$staleToken"),
                ).status shouldBe HttpStatusCode.Forbidden
            }
        }
    }
}
