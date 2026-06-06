package com.gemini.krakenbot.service.impl

import com.gemini.krakenbot.model.PortfolioSnapshot
import com.gemini.krakenbot.repository.TradeRepository
import com.gemini.krakenbot.service.TradeHistoryService
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.concurrent.CopyOnWriteArrayList

class TradeHistoryServiceImpl(
    private val repository: TradeRepository
) : TradeHistoryService {

    private val history = CopyOnWriteArrayList<PortfolioSnapshot>()
    private val maxHistorySize = 50
    private val snapshotFlow =
        MutableSharedFlow<PortfolioSnapshot>(extraBufferCapacity = 16)

    override fun init() {
        val loaded = repository.load()
        if (loaded.isNotEmpty()) {
            history.addAll(loaded)
        }
    }

    override fun addSnapshot(snapshot: PortfolioSnapshot) {
        history.add(0, snapshot)
        if (history.size > maxHistorySize) {
            history.removeLast()
        }
        repository.save(ArrayList(history))
        snapshotFlow.tryEmit(snapshot)
    }

    override fun getHistory(): List<PortfolioSnapshot> = ArrayList(history)

    override fun getLatestSnapshot(): PortfolioSnapshot? = history.firstOrNull()

    override fun getHistoryFlow(): Flow<PortfolioSnapshot> =
        snapshotFlow.asSharedFlow()
}
