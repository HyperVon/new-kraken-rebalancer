package com.gemini.krakenbot.service.impl

import com.gemini.krakenbot.model.PortfolioSnapshot
import com.gemini.krakenbot.repository.TradeRepository
import com.gemini.krakenbot.service.TradeHistoryService
import java.util.concurrent.CopyOnWriteArrayList

class TradeHistoryServiceImpl(
    private val repository: TradeRepository
) : TradeHistoryService {

    private val history = CopyOnWriteArrayList<PortfolioSnapshot>()
    private val MAX_HISTORY_SIZE = 50

    override fun init() {
        val loaded = repository.load()
        if (loaded.isNotEmpty()) {
            history.addAll(loaded)
        }
    }

    override fun addSnapshot(snapshot: PortfolioSnapshot) {
        history.add(0, snapshot)
        if (history.size > MAX_HISTORY_SIZE) {
            history.removeAt(history.size - 1)
        }
        repository.save(ArrayList(history))
    }

    override fun getHistory(): List<PortfolioSnapshot> {
        return ArrayList(history)
    }

    override fun getLatestSnapshot(): PortfolioSnapshot? {
        return history.firstOrNull()
    }
}
