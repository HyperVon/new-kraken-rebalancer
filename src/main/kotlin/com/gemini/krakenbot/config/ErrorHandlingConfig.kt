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
import java.time.Instant

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
            val statusErrors =
                listOf(
                    Triple(
                        HttpStatusCode.BadRequest,
                        "Bad Request",
                        "The request could not be understood by the server.",
                    ),
                    Triple(HttpStatusCode.NotFound, "Not Found", "The requested resource was not found."),
                    Triple(
                        HttpStatusCode.MethodNotAllowed,
                        "Method Not Allowed",
                        "The HTTP method is not allowed for this resource.",
                    ),
                    Triple(
                        HttpStatusCode.InternalServerError,
                        "Internal Server Error",
                        "An unexpected error occurred processing the request.",
                    ),
                    Triple(
                        HttpStatusCode.ServiceUnavailable,
                        "Service Unavailable",
                        "The service is temporarily unavailable. Please try again later.",
                    ),
                )

            statusErrors.forEach { (httpStatus, errorName, errorMsg) ->
                status(httpStatus) { call, status ->
                    if (httpStatus.value >= 500) {
                        log.error(errorName)
                    } else {
                        log.warn(errorName)
                    }
                    call.respondText(
                        text =
                        buildErrorJson(
                            status = status.value,
                            error = errorName,
                            message = errorMsg,
                        ),
                        status = status,
                        contentType = ContentType.Application.Json,
                    )
                }
            }

            // Handle all other exceptions
            exception<Exception> { call, cause ->
                log.error("Unhandled exception: {}", cause.message, cause)
                val status =
                    when (cause) {
                        is IllegalArgumentException -> HttpStatusCode.BadRequest
                        is IllegalStateException -> HttpStatusCode.InternalServerError
                        else -> HttpStatusCode.InternalServerError
                    }

                call.respondText(
                    text =
                    buildErrorJson(
                        status = status.value,
                        error = status.description,
                        message = cause.message ?: "An unexpected error occurred.",
                    ),
                    status = status,
                    contentType = ContentType.Application.Json,
                )
            }
        }
    }

    /**
     * Build a JSON error response string using the provided ObjectMapper.
     */
    private fun buildErrorJson(status: Int, error: String, message: String): String {
        val errorBody =
            mapOf(
                "timestamp" to Instant.now().toString(),
                "status" to status,
                "error" to error,
                "message" to message,
            )
        return objectMapper.writeValueAsString(errorBody)
    }
}
