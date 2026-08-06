package com.gemini.krakenbot.util

import java.net.URI

/**
 * Dashboard CORS allowlist predicate: localhost/loopback, `*.local`, RFC1918, and link-local
 * (169.254/16). Public origins must remain rejected — the UI has no user auth.
 */
fun isLocalOrPrivateOrigin(origin: String): Boolean {
    val uri = parseOrigin(origin) ?: return false
    val host = uri.host?.removeSurrounding("[", "]") ?: return false

    if (host.equals("localhost", ignoreCase = true)) return true
    if (host.contains(':')) return isIpv6Loopback(host)

    parseIpv4(host)?.let { octets ->
        return octets[0] == IPV4_LOOPBACK_PREFIX ||
            octets[0] == 10 ||
            (octets[0] == 192 && octets[1] == 168) ||
            (octets[0] == 169 && octets[1] == 254) ||
            (octets[0] == 172 && octets[1] in 16..31)
    }

    return host.endsWith(".local", ignoreCase = true) && isValidLocalHostname(host)
}

private fun parseOrigin(origin: String): URI? {
    val trimmed = origin.trim()
    if (trimmed.isEmpty()) return null
    val candidate = if (SCHEME_PATTERN.containsMatchIn(trimmed)) trimmed else "http://$trimmed"
    val uri = runCatching { URI(candidate) }.getOrNull() ?: return null
    if (uri.scheme?.lowercase() !in ALLOWED_SCHEMES || uri.rawUserInfo != null) return null
    if (!uri.rawPath.isNullOrEmpty() || uri.rawQuery != null || uri.rawFragment != null) return null
    return uri
}

private fun parseIpv4(host: String): List<Int>? {
    if (!IPV4_SHAPE.matches(host)) return null
    val octets = host.split('.').map { it.toIntOrNull() ?: return null }
    return octets.takeIf { values -> values.all { it in 0..255 } }
}

private fun isIpv6Loopback(host: String): Boolean {
    if (!IPV6_CHARS.matches(host)) return false
    return normalizeIpv6(host) == IPV6_LOOPBACK_EXPANDED
}

private fun normalizeIpv6(host: String): String {
    val left: List<String>
    val right: List<String>
    val parts = host.split("::")
    if (parts.size == 1) {
        left = parts[0].split(':').filter { it.isNotEmpty() }
        right = emptyList()
    } else {
        left = parts[0].split(':').filter { it.isNotEmpty() }
        right = parts.getOrNull(1)?.split(':')?.filter { it.isNotEmpty() } ?: emptyList()
    }
    val missing = 8 - left.size - right.size
    val middle = if (missing > 0) List(missing) { "0" } else emptyList()
    return (left + middle + right).joinToString(":") { normalizeIpv6Group(it) }
}

private fun normalizeIpv6Group(group: String): String = group.toInt(radix = 16).toString()

private fun isValidLocalHostname(host: String): Boolean =
    host.length <= MAX_HOST_LENGTH && host.split('.').all { label -> DNS_LABEL.matches(label) }

private const val IPV4_LOOPBACK_PREFIX = 127
private const val MAX_HOST_LENGTH = 253
private val ALLOWED_SCHEMES = setOf("http", "https")
private val SCHEME_PATTERN = Regex("^[A-Za-z][A-Za-z0-9+.-]*://")
private val IPV4_SHAPE = Regex("^[0-9]+(?:\\.[0-9]+){3}$")
private val IPV6_CHARS = Regex("^[0-9a-fA-F:]+$")
private val DNS_LABEL = Regex("^[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?$")
private val IPV6_LOOPBACK_EXPANDED = "0:0:0:0:0:0:0:1"
