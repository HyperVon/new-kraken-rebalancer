package com.gemini.krakenbot.config

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respondText
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
                        log.error(errorName)
                    } else {
                        log.warn(errorName)
                    }
                    call.respondText(
                        text =
                        buildErrorJson(
                            objectMapper = objectMapper,
                            status = status.value,
                            error = errorName,
                            message = errorMsg,
                        ),
                        status = status,
                        contentType = ContentType.Application.Json,
                    )
                }
            }

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
                        objectMapper = objectMapper,
                        status = status.value,
                        error = status.description,
                        message =
                        if (status.value >= 500) {
                            "An unexpected error occurred."
                        } else {
                            cause.message ?: status.description
                        },
                    ),
                    status = status,
                    contentType = ContentType.Application.Json,
                )
            }
        }
    }

    private fun buildErrorJson(objectMapper: ObjectMapper, status: Int, error: String, message: String): String {
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
