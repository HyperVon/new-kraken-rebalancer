package com.gemini.krakenbot.view.util

import com.gemini.krakenbot.model.TimeRange

fun String.withRange(range: TimeRange): String = withRange(range.key)
fun String.withRange(rangeKey: String): String = withQuery(QueryParamKeys.RANGE, rangeKey)

/** Appends one RFC 3986 query parameter without allowing user values to change URL structure. */
fun String.withQuery(key: String, value: Any): String {
    val fragmentIndex = indexOf('#')
    val base = if (fragmentIndex >= 0) substring(0, fragmentIndex) else this
    val fragment = if (fragmentIndex >= 0) substring(fragmentIndex) else ""
    val separator = if (base.contains("?")) "&" else "?"
    return "$base$separator${key.encodeQueryComponent()}=${value.toString().encodeQueryComponent()}$fragment"
}

private fun String.encodeQueryComponent(): String {
    val hex = "0123456789ABCDEF"
    return encodeToByteArray().joinToString(separator = "") { byte ->
        val unsigned = byte.toInt() and 0xFF
        if (unsigned in 0x30..0x39 ||
            unsigned in 0x41..0x5A ||
            unsigned in 0x61..0x7A ||
            unsigned == '-'.code ||
            unsigned == '.'.code ||
            unsigned == '_'.code ||
            unsigned == '~'.code
        ) {
            unsigned.toChar().toString()
        } else {
            "%${hex[unsigned ushr 4]}${hex[unsigned and 0x0F]}"
        }
    }
}
