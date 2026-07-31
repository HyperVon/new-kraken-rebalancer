package com.gemini.krakenbot.view.util

import com.gemini.krakenbot.model.TimeRange

/** HTTP paths shared by Ktor, HTMX, and Kotlin/JS — renaming one side alone breaks the pair silently. */
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
    const val API_HISTORY_COMPARISON = "/api/history/comparison"
    const val API_HEALTH = "/api/health"
    const val STATIC_STYLE_CSS = "/static/style.css"
    const val STATIC_REBALANCER_JS = "/static/rebalancer.js"
    const val STATIC_PREFIX = "/static"
    const val STATIC_RESOURCES_DIR = "static"
}

fun String.withRange(range: TimeRange): String = withRange(range.key)
fun String.withRange(rangeKey: String): String = withQuery(QueryParamKeys.RANGE, rangeKey)
fun String.withQuery(key: String, value: Any): String = "$this?$key=$value"

object QueryParamKeys {
    const val RANGE = "range"
}

object HtmxHeaders {
    const val HX_REDIRECT = "HX-Redirect"
    const val HX_RESWAP = "HX-Reswap"
    const val HX_RETARGET = "HX-Retarget"
}

object FormFields {
    const val CSRF_TOKEN = "csrfToken"
    const val LOOP_DELAY_SECONDS = "loopDelaySeconds"
    const val DEVIATION_TRIGGER_PERCENT = "deviationTriggerPercent"
    const val DUST_THRESHOLD_USD = "dustThresholdUSD"
    const val DRY_RUN = "dryRun"
    const val SIMULATION = "simulation"
    const val FIAT_MAX_DRAWDOWN = "fiatMaxDrawdown"
    const val FIAT_DEPLOYMENT_EXPONENT = "fiatDeploymentExponent"
    const val SYMBOLS = "symbols"
    const val TARGETS = "targets"
    const val COLORS = "colors"
}
