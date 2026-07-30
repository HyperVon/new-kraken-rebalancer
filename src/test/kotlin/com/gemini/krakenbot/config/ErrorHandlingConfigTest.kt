package com.gemini.krakenbot.config

import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.gemini.krakenbot.config.ErrorHandlingConfig.configureErrorHandling
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.application.call
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module

class ErrorHandlingConfigTest : StringSpec() {

    override fun isolationMode() = IsolationMode.InstancePerTest

    init {
        val testModule = module {
            single { jacksonObjectMapper().registerModule(JavaTimeModule()) }
        }

        beforeTest {
            stopKoin()
            startKoin { modules(testModule) }
        }

        afterTest {
            stopKoin()
        }

        "should return 404 for unknown routes" {
            testApplication {
                application {
                    configureErrorHandling()
                    routing {
                        get("/test") { call.respondText("OK") }
                    }
                }

                val response = client.get("/nonexistent")
                response.status shouldBe HttpStatusCode.NotFound

                val body = response.bodyAsText()
                body shouldContain "\"status\":404"
                body shouldContain "\"error\":\"Not Found\""
                body shouldContain "\"message\":\"The requested resource was not found.\""
                body shouldContain "\"timestamp\""
            }
        }

        "should return 405 for method not allowed" {
            testApplication {
                application {
                    configureErrorHandling()
                    routing {
                        get("/test") { call.respondText("OK") }
                    }
                }

                val response = client.post("/test")
                response.status shouldBe HttpStatusCode.MethodNotAllowed

                val body = response.bodyAsText()
                body shouldContain "\"status\":405"
                body shouldContain "\"error\":\"Method Not Allowed\""
            }
        }

        "should return 400 for bad request" {
            testApplication {
                application {
                    configureErrorHandling()
                    routing {
                        get("/test") {
                            throw IllegalArgumentException("Invalid parameter")
                        }
                    }
                }

                val response = client.get("/test")
                response.status shouldBe HttpStatusCode.BadRequest

                val body = response.bodyAsText()
                body shouldContain "\"status\":400"
                body shouldContain "\"error\":\"Bad Request\""
                body shouldContain "\"message\":\"Invalid parameter\""
            }
        }

        "should return 500 for illegal state exception" {
            testApplication {
                application {
                    configureErrorHandling()
                    routing {
                        get("/test") {
                            throw IllegalStateException("Service unavailable")
                        }
                    }
                }

                val response = client.get("/test")
                response.status shouldBe HttpStatusCode.InternalServerError

                val body = response.bodyAsText()
                body shouldContain "\"status\":500"
                body shouldContain "\"error\":\"Internal Server Error\""
                body shouldContain "\"message\":\"An unexpected error occurred.\""
            }
        }

        "should return 500 for generic exception" {
            testApplication {
                application {
                    configureErrorHandling()
                    routing {
                        get("/test") {
                            throw RuntimeException("Unexpected failure")
                        }
                    }
                }

                val response = client.get("/test")
                response.status shouldBe HttpStatusCode.InternalServerError

                val body = response.bodyAsText()
                body shouldContain "\"status\":500"
                body shouldContain "\"error\":\"Internal Server Error\""
                body shouldContain "\"message\":\"An unexpected error occurred.\""
            }
        }

        "should return JSON content type" {
            testApplication {
                application {
                    configureErrorHandling()
                    routing {
                        get("/test") {
                            throw IllegalArgumentException("test")
                        }
                    }
                }

                val response = client.get("/test")
                response.contentType().toString() shouldContain "application/json"
            }
        }

        "should include timestamp in error response" {
            testApplication {
                application {
                    configureErrorHandling()
                    routing {
                        get("/test") {
                            throw IllegalArgumentException("test")
                        }
                    }
                }

                val response = client.get("/test")
                val body = response.bodyAsText()
                body shouldContain "\"timestamp\":\""
            }
        }
    }
}
