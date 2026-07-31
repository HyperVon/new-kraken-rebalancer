package com.gemini.krakenbot.config

import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.options
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication

/**
 * Exercises the production Ktor CORS wiring in [configureCORS] via `testApplication`.
 * Confirms allowed private/loopback origins are echoed on preflight and actual requests,
 * while public origins (including public IPv6) are rejected with 403 before reaching the
 * route. The Origin-parsing predicate itself is exhaustively covered by `NetworkUtilsTest`;
 * these tests assert the plugin-level wiring only.
 */
class CORSConfigTest : StringSpec() {

    override fun isolationMode() = IsolationMode.InstancePerTest

    init {
        "preflight from a private IPv4 origin is allowed and echoes CORS headers" {
            testApplication {
                application {
                    configureCORS()
                    routing {
                        get("/test") { call.respondText("OK") }
                    }
                }

                val response = client.options("/test") {
                    header(HttpHeaders.Origin, "http://192.168.1.100:8080")
                    header(HttpHeaders.AccessControlRequestMethod, HttpMethod.Put.value)
                    header(HttpHeaders.AccessControlRequestHeaders, "Authorization, Content-Type")
                }

                response.status shouldBe HttpStatusCode.OK
                response.headers[HttpHeaders.AccessControlAllowOrigin] shouldBe "http://192.168.1.100:8080"
                response.headers[HttpHeaders.AccessControlAllowMethods] shouldContain "PUT"
                response.headers[HttpHeaders.AccessControlAllowHeaders] shouldContain "Authorization"
                response.headers[HttpHeaders.AccessControlAllowHeaders] shouldContain "Content-Type"
            }
        }

        "preflight from a loopback origin is allowed and echoes the request origin" {
            testApplication {
                application {
                    configureCORS()
                    routing {
                        get("/test") { call.respondText("OK") }
                    }
                }

                val response = client.options("/test") {
                    header(HttpHeaders.Origin, "http://localhost:8080")
                    header(HttpHeaders.AccessControlRequestMethod, HttpMethod.Delete.value)
                }

                response.status shouldBe HttpStatusCode.OK
                response.headers[HttpHeaders.AccessControlAllowOrigin] shouldBe "http://localhost:8080"
                response.headers[HttpHeaders.AccessControlAllowMethods] shouldContain "DELETE"
            }
        }

        "preflight from a public domain origin is rejected with 403 and emits no CORS headers" {
            testApplication {
                application {
                    configureCORS()
                    routing {
                        get("/test") { call.respondText("OK") }
                    }
                }

                val response = client.options("/test") {
                    header(HttpHeaders.Origin, "https://kraken.com")
                    header(HttpHeaders.AccessControlRequestMethod, HttpMethod.Put.value)
                }

                response.status shouldBe HttpStatusCode.Forbidden
                response.headers[HttpHeaders.AccessControlAllowOrigin].shouldBeNull()
            }
        }

        "preflight from a public IPv6 origin is rejected with 403" {
            testApplication {
                application {
                    configureCORS()
                    routing {
                        get("/test") { call.respondText("OK") }
                    }
                }

                val response = client.options("/test") {
                    header(HttpHeaders.Origin, "http://[2001:db8::1]")
                    header(HttpHeaders.AccessControlRequestMethod, HttpMethod.Put.value)
                }

                response.status shouldBe HttpStatusCode.Forbidden
                response.headers[HttpHeaders.AccessControlAllowOrigin].shouldBeNull()
            }
        }

        "GET from an allowed private origin echoes ACAO and reaches the route" {
            testApplication {
                application {
                    configureCORS()
                    routing {
                        get("/test") { call.respondText("OK") }
                    }
                }

                val response = client.get("/test") {
                    header(HttpHeaders.Origin, "http://localhost:8080")
                }

                response.status shouldBe HttpStatusCode.OK
                response.bodyAsText() shouldBe "OK"
                response.headers[HttpHeaders.AccessControlAllowOrigin] shouldBe "http://localhost:8080"
            }
        }

        "GET from a rejected public origin returns 403 and does not reach the route" {
            testApplication {
                application {
                    configureCORS()
                    routing {
                        get("/test") { call.respondText("OK") }
                    }
                }

                val response = client.get("/test") {
                    header(HttpHeaders.Origin, "https://google.com:443")
                }

                response.status shouldBe HttpStatusCode.Forbidden
                response.bodyAsText() shouldNotContain "OK"
                response.headers[HttpHeaders.AccessControlAllowOrigin].shouldBeNull()
            }
        }

        "request without an Origin header bypasses CORS and reaches the route" {
            testApplication {
                application {
                    configureCORS()
                    routing {
                        get("/test") { call.respondText("OK") }
                    }
                }

                val response = client.get("/test")

                response.status shouldBe HttpStatusCode.OK
                response.bodyAsText() shouldBe "OK"
                response.headers[HttpHeaders.AccessControlAllowOrigin].shouldBeNull()
            }
        }
    }
}
