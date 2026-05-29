package com.gemini.krakenbot.util

import com.fasterxml.jackson.databind.ObjectMapper
import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

object AtomicJsonFile {

    fun <T> write(objectMapper: ObjectMapper, target: File, value: T) {
        val parent = target.parentFile ?: File(".")
        if (!parent.exists() && !parent.mkdirs()) {
            throw IOException("Failed to create directory: ${parent.absolutePath}")
        }
        val temp =
            File(parent, "${target.name}.${System.currentTimeMillis()}.tmp")
        try {
            objectMapper.writeValue(temp, value)
            try {
                Files.move(
                    temp.toPath(),
                    target.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(
                    temp.toPath(),
                    target.toPath(),
                    StandardCopyOption.REPLACE_EXISTING
                )
            }
        } finally {
            if (temp.exists()) {
                temp.delete()
            }
        }
    }
}
