package com.gemini.krakenbot.repository.impl

import com.fasterxml.jackson.databind.ObjectMapper
import com.gemini.krakenbot.model.PortfolioStats
import com.gemini.krakenbot.repository.PortfolioStatsRepository
import com.gemini.krakenbot.util.AtomicJsonFile
import org.slf4j.LoggerFactory
import java.io.File
import java.io.IOException
import java.math.BigDecimal

class PortfolioStatsRepositoryImpl(
    private val objectMapper: ObjectMapper
) : PortfolioStatsRepository {

    private val log =
        LoggerFactory.getLogger(PortfolioStatsRepositoryImpl::class.java)
    private val filePath = "portfolio-stats.json"

    override fun load(): PortfolioStats {
        val file = File(filePath)
        if (!file.exists()) return PortfolioStats(BigDecimal.ZERO)

        return runCatching {
            objectMapper.readValue(file, PortfolioStats::class.java)
        }.getOrElse { e ->
            log.error("Failed to load portfolio stats", e)
            PortfolioStats(BigDecimal.ZERO)
        }
    }

    override fun save(stats: PortfolioStats) {
        try {
            AtomicJsonFile.write(
                objectMapper,
                File(filePath),
                stats
            )
        } catch (e: IOException) {
            log.error("Failed to save portfolio stats", e)
            throw e
        }
    }
}
