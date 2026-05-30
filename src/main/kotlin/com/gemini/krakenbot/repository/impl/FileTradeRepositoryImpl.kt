package com.gemini.krakenbot.repository.impl

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import com.gemini.krakenbot.model.PortfolioSnapshot
import com.gemini.krakenbot.repository.TradeRepository
import com.gemini.krakenbot.util.AtomicJsonFile
import org.slf4j.LoggerFactory
import java.io.File
import java.io.IOException

class FileTradeRepositoryImpl(
    private val objectMapper: ObjectMapper
) : TradeRepository {

    private val log =
        LoggerFactory.getLogger(FileTradeRepositoryImpl::class.java)
    private val filePath = "trade-history.json"

    override fun save(history: List<PortfolioSnapshot>) {
        try {
            AtomicJsonFile.write(
                objectMapper,
                File(filePath),
                history
            )
        } catch (e: IOException) {
            log.error("Failed to save trade history to {}", filePath, e)
            throw e
        }
    }

    override fun load(): List<PortfolioSnapshot> {
        val file = File(filePath)
        if (!file.exists()) {
            return emptyList()
        }
        return try {
            objectMapper.readValue(
                file,
                object : TypeReference<List<PortfolioSnapshot>>() {})
        } catch (e: Exception) {
            log.error(
                "Failed to load trade history from {}. Starting with empty history.",
                filePath,
                e
            )
            emptyList()
        }
    }
}
