package com.gemini.krakenbot.view.util

/** Standard HTML event-handler and custom attribute name constants. */
object HtmlAttrs {
    const val ONCLICK = "onclick"
    const val ONINPUT = "oninput"
    const val ONKEYDOWN = "onkeydown"
    const val DATA_EPOCH = "data-epoch"
    const val DATA_RANGE = "data-range"
    const val DATA_SORT_VALUE = "data-sort-value"
    const val DATA_CHART_ID = "data-chart-id"
    const val DATA_ZOOM_ACTION = "data-zoom-action"
    const val DATASET_SORT_VALUE = "sortValue"
    const val CROSSORIGIN = "crossorigin"
    const val ARIA_LABEL = "aria-label"
}

/** Zoom control action values for History chart toolbar buttons. */
object ZoomActions {
    const val IN = "in"
    const val OUT = "out"
    const val RESET = "reset"
}

/** Standard DOM event name constants. */
object HtmlEvents {
    const val EVENT = "Event"
    const val CLICK = "click"
    const val CHANGE = "change"
    const val INPUT = "input"
    const val DOM_CONTENT_LOADED = "DOMContentLoaded"
    const val HTMX_AFTER_SWAP = "htmx:afterSwap"
}

/** HTMX and SSE attribute name constants. */
object HtmxAttrs {
    const val HX_EXT = "hx-ext"
    const val HX_GET = "hx-get"
    const val HX_POST = "hx-post"
    const val HX_SWAP = "hx-swap"
    const val HX_TARGET = "hx-target"
    const val HX_TRIGGER = "hx-trigger"
    const val SSE_CONNECT = "sse-connect"
}

object HtmxValues {
    const val BODY = "body"
    const val INNER_HTML = "innerHTML"
}

/** Centralized HTML tag name constants used for DOM element creation. */
object HtmlTags {
    const val DIV = "div"
    const val SPAN = "span"
    const val BUTTON = "button"
    const val INPUT = "input"
    const val TR = "tr"
    const val TD = "td"
    const val TH = "th"
    const val TABLE = "table"
    const val THEAD = "thead"
    const val TBODY = "tbody"
    const val OPTION = "option"
}

/** Centralized HTML element IDs used in view layout templates and client scripts. */
object HtmlIds {
    const val SAVE_BUTTON = "save-button"
    const val TOTAL_ALLOCATED_DISPLAY = "total-allocated-display"
    const val ALLOCATIONS_CONTAINER = "allocations-container"
    const val NEW_SYMBOL_INPUT = "new-symbol-input"

    // History Page IDs
    const val HISTORY_STATS = "history-stats"
    const val STAT_ATH_TITLE = "stat-ath-title"
    const val STAT_ATH = "stat-ath"
    const val STAT_TOTAL_TRADES = "stat-total-trades"
    const val STAT_TOTAL_VOLUME = "stat-total-volume"
    const val STAT_TOTAL_FEES = "stat-total-fees"
    const val SHOW_DRY_RUN_CHECKBOX = "show-dry-run-checkbox"
    const val TRADE_TABLE_BODY = "trade-table-body"
    const val SYNC_PROGRESS_BANNER = "sync-progress-banner"
    const val SYNC_PROGRESS_TEXT = "sync-progress-text"
    const val SYNC_PROGRESS_BAR = "sync-progress-bar"
    const val HISTORY_VIEWS_SELECT = "history-views-select"
    const val HISTORY_SAVE_VIEW_BTN = "history-save-view-btn"
    const val HISTORY_SET_DEFAULT_BTN = "history-set-default-btn"
    const val HISTORY_DELETE_VIEW_BTN = "history-delete-view-btn"

    // Chart Canvas IDs
    const val PORTFOLIO_VALUE_CHART = "portfolio-value-chart"
    const val ASSET_HOLDINGS_CHART = "asset-holdings-chart"
    const val ALLOCATION_DRIFT_CHART = "allocation-drift-chart"
    const val CUMULATIVE_PL_CHART = "cumulative-pl-chart"
}

/** Centralized sync metadata keys. */
object SyncMetadataKeys {
    const val SYNC_OFFSET = "sync_offset"
    const val SYNC_TOTAL = "sync_total"
    const val COMPLETED = "completed"
    const val IS_SEEDED = "seeded"
    const val OFFSET = "offset"
    const val TOTAL = "total"
}

/** Centralized health check response keys. */
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
