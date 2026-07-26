package com.gemini.krakenbot.util

/**
 * Dashboard CORS allowlist predicate: localhost/loopback, `*.local`, RFC1918, and link-local
 * (169.254/16). Public origins must remain rejected — the UI has no user auth.
 */
fun isLocalOrPrivateOrigin(origin: String): Boolean {
    val clean = origin.removePrefix("http://").removePrefix("https://").substringBefore(":")
    when {
        clean.equals("localhost", ignoreCase = true) || clean == "127.0.0.1" || clean == "::1" -> {
            return true
        }
        clean.endsWith(".local", ignoreCase = true) -> {
            return true
        }
        clean.startsWith("192.168.") || clean.startsWith("10.") || clean.startsWith("169.254.") -> {
            return true
        }
        clean.startsWith("172.") -> return isPrivateClassB172(clean)
    }
    return false
}

// RFC1918 172.16.0.0/12 only — second octet 16..31; 172.32+ is public address space.
private fun isPrivateClassB172(host: String): Boolean {
    val parts = host.split(".")
    if (parts.size < 2) return false
    val secondOctet = parts[1].toIntOrNull() ?: return false
    return secondOctet in 16..31
}
