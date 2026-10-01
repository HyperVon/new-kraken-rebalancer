package com.gemini.krakenbot.controller

import com.gemini.krakenbot.util.isLocalOrPrivateOrigin
import com.gemini.krakenbot.view.util.FormFields
import io.ktor.http.HttpHeaders
import io.ktor.http.Parameters
import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.origin
import io.ktor.server.response.header
import java.net.IDN
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Double-submit protection for unauthenticated, LAN-accessible mutations. */
internal object CsrfProtection {
    private const val COOKIE_NAME = "rebalancer-csrf"
    private const val TOKEN_BYTES = 32
    private const val TOKEN_SEGMENT_LENGTH = 43
    private const val TOKEN_LENGTH = TOKEN_SEGMENT_LENGTH * 2 + 1
    private const val TOKEN_PATTERN =
        "^[A-Za-z0-9_-]{$TOKEN_SEGMENT_LENGTH}\\.[A-Za-z0-9_-]{$TOKEN_SEGMENT_LENGTH}$"
    private const val LEGACY_TOKEN_PATTERN = "^[A-Za-z0-9_-]{$TOKEN_SEGMENT_LENGTH}$"
    private const val HMAC_ALGORITHM = "HmacSHA256"
    private val secureRandom = SecureRandom()
    private val tokenSecret = ByteArray(TOKEN_BYTES).also(secureRandom::nextBytes)
    private val tokenPattern = Regex(TOKEN_PATTERN)
    private val legacyTokenPattern = Regex(LEGACY_TOKEN_PATTERN)

    fun issueToken(call: ApplicationCall): String {
        val existingToken = call.request.cookies[COOKIE_NAME]
        existingToken?.takeIf(::isAuthenticToken)?.let { return it }

        return rotateToken(call)
    }

    fun rotateToken(call: ApplicationCall): String {
        val nonce = ByteArray(TOKEN_BYTES).also(secureRandom::nextBytes)
        val encoder = Base64.getUrlEncoder().withoutPadding()
        val token = "${encoder.encodeToString(nonce)}.${encoder.encodeToString(sign(nonce))}"
        val isSecure = runCatching { call.request.origin.scheme == "https" }.getOrDefault(false)
        val secureAttr = if (isSecure) "; Secure" else ""
        call.response.header(
            HttpHeaders.SetCookie,
            "$COOKIE_NAME=$token; Path=/; HttpOnly; SameSite=Strict; Max-Age=86400$secureAttr",
        )
        return token
    }

    fun isValid(call: ApplicationCall, parameters: Parameters): Boolean {
        if (!presentOriginHeadersMatch(call)) return false

        // A host-scoped cookie can be set by another service on this machine because cookies do
        // not isolate ports. Only a token authenticated by this process can complete the
        // double-submit check.
        val cookieToken = call.request.cookies[COOKIE_NAME]?.takeIf(::isAuthenticToken) ?: return false
        val formTokens = parameters.getAll(FormFields.CSRF_TOKEN) ?: return false
        if (formTokens.size != 1) return false
        val formToken = formTokens.single()
        return isAuthenticToken(formToken) && MessageDigest.isEqual(
            cookieToken.toByteArray(Charsets.UTF_8),
            formToken.toByteArray(Charsets.UTF_8),
        )
    }

    /**
     * A prior process may have issued the same token format with a different in-memory HMAC key.
     * This recognizes only a matching, well-formed pair as a refresh candidate; it never
     * authorizes a mutation. Requiring an explicit exact same-origin header prevents a stale
     * cookie planted by another local port from triggering a browser refresh.
     */
    fun isRefreshableStaleTokenPair(call: ApplicationCall, parameters: Parameters): Boolean {
        if (!hasExplicitSameOrigin(call)) return false
        val cookieToken = call.request.cookies[COOKIE_NAME] ?: return false
        val formTokens = parameters.getAll(FormFields.CSRF_TOKEN) ?: return false
        val formToken = formTokens.singleOrNull() ?: return false
        if (!MessageDigest.isEqual(
                cookieToken.toByteArray(Charsets.UTF_8),
                formToken.toByteArray(Charsets.UTF_8),
            )
        ) {
            return false
        }
        return isWellFormedToken(cookieToken) && !isAuthenticToken(cookieToken)
    }

    /** True only when a single explicit local/private Origin or Referer exactly matches the request. */
    fun hasExplicitSameOrigin(call: ApplicationCall): Boolean {
        val origins = call.request.headers.getAll(HttpHeaders.Origin)
        if (origins != null) {
            val origin = origins.singleOrNull() ?: return false
            return matchesRequestOrigin(call, origin)
        }

        val referers = call.request.headers.getAll(HttpHeaders.Referrer)
            ?: call.request.headers.getAll("Referer")
            ?: return false
        val referer = referers.singleOrNull() ?: return false
        val origin = extractOrigin(referer) ?: return false
        return matchesRequestOrigin(call, origin)
    }

    fun currentToken(call: ApplicationCall): String = issueToken(call)

    private data class NormalizedOrigin(val scheme: String, val host: String, val port: Int)

    private fun matchesRequestOrigin(call: ApplicationCall, candidate: String): Boolean {
        if (!isLocalOrPrivateOrigin(candidate)) return false
        val requestOrigin = runCatching {
            val request = call.request.origin
            val authority = call.request.headers[HttpHeaders.Host]
            if (authority != null) {
                normalizeRequestAuthority(request.scheme, authority)
            } else {
                normalizeOrigin(request.scheme, request.serverHost, request.serverPort)
            }
        }.getOrNull() ?: return false
        return normalizeOrigin(candidate) == requestOrigin
    }

    private fun normalizeOrigin(value: String): NormalizedOrigin? {
        val uri = runCatching { URI(value) }.getOrNull() ?: return null
        val authority = uri.rawAuthority ?: return null
        if (
            uri.rawUserInfo != null ||
            !uri.rawPath.isNullOrEmpty() ||
            uri.rawQuery != null ||
            uri.rawFragment != null
        ) {
            return null
        }
        if (!hasValidPort(authority, uri.port)) return null
        val scheme = uri.scheme?.lowercase()?.takeIf { it == "http" || it == "https" } ?: return null
        val host = uri.host?.let(::normalizeHost) ?: return null
        val port = effectivePort(scheme, uri.port) ?: return null
        return NormalizedOrigin(scheme, host, port)
    }

    private fun normalizeRequestAuthority(scheme: String, authority: String): NormalizedOrigin? {
        val normalizedScheme = scheme.lowercase().takeIf { it == "http" || it == "https" } ?: return null
        if (authority.isBlank() || authority.any { it.isWhitespace() || it in "@/?#\\," }) return null
        val rawHost: String
        val rawPort: String?
        if (authority.startsWith("[")) {
            val closingBracket = authority.indexOf(']')
            if (closingBracket < 0) return null
            rawHost = authority.substring(1, closingBracket)
            val suffix = authority.substring(closingBracket + 1)
            rawPort = when {
                suffix.isEmpty() -> null
                suffix.startsWith(":") -> suffix.substring(1)
                else -> return null
            }
        } else {
            when (authority.count { it == ':' }) {
                0 -> {
                    rawHost = authority
                    rawPort = null
                }

                1 -> {
                    rawHost = authority.substringBeforeLast(':')
                    rawPort = authority.substringAfterLast(':')
                }

                else -> return null
            }
        }
        if (rawHost.isEmpty()) return null
        val port = if (rawPort == null) {
            effectivePort(normalizedScheme, -1) ?: return null
        } else {
            rawPort.toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
        }
        val host = normalizeHost(rawHost) ?: return null
        return NormalizedOrigin(normalizedScheme, host, port)
    }

    private fun normalizeOrigin(scheme: String, host: String, port: Int): NormalizedOrigin? {
        val normalizedScheme = scheme.lowercase().takeIf { it == "http" || it == "https" } ?: return null
        val normalizedHost = normalizeHost(host) ?: return null
        val effectivePort = effectivePort(normalizedScheme, port) ?: return null
        return NormalizedOrigin(normalizedScheme, normalizedHost, effectivePort)
    }

    private fun hasValidPort(authority: String, parsedPort: Int): Boolean {
        val rawPort = when {
            authority.startsWith("[") -> {
                val closingBracket = authority.indexOf(']')
                if (closingBracket < 0) return false
                val suffix = authority.substring(closingBracket + 1)
                when {
                    suffix.isEmpty() -> return parsedPort == -1
                    suffix.startsWith(":") -> suffix.substring(1)
                    else -> return false
                }
            }

            authority.count { it == ':' } == 0 -> return parsedPort == -1

            authority.count { it == ':' } == 1 -> authority.substringAfterLast(':')

            else -> return false
        }
        val explicitPort = rawPort.toIntOrNull() ?: return false
        return explicitPort in 1..65535 && explicitPort == parsedPort
    }

    private fun effectivePort(scheme: String, port: Int): Int? = when {
        port in 1..65535 -> port
        port == -1 && scheme == "http" -> 80
        port == -1 && scheme == "https" -> 443
        else -> null
    }

    private fun normalizeHost(rawHost: String): String? {
        val host = rawHost.removeSurrounding("[", "]").removeSuffix(".")
        if (host.isBlank() || host.any(Char::isWhitespace)) return null
        if (host.contains(':')) {
            val address = runCatching { InetAddress.getByName(host) }.getOrNull() as? Inet6Address ?: return null
            val bytes = address.address
            return (0 until 8).joinToString(":") { index ->
                val high = bytes[index * 2].toInt() and 0xff
                val low = bytes[index * 2 + 1].toInt() and 0xff
                ((high shl 8) or low).toString(16)
            }
        }
        return runCatching { IDN.toASCII(host, IDN.USE_STD3_ASCII_RULES).lowercase() }
            .getOrNull()
            ?.takeIf(String::isNotEmpty)
    }

    private fun isAuthenticToken(token: String?): Boolean {
        if (token == null || token.length != TOKEN_LENGTH || !tokenPattern.matches(token)) return false
        val separator = token.indexOf('.')
        val decoder = Base64.getUrlDecoder()
        val nonce = runCatching { decoder.decode(token.substring(0, separator)) }.getOrNull() ?: return false
        val signature = runCatching { decoder.decode(token.substring(separator + 1)) }.getOrNull() ?: return false
        if (nonce.size != TOKEN_BYTES || signature.size != TOKEN_BYTES) return false
        return MessageDigest.isEqual(sign(nonce), signature)
    }

    private fun isWellFormedToken(token: String): Boolean {
        if (legacyTokenPattern.matches(token)) {
            return decodeTokenSegment(token) != null
        }
        if (token.length != TOKEN_LENGTH || !tokenPattern.matches(token)) return false
        val separator = token.indexOf('.')
        return decodeTokenSegment(token.substring(0, separator)) != null &&
            decodeTokenSegment(token.substring(separator + 1)) != null
    }

    private fun decodeTokenSegment(segment: String): ByteArray? {
        if (segment.length != TOKEN_SEGMENT_LENGTH) return null
        return runCatching { Base64.getUrlDecoder().decode(segment) }
            .getOrNull()
            ?.takeIf { it.size == TOKEN_BYTES }
    }

    private fun presentOriginHeadersMatch(call: ApplicationCall): Boolean {
        val origins = call.request.headers.getAll(HttpHeaders.Origin)
        if (origins != null) {
            val origin = origins.singleOrNull() ?: return false
            return matchesRequestOrigin(call, origin)
        }
        val referers = call.request.headers.getAll(HttpHeaders.Referrer)
            ?: call.request.headers.getAll("Referer")
            ?: return true
        val referer = referers.singleOrNull() ?: return false
        val origin = extractOrigin(referer) ?: return false
        return matchesRequestOrigin(call, origin)
    }

    private fun sign(nonce: ByteArray): ByteArray = Mac.getInstance(HMAC_ALGORITHM).run {
        init(SecretKeySpec(tokenSecret, HMAC_ALGORITHM))
        doFinal(nonce)
    }

    private fun extractOrigin(referer: String): String? {
        // HTTP stacks may coalesce repeated Referer fields with commas; do not treat that as one URI.
        val comma = referer.indexOf(',')
        if (comma >= 0) {
            val firstUri = runCatching { URI(referer.substring(0, comma).trim()) }.getOrNull()
            val secondUri = runCatching { URI(referer.substring(comma + 1).trim()) }.getOrNull()
            if (
                firstUri?.isAbsolute == true && firstUri.rawAuthority != null &&
                secondUri?.isAbsolute == true && secondUri.rawAuthority != null
            ) {
                return null
            }
        }

        val uri = runCatching { URI(referer) }.getOrNull() ?: return null
        val scheme = uri.scheme ?: return null
        val authority = uri.authority ?: return null
        return "$scheme://$authority"
    }
}
