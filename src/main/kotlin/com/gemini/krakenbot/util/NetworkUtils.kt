package com.gemini.krakenbot.util

fun isLocalOrPrivateOrigin(origin: String): Boolean {
    val clean = origin.removePrefix("http://").removePrefix("https://").substringBefore(":")
    if (clean.equals("localhost", ignoreCase = true) || clean == "127.0.0.1" || clean == "::1") {
        return true
    }
    if (clean.endsWith(".local", ignoreCase = true)) {
        return true
    }
    if (clean.startsWith("192.168.") || clean.startsWith("10.") || clean.startsWith("169.254.")) {
        return true
    }
    if (clean.startsWith("172.")) {
        val parts = clean.split(".")
        if (parts.size >= 2) {
            val secondOctet = parts[1].toIntOrNull()
            if (secondOctet != null && secondOctet in 16..31) {
                return true
            }
        }
    }
    return false
}
