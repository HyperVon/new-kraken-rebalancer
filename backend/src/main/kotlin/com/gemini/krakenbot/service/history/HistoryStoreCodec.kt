package com.gemini.krakenbot.service.history

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import java.util.Base64

internal fun encodeHistoryStrings(values: List<String>): String = values.joinToString("\n") { encodeBase64(it) }

internal fun decodeHistoryStrings(encoded: String): List<String> =
    if (encoded.isEmpty()) emptyList() else encoded.split('\n').map(::decodeBase64)

internal fun encodeHistoryPairs(values: Map<String, String?>): String {
    val bytes = ByteArrayOutputStream()
    DataOutputStream(bytes).use { output ->
        output.writeInt(values.size)
        values.toSortedMap().forEach { (key, value) ->
            writeHistoryString(output, key)
            if (value == null) {
                output.writeInt(-1)
            } else {
                writeHistoryString(output, value)
            }
        }
    }
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes.toByteArray())
}

internal fun decodeHistoryPairs(encoded: String): Map<String, String?> {
    if (encoded.isEmpty()) return emptyMap()
    val bytes = Base64.getUrlDecoder().decode(encoded)
    DataInputStream(ByteArrayInputStream(bytes)).use { input ->
        val count = input.readInt()
        require(count in 0..MAX_ENCODED_HISTORY_PAIRS) { "Invalid encoded History evidence map size." }
        return buildMap {
            repeat(count) {
                val key = readHistoryString(input)
                val valueLength = input.readInt()
                val value = if (valueLength == -1) null else readHistoryString(input, valueLength)
                put(key, value)
            }
            require(input.available() == 0) { "Unexpected trailing bytes in encoded History evidence map." }
        }
    }
}

internal fun historyPayloadHash(values: List<String?>): String {
    val digest = MessageDigest.getInstance("SHA-256")
    values.forEach { value ->
        if (value == null) {
            digest.update(byteArrayOf(0))
        } else {
            val bytes = value.toByteArray(Charsets.UTF_8)
            digest.update(byteArrayOf(1))
            digest.update(
                byteArrayOf(
                    (bytes.size ushr 24).toByte(),
                    (bytes.size ushr 16).toByte(),
                    (bytes.size ushr 8).toByte(),
                    bytes.size.toByte(),
                ),
            )
            digest.update(bytes)
        }
    }
    return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
}

internal fun encodeHistoryAssetPartition(assetClass: String?, assetKey: String): String =
    encodeHistoryPairs(mapOf("class" to assetClass, "asset" to assetKey))

private fun encodeBase64(value: String): String =
    Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray(Charsets.UTF_8))

private fun decodeBase64(value: String): String = String(Base64.getUrlDecoder().decode(value), Charsets.UTF_8)

private fun writeHistoryString(output: DataOutputStream, value: String) {
    val bytes = value.toByteArray(Charsets.UTF_8)
    output.writeInt(bytes.size)
    output.write(bytes)
}

private fun readHistoryString(input: DataInputStream, size: Int = input.readInt()): String {
    require(size in 0..MAX_ENCODED_HISTORY_STRING_BYTES) { "Invalid encoded History string size." }
    val bytes = ByteArray(size)
    input.readFully(bytes)
    return String(bytes, Charsets.UTF_8)
}

private const val MAX_ENCODED_HISTORY_PAIRS = 100_000
private const val MAX_ENCODED_HISTORY_STRING_BYTES = 8_000_000
