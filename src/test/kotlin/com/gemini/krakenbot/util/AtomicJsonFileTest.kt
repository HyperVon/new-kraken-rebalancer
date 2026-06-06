package com.gemini.krakenbot.util

import com.fasterxml.jackson.databind.ObjectMapper
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

@Suppress("unused")
class AtomicJsonFileTest : StringSpec() {
    private val objectMapper = ObjectMapper()

    init {
        "should write json file successfully" {
            val target = File("build/test-atomic/success.json")
            target.parentFile?.mkdirs()
            target.delete()

            AtomicJsonFile.write(
                objectMapper,
                target,
                mapOf("hello" to "world")
            )
            target.exists() shouldBe true
            objectMapper.readValue(
                target,
                Map::class.java
            )["hello"] shouldBe "world"
            target.delete()
        }

        "should write json file successfully with no parent directory" {
            val target = File("success-no-parent.json")
            target.delete()

            AtomicJsonFile.write(
                objectMapper,
                target,
                mapOf("hello" to "world")
            )
            target.exists() shouldBe true
            objectMapper.readValue(
                target,
                Map::class.java
            )["hello"] shouldBe "world"
            target.delete()
        }

        "should create parent directory if it does not exist" {
            val parent = File("build/test-atomic/nested-dir")
            if (parent.exists()) parent.deleteRecursively()

            val target = File(parent, "nested.json")
            AtomicJsonFile.write(objectMapper, target, mapOf("a" to 1))
            target.exists() shouldBe true
            parent.exists() shouldBe true
            parent.deleteRecursively()
        }

        "should throw IOException if parent directory cannot be created" {
            val notADirFile = File("build/test-atomic/not-a-dir-file")
            notADirFile.parentFile?.mkdirs()
            notADirFile.delete()
            notADirFile.createNewFile() // makes it a regular file

            val parent = File(
                notADirFile,
                "nested-subdir"
            ) // parent does not exist, and cannot be created because its parent is a file
            val target = File(parent, "nested.json")
            shouldThrow<IOException> {
                AtomicJsonFile.write(objectMapper, target, mapOf("a" to 1))
            }
            notADirFile.delete()
        }

        "should fallback to non-atomic move if atomic move is not supported" {
            mockkStatic(Files::class)
            val target = File("build/test-atomic/atomic-fallback.json")
            target.parentFile?.mkdirs()
            target.delete()

            every {
                Files.move(
                    any(),
                    any(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE
                )
            } throws AtomicMoveNotSupportedException(
                "source",
                "target",
                "Atomic move not supported"
            )

            every {
                Files.move(
                    any(),
                    any(),
                    StandardCopyOption.REPLACE_EXISTING
                )
            } answers {
                val src = firstArg<Path>()
                val dst = secondArg<Path>()
                src.toFile().copyTo(dst.toFile(), overwrite = true)
                src.toFile().delete()
                dst
            }

            AtomicJsonFile.write(
                objectMapper,
                target,
                mapOf("fallback" to true)
            )
            target.exists() shouldBe true
            objectMapper.readValue(
                target,
                Map::class.java
            )["fallback"] shouldBe true

            unmockkStatic(Files::class)
            target.delete()
        }

        "should delete temp file if write fails" {
            mockkStatic(Files::class)
            val target = File("build/test-atomic/fail-cleanup.json")
            target.parentFile?.mkdirs()
            target.delete()

            every {
                Files.move(
                    any(),
                    any(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE
                )
            } throws IOException("Move failed")

            shouldThrow<IOException> {
                AtomicJsonFile.write(
                    objectMapper,
                    target,
                    mapOf("should" to "fail")
                )
            }

            val files = target.parentFile?.listFiles() ?: emptyArray()
            files.any {
                it.name.startsWith("fail-cleanup.json") && it.name.endsWith(
                    ".tmp"
                )
            } shouldBe false

            unmockkStatic(Files::class)
            target.delete()
        }
    }
}
