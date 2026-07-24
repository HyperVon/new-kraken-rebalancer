package com.gemini.krakenbot.config

import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldNotBe
import java.io.File

@Suppress("unused")
class DatabaseConfigTest : StringSpec() {
    override fun isolationMode() = IsolationMode.InstancePerTest

    init {
        "should initialize database and create file" {
            val dbFile = File("test-config.db")
            if (dbFile.exists()) dbFile.delete()

            try {
                val db = DatabaseConfig.init(dbFile.name)
                db shouldNotBe null
                dbFile.exists() shouldNotBe false
            } finally {
                if (dbFile.exists()) dbFile.delete()
            }
        }

        "should initialize in-memory database" {
            val db = DatabaseConfig.init(":memory:")
            db shouldNotBe null
        }
    }
}
