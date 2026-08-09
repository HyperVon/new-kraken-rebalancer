package com.gemini.krakenbot.controller

import com.gemini.krakenbot.util.isLocalOrPrivateOrigin
import com.gemini.krakenbot.view.util.FormFields
import io.ktor.http.HttpHeaders
import io.ktor.http.Parameters
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.header
import java.net.URI
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/** Double-submit protection for unauthenticated, LAN-accessible mutations. */
internal object CsrfProtection {
    private const val COOKIE_NAME = "rebalancer-csrf"
    private const val TOKEN_BYTES = 32
    private val secureRandom = SecureRandom()

    fun issueToken(call: ApplicationCall): String {
        val existingToken = call.request.cookies[COOKIE_NAME]
        if (!existingToken.isNullOrBlank()) return existingToken

        return rotateToken(call)
    }

    fun rotateToken(call: ApplicationCall): String {
        val tokenBytes = ByteArray(TOKEN_BYTES)
        secureRandom.nextBytes(tokenBytes)
        val token = Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes)
        call.response.header(
            HttpHeaders.SetCookie,
            "$COOKIE_NAME=$token; Path=/; HttpOnly; SameSite=Strict",
        )
        return token
    }

    fun isValid(call: ApplicationCall, parameters: Parameters): Boolean {
        val originHeader = call.request.headers[HttpHeaders.Origin]
        if (originHeader != null) {
            if (!isLocalOrPrivateOrigin(originHeader)) return false
        } else {
            val refererHeader = call.request.headers[HttpHeaders.Referrer] ?: call.request.headers["Referer"]
            if (refererHeader != null) {
                val refererOrigin = extractOrigin(refererHeader) ?: return false
                if (!isLocalOrPrivateOrigin(refererOrigin)) return false
            }
        }

        val cookieToken = call.request.cookies[COOKIE_NAME] ?: return false
        val formTokens = parameters.getAll(FormFields.CSRF_TOKEN) ?: return false
        if (formTokens.size != 1) return false

        return MessageDigest.isEqual(
            cookieToken.toByteArray(Charsets.UTF_8),
            formTokens.single().toByteArray(Charsets.UTF_8),
        )
    }

    fun currentToken(call: ApplicationCall): String = call.request.cookies[COOKIE_NAME]
        ?.takeIf { it.isNotBlank() }
        ?: issueToken(call)

    private fun extractOrigin(referer: String): String? {
        val uri = runCatching { URI(referer) }.getOrNull() ?: return null
        val scheme = uri.scheme ?: return null
        val authority = uri.authority ?: return null
        return "$scheme://$authority"
    }
}
