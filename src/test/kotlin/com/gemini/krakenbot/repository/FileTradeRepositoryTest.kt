package com.gemini.krakenbot.repository

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.gemini.krakenbot.model.PortfolioSnapshot
import com.gemini.krakenbot.repository.impl.FileTradeRepositoryImpl
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import java.io.File
import java.io.FileWriter
import java.io.IOException
import java.math.BigDecimal
import java.time.Instant

class FileTradeRepositoryTest : StringSpec({

    val TEST_FILE = "test-trade-history.json"
    lateinit var repository: FileTradeRepositoryImpl
    lateinit var objectMapper: ObjectMapper

    beforeTest {
        val f = File(TEST_FILE)
        if (f.exists()) f.delete()
        objectMapper = jacksonObjectMapper()
        objectMapper.findAndRegisterModules()
        repository = FileTradeRepositoryImpl(objectMapper)
        val field = FileTradeRepositoryImpl::class.java.getDeclaredField("filePath")
        field.isAccessible = true
        field.set(repository, TEST_FILE)
    }

    afterTest {
        val f = File(TEST_FILE)
        if (f.exists()) f.delete()
    }

    "testSaveAndLoad" {
        val snapshot = PortfolioSnapshot(
            timestamp = Instant.parse("2023-01-01T10:00:00Z"),
            totalValueUSD = BigDecimal("15000.50"),
            assets = emptyMap(),
            actions = listOf("BUY BTC"),
            drawdownPercent = BigDecimal.ZERO,
            fiatDeploymentPercent = BigDecimal.ZERO,
            effectiveUsdTargetPercent = BigDecimal.ZERO
        )

        repository.save(listOf(snapshot))
        val loaded = repository.load()

        loaded.size shouldBe 1
        loaded[0].totalValueUSD shouldBe BigDecimal("15000.50")
        loaded[0].actions shouldBe snapshot.actions
    }

    "testLoadNonExistentFile" {
        val loaded = repository.load()
        loaded.shouldNotBeNull()
        loaded.isEmpty().shouldBeTrue()
    }

    "testLoadCorruptedFile" {
        val file = File(TEST_FILE)
        FileWriter(file).use { writer ->
            writer.write("{ incomplete json ")
        }

        val loaded = repository.load()
        loaded.shouldNotBeNull()
        loaded.isEmpty().shouldBeTrue()
    }

    "testSaveError" {
        val mockMapper = mockk<ObjectMapper>(relaxed = true)
        every { mockMapper.writeValue(any<File>(), any<Any>()) } throws IOException("Write failed")
        val repo = FileTradeRepositoryImpl(mockMapper)
        shouldThrow<IOException> { repo.save(emptyList()) }
    }
})
