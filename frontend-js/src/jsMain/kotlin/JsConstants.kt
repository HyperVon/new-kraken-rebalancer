package com.gemini.krakenbot.frontend

/** Type-safe DOM element IDs used across Kotlin/JS frontend components. */
object JsElementId {
    const val SAVE_BUTTON = "save-button"
    const val TOTAL_ALLOCATED_DISPLAY = "total-allocated-display"
    const val ALLOCATIONS_CONTAINER = "allocations-container"
    const val NEW_SYMBOL_INPUT = "new-symbol-input"
    const val SHOW_DRY_RUN_CHECKBOX = "show-dry-run-checkbox"
    const val SYNC_PROGRESS_BANNER = "sync-progress-banner"
    const val SYNC_PROGRESS_BAR = "sync-progress-bar"
    const val SYNC_PROGRESS_TEXT = "sync-progress-text"
    const val TRADE_TABLE_BODY = "trade-table-body"
    const val STAT_ATH_TITLE = "stat-ath-title"
    const val STAT_ATH = "stat-ath"
    const val STAT_TOTAL_TRADES = "stat-total-trades"
    const val STAT_TOTAL_VOLUME = "stat-total-volume"
    const val STAT_TOTAL_FEES = "stat-total-fees"

    const val PORTFOLIO_VALUE_CHART = "portfolio-value-chart"
    const val ASSET_HOLDINGS_CHART = "asset-holdings-chart"
    const val ALLOCATION_DRIFT_CHART = "allocation-drift-chart"
    const val CUMULATIVE_PL_CHART = "cumulative-pl-chart"
}

/** Type-safe CSS class names used across Kotlin/JS DOM manipulation. */
object JsCssClass {
    const val LIVE = "live"
    const val DELAYED = "delayed"
    const val STALE = "stale"
    const val ACTIVE = "active"
    const val BADGE_BUY = "badge badge-buy"
    const val BADGE_SELL = "badge badge-sell"
    const val BADGE_INFO = "badge badge-info"
    const val ALLOCATION_EDIT_ROW = "allocation-edit-row"
}

/** Centralized CSS query selectors used in document element searches. */
object JsQuerySelector {
    const val TARGET_INPUTS = "input[name=\"targets\"]"
    const val SYMBOL_INPUTS = "input[name=\"symbols\"]"
    const val TIME_RANGE_BTNS = ".time-range-btn"
    const val DATA_AGE_VALUE = ".data-age-value"
    const val DATA_AGE_TIME = ".data-age-time"
    const val STATUS_BADGE = ".status-badge"
    const val SORTABLE_TH = "th.sortable"
    const val HOVERABLE_TR = "tr.hoverable"
}

/** Custom HTML dataset attribute names. */
object JsDatasetKey {
    const val EPOCH = "data-epoch"
    const val RANGE = "data-range"
    const val SORT_VALUE = "sortValue"
}

/** Standard time range option strings. */
object JsTimeRange {
    const val THIRTY_DAYS = "30d"
    const val ALL = "all"
}
