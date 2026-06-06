package com.gemini.krakenbot.repository

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.gemini.krakenbot.model.PortfolioStats
import com.gemini.krakenbot.repository.impl.PortfolioStatsRepositoryImpl
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import java.io.File
import java.io.IOException
import java.math.BigDecimal
import java.nio.file.Files

@Suppress("unused")
class PortfolioStatsRepositoryImplTest : StringSpec() {

    private val testFileName = "test-portfolio-stats.json"
    private lateinit var repository: PortfolioStatsRepositoryImpl
    private lateinit var objectMapper: ObjectMapper

    init {
        beforeTest {
            val f = File(testFileName)
            if (f.exists()) f.delete()
            objectMapper = jacksonObjectMapper()
            repository = PortfolioStatsRepositoryImpl(objectMapper)
            val field =
                PortfolioStatsRepositoryImpl::class.java.getDeclaredField("filePath")
            field.isAccessible = true
            field.set(repository, testFileName)
        }

        afterTest {
            val f = File(testFileName)
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
            val file = File(testFileName)
            Files.writeString(file.toPath(), "{invalid json}")

            val stats = repository.load()
            stats.shouldNotBeNull()
            stats.allTimeHigh shouldBe BigDecimal.ZERO
        }

        "save_HandlesIOException" {
            val mockMapper = mockk<ObjectMapper>(relaxed = true)
            every {
                mockMapper.writeValue(
                    any<File>(),
                    any<Any>()
                )
            } throws IOException("simulated error")

            val errRepository = PortfolioStatsRepositoryImpl(mockMapper)
            val stats = PortfolioStats(BigDecimal.TEN)

            shouldThrow<IOException> { errRepository.save(stats) }
        }
    }
}
