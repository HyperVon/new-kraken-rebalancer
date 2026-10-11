package com.gemini.krakenbot.service

import com.gemini.krakenbot.model.PortfolioSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** In-memory view of the latest completed cycle; this intentionally retains no history. */
class CurrentPortfolioState {
    private val mutableSnapshot = MutableStateFlow<PortfolioSnapshot?>(null)

    val snapshot: StateFlow<PortfolioSnapshot?> = mutableSnapshot.asStateFlow()

    fun publish(snapshot: PortfolioSnapshot) {
        mutableSnapshot.value = snapshot
    }
}
