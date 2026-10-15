package com.gemini.krakenbot.config

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.concurrent.ConcurrentHashMap

/** Pins all account-scoped stores in one active data directory to the same authenticated account. */
class DataDirectoryIdentity(dataDirectory: Path) {
    private val directory = dataDirectory.toAbsolutePath().normalize()
    private val identityFile = directory.resolve(IDENTITY_FILE_NAME)
    private val lock = directoryLocks.computeIfAbsent(identityFile) { Any() }

    fun bindOrVerify(accountIdentityDigest: String) {
        require(isDigest(accountIdentityDigest)) { "Account identity must be a SHA-256 digest." }
        synchronized(lock) {
            try {
                Files.createDirectories(directory)
                val existing = readIdentity()
                if (existing != null) {
                    check(existing == accountIdentityDigest) {
                        "The active data directory is already bound to a different Kraken account."
                    }
                } else {
                    writeIdentity(accountIdentityDigest)
                }
            } catch (e: IOException) {
                throw IllegalStateException("The active data directory account identity could not be verified.", e)
            }
        }
    }

    fun boundAccountIdentity(): String? = synchronized(lock) {
        try {
            readIdentity()
        } catch (e: IOException) {
            throw IllegalStateException("The active data directory account identity could not be read.", e)
        }
    }

    private fun readIdentity(): String? {
        if (!Files.exists(identityFile)) return null
        val value = Files.readString(identityFile).trim()
        check(isDigest(value)) { "The active data directory account identity is corrupt." }
        return value
    }

    private fun writeIdentity(accountIdentityDigest: String) {
        try {
            FileChannel.open(identityFile, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { channel ->
                val content = ByteBuffer.wrap("$accountIdentityDigest\n".toByteArray(Charsets.UTF_8))
                while (content.hasRemaining()) channel.write(content)
                channel.force(true)
            }
        } catch (e: java.nio.file.FileAlreadyExistsException) {
            check(readIdentity() == accountIdentityDigest) {
                "The active data directory is already bound to a different Kraken account."
            }
        }
    }

    private fun isDigest(value: String): Boolean = DIGEST_PATTERN.matches(value)

    private companion object {
        const val IDENTITY_FILE_NAME = "account-identity.sha256"
        val DIGEST_PATTERN = Regex("[0-9a-f]{64}")
        val directoryLocks = ConcurrentHashMap<Path, Any>()
    }
}
