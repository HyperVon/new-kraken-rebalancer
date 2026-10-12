package com.gemini.krakenbot.service

import com.gemini.krakenbot.model.PortfolioSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Bounded in-memory view of the latest completed cycles; it never reads persisted history. */
class CurrentPortfolioState {
    private val mutableSnapshot = MutableStateFlow<PortfolioSnapshot?>(null)
    private val lock = Any()
    private val recent = ArrayDeque<PortfolioSnapshot>()
    private var sourceScope: String? = null

    val snapshot: StateFlow<PortfolioSnapshot?> = mutableSnapshot.asStateFlow()

    fun publish(snapshot: PortfolioSnapshot, sourceScope: String) {
        synchronized(lock) {
            if (this.sourceScope != sourceScope) {
                recent.clear()
                this.sourceScope = sourceScope
            }
            recent.addLast(snapshot)
            while (recent.size > MAX_RECENT_SNAPSHOTS) recent.removeFirst()
            mutableSnapshot.value = snapshot
        }
    }

    /** Returns one consistent latest-and-trend view for the dashboard, scoped to this process. */
    fun readDashboardView(): CurrentPortfolioDashboardView = synchronized(lock) {
        CurrentPortfolioDashboardView(
            latest = mutableSnapshot.value,
            recentSnapshots = recent.toList(),
        )
    }

    companion object {
        const val MAX_RECENT_SNAPSHOTS = 24
    }
}

data class CurrentPortfolioDashboardView(val latest: PortfolioSnapshot?, val recentSnapshots: List<PortfolioSnapshot>)
