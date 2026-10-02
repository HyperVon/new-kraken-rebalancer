package com.gemini.krakenbot.service.impl.history

import com.gemini.krakenbot.model.PortfolioSnapshot
import com.gemini.krakenbot.model.TradeRecord
import com.gemini.krakenbot.repository.TradeRepository
import java.time.Instant

/**
 * Narrow read port for trade and snapshot evidence required during historical price resolution.
 * Allows decoupling price calculation from the mutable, live [TradeRepository].
 */
interface HistoricalPriceEvidenceReader {
    suspend fun getTradesInRange(from: Instant, to: Instant): List<TradeRecord>
    suspend fun getSnapshotsInRange(from: Instant, to: Instant): List<PortfolioSnapshot>
}

/**
 * Live repository adapter delegating reads directly to [TradeRepository].
 */
class LiveTradeRepositoryPriceEvidenceReader(private val repository: TradeRepository) : HistoricalPriceEvidenceReader {
    override suspend fun getTradesInRange(from: Instant, to: Instant): List<TradeRecord> =
        repository.getTradesInRange(from, to)

    override suspend fun getSnapshotsInRange(from: Instant, to: Instant): List<PortfolioSnapshot> =
        repository.getSnapshotsInRange(from, to)
}
