package com.gemini.krakenbot.config

import com.fasterxml.jackson.databind.SerializationFeature
import com.gemini.krakenbot.util.isLocalOrPrivateOrigin
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.serialization.jackson.*
import io.ktor.server.application.*
import io.ktor.server.plugins.cachingheaders.*
import io.ktor.server.plugins.compression.*
import io.ktor.server.plugins.conditionalheaders.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.cors.routing.*

fun Application.configureSerialization() {
    install(ContentNegotiation) {
        jackson {
            findAndRegisterModules()
            disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
        }
    }
}

fun Application.configureCORS() {
    install(CORS) {
        allowOrigins { origin ->
            isLocalOrPrivateOrigin(origin)
        }
        allowMethod(HttpMethod.Options)
        allowMethod(HttpMethod.Put)
        allowMethod(HttpMethod.Delete)
        allowMethod(HttpMethod.Patch)
        allowHeader(HttpHeaders.Authorization)
        allowHeader(HttpHeaders.ContentType)
    }
}

fun Application.configureCompression() {
    install(Compression) {
        gzip {
            priority = 1.0
        }
        deflate {
            priority = 0.9
            minimumSize(1024)
        }
    }
}

fun Application.configureCachingAndConditionalHeaders() {
    install(ConditionalHeaders)
    install(CachingHeaders) {
        options { _, outgoingContent ->
            when (outgoingContent.contentType?.withoutParameters()) {
                ContentType.Text.CSS -> CachingOptions(CacheControl.MaxAge(maxAgeSeconds = 24 * 3600))
                else -> null
            }
        }
    }
}
