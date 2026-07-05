package com.gemini.krakenbot.config

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respondText
import org.slf4j.LoggerFactory
import java.time.Instant

/**
 * Error handling and status pages configuration for HTTP responses.
 * Provides consistent error responses with proper logging and HTTP status codes.
 */
object ErrorHandlingConfig {
    private val log = LoggerFactory.getLogger(ErrorHandlingConfig::class.java)

    /**
     * Standard error response structure.
     */
    data class ErrorResponse(
        val timestamp: String = Instant.now().toString(),
        val status: Int,
        val error: String,
        val message: String
    )

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
     * Build a JSON error response string.
     */
    private fun buildErrorJson(
        status: Int,
        error: String,
        message: String
    ): String {
        return buildString {
            append("{")
            append("\"timestamp\":\"${Instant.now()}\",")
            append("\"status\":$status,")
            append("\"error\":\"$error\",")
            append("\"message\":\"${escapeJson(message)}\"")
            append("}")
        }
    }

    /**
     * Escape special characters for JSON strings.
     */
    private fun escapeJson(value: String): String =
        value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
}
