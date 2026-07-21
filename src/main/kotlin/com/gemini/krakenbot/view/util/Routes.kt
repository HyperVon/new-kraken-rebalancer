package com.gemini.krakenbot.view.util

object Routes {
    const val ROOT = "/"
    const val SETTINGS = "/settings"
    const val HISTORY = "/history"
    const val FRAGMENT_DASHBOARD = "/fragments/dashboard"
    const val API_ROUTE_PREFIX = "/api"
    const val API_STATUS_STREAM = "/api/status/stream"
    const val API_HISTORY_SNAPSHOTS = "/api/history/snapshots"
    const val API_HISTORY_TRADES = "/api/history/trades"
    const val API_HISTORY_STATS = "/api/history/stats"
    const val API_HISTORY_SYNC_PROGRESS = "/api/history/sync-progress"
    const val API_HEALTH = "/api/health"
    const val STATIC_STYLE_CSS = "/static/style.css"
    const val STATIC_REBALANCER_JS = "/static/rebalancer.js"
}

object HtmxHeaders {
    const val HX_REDIRECT = "HX-Redirect"
}

object FormFields {
    const val LOOP_DELAY_SECONDS = "loopDelaySeconds"
    const val DEVIATION_TRIGGER_PERCENT = "deviationTriggerPercent"
    const val DUST_THRESHOLD_USD = "dustThresholdUSD"
    const val DRY_RUN = "dryRun"
    const val SIMULATION = "simulation"
    const val FIAT_MAX_DRAWDOWN = "fiatMaxDrawdown"
    const val FIAT_DEPLOYMENT_EXPONENT = "fiatDeploymentExponent"
    const val SYMBOLS = "symbols"
    const val TARGETS = "targets"
}

object SyncMetadataKeys {
    const val SYNC_OFFSET = "sync_offset"
    const val SYNC_TOTAL = "sync_total"
    const val COMPLETED = "completed"
}

object HealthStatusKeys {
    const val STATUS = "status"
    const val STATUS_UP = "UP"
    const val TIMESTAMP = "timestamp"
    const val UPTIME_SECONDS = "uptimeSeconds"
    const val TOTAL_TRADES_EXECUTED = "totalTradesExecuted"
    const val TOTAL_VOLUME_TRADED = "totalVolumeTraded"
    const val LAST_SNAPSHOT_TIME = "lastSnapshotTime"
    const val LAST_SNAPSHOT_TOTAL_VALUE_USD = "lastSnapshotTotalValueUSD"
}
