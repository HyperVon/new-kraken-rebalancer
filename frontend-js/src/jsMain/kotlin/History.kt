package com.gemini.krakenbot.frontend

fun initHistory() {
    HistoryViewPrefs.initToolbar()
    setupZoomButtons()
    setupChartScrubbers()
    setupSyncProgressAndLoad()
}
