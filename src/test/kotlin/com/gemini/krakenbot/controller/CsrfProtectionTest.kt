package com.gemini.krakenbot.controller

import com.gemini.krakenbot.view.util.FormFields
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.formUrlEncode
import io.ktor.http.parametersOf
import io.ktor.server.application.call
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication

class CsrfProtectionTest :
    StringSpec({
        "isValid_WithValidOrigin_AcceptsRequest" {
            testApplication {
                routing {
                    post("/test-csrf") {
                        val params = call.receiveParameters()
                        if (CsrfProtection.isValid(call, params)) {
                            call.respondText("OK")
                        } else {
                            call.respondText("FORBIDDEN", status = HttpStatusCode.Forbidden)
                        }
                    }
                }

                val response = client.post("/test-csrf") {
                    header(HttpHeaders.Cookie, "rebalancer-csrf=secret123")
                    header(HttpHeaders.Origin, "http://localhost:8080")
                    header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded.toString())
                    setBody(parametersOf(FormFields.CSRF_TOKEN, "secret123").formUrlEncode())
                }
                response.status shouldBe HttpStatusCode.OK
            }
        }

        "isValid_WithUntrustedOrigin_RejectsRequest" {
            testApplication {
                routing {
                    post("/test-csrf") {
                        val params = call.receiveParameters()
                        if (CsrfProtection.isValid(call, params)) {
                            call.respondText("OK")
                        } else {
                            call.respondText("FORBIDDEN", status = HttpStatusCode.Forbidden)
                        }
                    }
                }

                val response = client.post("/test-csrf") {
                    header(HttpHeaders.Cookie, "rebalancer-csrf=secret123")
                    header(HttpHeaders.Origin, "https://evil.com")
                    header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded.toString())
                    setBody(parametersOf(FormFields.CSRF_TOKEN, "secret123").formUrlEncode())
                }
                response.status shouldBe HttpStatusCode.Forbidden
            }
        }

        "isValid_WithNoOriginAndValidReferer_AcceptsRequest" {
            testApplication {
                routing {
                    post("/test-csrf") {
                        val params = call.receiveParameters()
                        if (CsrfProtection.isValid(call, params)) {
                            call.respondText("OK")
                        } else {
                            call.respondText("FORBIDDEN", status = HttpStatusCode.Forbidden)
                        }
                    }
                }

                val response = client.post("/test-csrf") {
                    header(HttpHeaders.Cookie, "rebalancer-csrf=secret123")
                    header(HttpHeaders.Referrer, "http://192.168.1.10:8080/settings?tab=1#top")
                    header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded.toString())
                    setBody(parametersOf(FormFields.CSRF_TOKEN, "secret123").formUrlEncode())
                }
                response.status shouldBe HttpStatusCode.OK
            }
        }

        "isValid_WithNoOriginAndUntrustedReferer_RejectsRequest" {
            testApplication {
                routing {
                    post("/test-csrf") {
                        val params = call.receiveParameters()
                        if (CsrfProtection.isValid(call, params)) {
                            call.respondText("OK")
                        } else {
                            call.respondText("FORBIDDEN", status = HttpStatusCode.Forbidden)
                        }
                    }
                }

                val response = client.post("/test-csrf") {
                    header(HttpHeaders.Cookie, "rebalancer-csrf=secret123")
                    header(HttpHeaders.Referrer, "https://evil.com/csrf-attack")
                    header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded.toString())
                    setBody(parametersOf(FormFields.CSRF_TOKEN, "secret123").formUrlEncode())
                }
                response.status shouldBe HttpStatusCode.Forbidden
            }
        }

        "isValid_WithNoOriginAndMalformedReferer_RejectsRequest" {
            testApplication {
                routing {
                    post("/test-csrf") {
                        val params = call.receiveParameters()
                        if (CsrfProtection.isValid(call, params)) {
                            call.respondText("OK")
                        } else {
                            call.respondText("FORBIDDEN", status = HttpStatusCode.Forbidden)
                        }
                    }
                }

                val response = client.post("/test-csrf") {
                    header(HttpHeaders.Cookie, "rebalancer-csrf=secret123")
                    header(HttpHeaders.Referrer, "not a uri")
                    header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded.toString())
                    setBody(parametersOf(FormFields.CSRF_TOKEN, "secret123").formUrlEncode())
                }
                response.status shouldBe HttpStatusCode.Forbidden
            }
        }

        "isValid_WithNeitherOriginNorReferer_FallthroughToDoubleSubmitCheck" {
            testApplication {
                routing {
                    post("/test-csrf") {
                        val params = call.receiveParameters()
                        if (CsrfProtection.isValid(call, params)) {
                            call.respondText("OK")
                        } else {
                            call.respondText("FORBIDDEN", status = HttpStatusCode.Forbidden)
                        }
                    }
                }

                val response = client.post("/test-csrf") {
                    header(HttpHeaders.Cookie, "rebalancer-csrf=secret123")
                    header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded.toString())
                    setBody(parametersOf(FormFields.CSRF_TOKEN, "secret123").formUrlEncode())
                }
                response.status shouldBe HttpStatusCode.OK
            }
        }

        "isValid_WithValidOriginAndMismatchedToken_RejectsRequest" {
            testApplication {
                routing {
                    post("/test-csrf") {
                        val params = call.receiveParameters()
                        if (CsrfProtection.isValid(call, params)) {
                            call.respondText("OK")
                        } else {
                            call.respondText("FORBIDDEN", status = HttpStatusCode.Forbidden)
                        }
                    }
                }

                val response = client.post("/test-csrf") {
                    header(HttpHeaders.Cookie, "rebalancer-csrf=secret123")
                    header(HttpHeaders.Origin, "http://localhost:8080")
                    header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded.toString())
                    setBody(parametersOf(FormFields.CSRF_TOKEN, "wrongtoken").formUrlEncode())
                }
                response.status shouldBe HttpStatusCode.Forbidden
            }
        }
    })
