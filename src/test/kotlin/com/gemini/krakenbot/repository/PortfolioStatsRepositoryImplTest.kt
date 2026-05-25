package com.gemini.krakenbot.repository

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.gemini.krakenbot.model.PortfolioStats
import com.gemini.krakenbot.repository.impl.PortfolioStatsRepositoryImpl
import io.mockk.every
import io.mockk.mockk
import java.io.File
import java.io.IOException
import java.math.BigDecimal
import java.nio.file.Files
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.assertions.throwables.shouldNotThrowAny

class PortfolioStatsRepositoryImplTest : StringSpec({

    val TEST_FILE = "test-portfolio-stats.json"
    lateinit var repository: PortfolioStatsRepositoryImpl
    lateinit var objectMapper: ObjectMapper

    beforeTest {
        val f = File(TEST_FILE)
        if (f.exists()) f.delete()
        objectMapper = jacksonObjectMapper()
        repository = PortfolioStatsRepositoryImpl(objectMapper)
        val field = PortfolioStatsRepositoryImpl::class.java.getDeclaredField("filePath")
        field.isAccessible = true
        field.set(repository, TEST_FILE)
    }

    afterTest {
        val f = File(TEST_FILE)
        if (f.exists()) f.delete()
    }

    "load_NonExistentFile_ReturnsZeroStats" {
        val stats = repository.load()
        stats.shouldNotBeNull()
        stats.allTimeHigh shouldBe BigDecimal.ZERO
    }

    "load_Success" {
        val stats = PortfolioStats(BigDecimal("1000.50"))
        repository.save(stats)

        val loaded = repository.load()
        loaded.shouldNotBeNull()
        loaded.allTimeHigh shouldBe BigDecimal("1000.50")
    }

    "load_HandlesIOException" {
        val file = File(TEST_FILE)
        Files.writeString(file.toPath(), "{invalid json}")

        val stats = repository.load()
        stats.shouldNotBeNull()
        stats.allTimeHigh shouldBe BigDecimal.ZERO
    }

    "save_HandlesIOException" {
        val mockMapper = mockk<ObjectMapper>(relaxed = true)
        every { mockMapper.writeValue(any<File>(), any<Any>()) } throws IOException("simulated error")

        val errRepository = PortfolioStatsRepositoryImpl(mockMapper)
        val stats = PortfolioStats(BigDecimal.TEN)

        shouldNotThrowAny { errRepository.save(stats) }
    }
})
