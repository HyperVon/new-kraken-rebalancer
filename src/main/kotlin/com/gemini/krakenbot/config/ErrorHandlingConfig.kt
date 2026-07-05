package com.gemini.krakenbot.config

import com.fasterxml.jackson.databind.ObjectMapper
import io.ktor.http.*
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.response.*
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import org.slf4j.LoggerFactory

/**
 * Error handling and status pages configuration for HTTP responses.
 * Provides consistent error responses with proper logging and HTTP status codes.
 */
object ErrorHandlingConfig : KoinComponent {
    private val objectMapper: ObjectMapper by inject()
    private val log = LoggerFactory.getLogger(ErrorHandlingConfig::class.java)

    /**
     * Configure status pages and error handling for the application.
     */
    fun Application.configureErrorHandling() {
        install(StatusPages) {
            // Handle 400 Bad Request
            status(HttpStatusCode.BadRequest) { call, status ->
                log.warn("Bad Request")
                call.respondText(
                    text = buildErrorJson(
                        status = status.value,
                        error = "Bad Request",
                        message = "The request could not be understood by the server."
                    ),
                    status = status,
                    contentType = ContentType.Application.Json
                )
            }

            // Handle 404 Not Found
            status(HttpStatusCode.NotFound) { call, status ->
                log.warn("Not Found")
                call.respondText(
                    text = buildErrorJson(
                        status = status.value,
                        error = "Not Found",
                        message = "The requested resource was not found."
                    ),
                    status = status,
                    contentType = ContentType.Application.Json
                )
            }

            // Handle 405 Method Not Allowed
            status(HttpStatusCode.MethodNotAllowed) { call, status ->
                log.warn("Method Not Allowed")
                call.respondText(
                    text = buildErrorJson(
                        status = status.value,
                        error = "Method Not Allowed",
                        message = "The HTTP method is not allowed for this resource."
                    ),
                    status = status,
                    contentType = ContentType.Application.Json
                )
            }

            // Handle 500 Internal Server Error
            status(HttpStatusCode.InternalServerError) { call, status ->
                log.error("Internal Server Error")
                call.respondText(
                    text = buildErrorJson(
                        status = status.value,
                        error = "Internal Server Error",
                        message = "An unexpected error occurred processing the request."
                    ),
                    status = status,
                    contentType = ContentType.Application.Json
                )
            }

            // Handle 503 Service Unavailable
            status(HttpStatusCode.ServiceUnavailable) { call, status ->
                log.error("Service Unavailable")
                call.respondText(
                    text = buildErrorJson(
                        status = status.value,
                        error = "Service Unavailable",
                        message = "The service is temporarily unavailable. Please try again later."
                    ),
                    status = status,
                    contentType = ContentType.Application.Json
                )
            }

            // Handle all other exceptions
            exception<Exception> { call, cause ->
                log.error("Unhandled exception: {}", cause.message, cause)
                val status = when (cause) {
                    is IllegalArgumentException -> HttpStatusCode.BadRequest
                    is IllegalStateException -> HttpStatusCode.InternalServerError
                    else -> HttpStatusCode.InternalServerError
                }

                call.respondText(
                    text = buildErrorJson(
                        status = status.value,
                        error = status.description,
                        message = cause.message ?: "An unexpected error occurred."
                    ),
                    status = status,
                    contentType = ContentType.Application.Json
                )
            }
        }
    }

    /**
     * Build a JSON error response string using the provided ObjectMapper.
     */
    private fun buildErrorJson(
        status: Int,
        error: String,
        message: String
    ): String {
        val errorBody = mapOf(
            "timestamp" to java.time.Instant.now().toString(),
            "status" to status,
            "error" to error,
            "message" to message
        )
        return objectMapper.writeValueAsString(errorBody)
    }

}
