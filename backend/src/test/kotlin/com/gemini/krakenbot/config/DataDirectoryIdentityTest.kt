package com.gemini.krakenbot.config

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.security.MessageDigest

class DataDirectoryIdentityTest : StringSpec() {
    override fun isolationMode() = IsolationMode.InstancePerTest

    init {
        "concurrent different account binds cannot replace the first directory identity" {
            runTest {
                val directory = Files.createTempDirectory("data-directory-identity-")
                try {
                    val identity = DataDirectoryIdentity(directory) { false }
                    identity.bindOrVerify(digest(ACCOUNT_A))
                    val competingBinds = List(8) {
                        async(Dispatchers.IO) {
                            runCatching { DataDirectoryIdentity(directory) { false }.bindOrVerify(digest(ACCOUNT_B)) }
                        }
                    }.awaitAll()

                    competingBinds.any { it.isSuccess } shouldBe false
                    identity.boundAccountIdentity() shouldBe digest(ACCOUNT_A)
                    Files.readString(directory.resolve("account-identity.sha256")) shouldBe "${digest(ACCOUNT_A)}\n"
                } finally {
                    directory.toFile().deleteRecursively()
                }
            }
        }

        "rejects non-digest account identity" {
            val directory = Files.createTempDirectory("data-dir-invalid-digest-")
            try {
                val identity = DataDirectoryIdentity(directory) { false }
                shouldThrow<IllegalArgumentException> {
                    identity.bindOrVerify("not-a-valid-sha256-hex-digest")
                }
            } finally {
                directory.toFile().deleteRecursively()
            }
        }

        "boundAccountIdentity returns null when identity file does not exist" {
            val directory = Files.createTempDirectory("data-dir-no-identity-")
            try {
                val identity = DataDirectoryIdentity(directory) { false }
                identity.boundAccountIdentity() shouldBe null
            } finally {
                directory.toFile().deleteRecursively()
            }
        }

        "boundAccountIdentity throws IllegalStateException when identity file is corrupt" {
            val directory = Files.createTempDirectory("data-dir-corrupt-identity-")
            try {
                Files.writeString(directory.resolve("account-identity.sha256"), "corrupted-content")
                val identity = DataDirectoryIdentity(directory) { false }
                shouldThrow<IllegalStateException> {
                    identity.boundAccountIdentity()
                }
            } finally {
                directory.toFile().deleteRecursively()
            }
        }

        "bindOrVerify succeeds idempotently when called repeatedly with the same digest" {
            val directory = Files.createTempDirectory("data-dir-idempotent-")
            try {
                val identity = DataDirectoryIdentity(directory) { false }
                val d = digest(ACCOUNT_A)
                identity.bindOrVerify(d)
                identity.bindOrVerify(d)
                identity.boundAccountIdentity() shouldBe d
            } finally {
                directory.toFile().deleteRecursively()
            }
        }

        "bindOrVerify throws IllegalStateException when existing identity file has a different digest" {
            val directory = Files.createTempDirectory("data-dir-mismatch-")
            try {
                val identity = DataDirectoryIdentity(directory) { false }
                identity.bindOrVerify(digest(ACCOUNT_A))
                shouldThrow<IllegalStateException> {
                    identity.bindOrVerify(digest(ACCOUNT_B))
                }
            } finally {
                directory.toFile().deleteRecursively()
            }
        }

        "refuses to write an identity marker when account evidence exists without one" {
            val directory = Files.createTempDirectory("data-dir-unmarked-evidence-")
            try {
                val identity = DataDirectoryIdentity(directory) { true }
                shouldThrow<IllegalStateException> {
                    identity.bindOrVerify(digest(ACCOUNT_A))
                }
                Files.exists(directory.resolve("account-identity.sha256")) shouldBe false
            } finally {
                directory.toFile().deleteRecursively()
            }
        }
    }

    private fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString(separator = "") { byte -> "%02x".format(byte) }

    private companion object {
        const val ACCOUNT_A = "authenticated-account-a"
        const val ACCOUNT_B = "authenticated-account-b"
    }
}
