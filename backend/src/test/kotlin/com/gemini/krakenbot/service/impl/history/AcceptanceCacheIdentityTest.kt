package com.gemini.krakenbot.service.impl.history

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.io.File

/**
 * Build-level guard for the acceptance-test cache identity.
 *
 * Acceptance and forensic replays read a production-derived database supplied through
 * `ACCEPTANCE_DB_PATH`. Gradle does not track environment variables as task inputs, so when that path
 * was undeclared `:backend:test` returned `FROM-CACHE` for a database it had never seen, and a result
 * computed against one database was presented as a fresh replay of another.
 *
 * This pins the declaration in the build script itself: if the inputs are removed or reduced to the
 * path alone, the regression guard fails rather than silently returning to silent cache reuse.
 */
class AcceptanceCacheIdentityTest : StringSpec() {

    private val buildScript: File by lazy {
        generateSequence(File("").absoluteFile) { it.parentFile }
            .map { File(it, "build.gradle.kts") }
            .firstOrNull { it.isFile }
            ?: error("backend/build.gradle.kts not found from ${File("").absolutePath}")
    }

    private val script: String by lazy { buildScript.readText() }

    init {
        "the backend test task declares the acceptance database path as an input" {
            buildScript.name shouldBe "build.gradle.kts"
            script shouldContain "inputs.property(\"acceptanceDbPath\""
        }

        "the backend test task declares a content fingerprint, not just the path" {
            // A path alone still collides whenever one database is replaced in place at the same
            // path, which is the common case for a disposable acceptance copy.
            script shouldContain "inputs.property(\"acceptanceDbFingerprint\""
            script shouldContain "MessageDigest.getInstance("
        }

        "the acceptance database identity is read from the environment, not a build-time constant" {
            script shouldContain "environmentVariable(\"ACCEPTANCE_DB_PATH\")"
        }
    }
}
