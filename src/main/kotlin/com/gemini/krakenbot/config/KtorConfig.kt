package com.gemini.krakenbot.config

import com.fasterxml.jackson.databind.SerializationFeature
import com.gemini.krakenbot.util.isAllowAllOriginsEnabled
import com.gemini.krakenbot.util.isLocalOrPrivateOrigin
import io.ktor.http.CacheControl
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.content.CachingOptions
import io.ktor.serialization.jackson.jackson
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.cachingheaders.CachingHeaders
import io.ktor.server.plugins.compression.Compression
import io.ktor.server.plugins.compression.deflate
import io.ktor.server.plugins.compression.gzip
import io.ktor.server.plugins.compression.minimumSize
import io.ktor.server.plugins.conditionalheaders.ConditionalHeaders
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.cors.routing.CORS
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("KtorConfig")

fun Application.configureSerialization() {
    install(ContentNegotiation) {
        jackson {
            findAndRegisterModules()
            disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
        }
    }
}

fun Application.configureCORS() {
    if (isAllowAllOriginsEnabled()) {
        log.warn(
            "REBALANCER_ALLOW_ALL_ORIGINS=true disables ALL dashboard origin checks. " +
                "Any website you visit can call this dashboard's API from your browser. " +
                "Never enable this outside an isolated lab environment, and never with live API keys.",
        )
    }
    install(CORS) {
        // The unauthenticated dashboard assumes local/private-network trust. CORS limits browser
        // cross-origin access accordingly; deployment still controls direct network reachability.
        // `REBALANCER_ALLOWED_ORIGINS` / `REBALANCER_ALLOW_ALL_ORIGINS` extend the private-network
        // predicate without widening `*.local` — see `isLocalOrPrivateOrigin`.
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
