package com.gemini.krakenbot.config

object ServerConfig {
    const val DEFAULT_SERVER_PORT = 8080
    const val SERVER_PORT_PROPERTY = "kraken.server.port"

    fun resolveServerPort(rawValue: String? = System.getProperty(SERVER_PORT_PROPERTY)): Int {
        val value = rawValue?.trim()
        if (value.isNullOrEmpty()) return DEFAULT_SERVER_PORT

        val port = value.toIntOrNull()
            ?: throw invalidPort(value)
        if (port !in 1..65535) throw invalidPort(value)
        return port
    }

    private fun invalidPort(value: String) =
        IllegalArgumentException("$SERVER_PORT_PROPERTY must be an integer between 1 and 65535: $value")
}
