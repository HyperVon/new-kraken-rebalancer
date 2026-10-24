package com.gemini.krakenbot.config

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.response.respondText
import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory
import java.time.Instant

object ErrorHandlingConfig {
    private val log = LoggerFactory.getLogger(ErrorHandlingConfig::class.java)

    fun Application.configureErrorHandling() {
        configureErrorHandling(jacksonObjectMapper())
    }

    fun Application.configureErrorHandling(objectMapper: ObjectMapper) {
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
                        log.error("{}: {} {}", errorName, call.request.httpMethod.value, call.request.path())
                    } else {
                        log.warn("{}: {} {}", errorName, call.request.httpMethod.value, call.request.path())
                    }
                    call.respondError(objectMapper, status, errorName, errorMsg)
                }
            }

            // Cancellation (client disconnect, shutdown) is not an error: rethrow so
            // it is never logged as unhandled and no response is attempted.
            exception<CancellationException> { _, cause -> throw cause }

            exception<Exception> { call, cause ->
                log.error("Unhandled exception: {}", cause.message, cause)
                val status =
                    when (cause) {
                        is IllegalArgumentException -> HttpStatusCode.BadRequest
                        else -> HttpStatusCode.InternalServerError
                    }

                call.respondError(
                    objectMapper = objectMapper,
                    status = status,
                    error = status.description,
                    message = if (status.value >= 500) {
                        "An unexpected error occurred."
                    } else {
                        cause.message ?: status.description
                    },
                )
            }
        }
    }

    private suspend fun ApplicationCall.respondError(
        objectMapper: ObjectMapper,
        status: HttpStatusCode,
        error: String,
        message: String,
    ) {
        val errorBody =
            mapOf(
                "timestamp" to Instant.now().toString(),
                "status" to status.value,
                "error" to error,
                "message" to message,
            )
        respondText(objectMapper.writeValueAsString(errorBody), ContentType.Application.Json, status)
    }
}
