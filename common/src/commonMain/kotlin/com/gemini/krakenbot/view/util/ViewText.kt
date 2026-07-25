package com.gemini.krakenbot.view.util

object ViewText {
    const val APP_TITLE = "Kraken Rebalancer"
    const val APP_BRAND_PRIMARY = "Kraken"
    const val APP_BRAND_ACCENT = "Rebalancer"
    const val SETTINGS_TITLE = "Settings"
    const val PORTFOLIO_ALLOCATION = "Portfolio Allocation (Top Assets)"
    const val ASSET_PERFORMANCE = "Asset Performance"
    const val TOTAL_PORTFOLIO = "Total Portfolio"
    const val CASH_USD = "Cash (USD)"
    const val CRYPTO_ASSETS = "Crypto Assets"

    // DASH-1: hero delta window label.
    const val DELTA_WINDOW_24H = "24H"
    const val RECENT_ACTIVITY = "Recent Activity"
    const val NO_TRADING_HISTORY = "No trading history available."
    const val NO_TRADES_EXECUTED = "No trades executed (Cycle complete)"

    // DASH-3: activity feed labels.
    const val ACTIVITY_VIEW_ALL = "View all history"
    const val ACTIVITY_CYCLE_PREFIX = "Cycle"
    const val ACTIVITY_NO_TRADES = "No trades — portfolio within tolerance"
    const val ACTIVITY_ACTIONS_SUFFIX = " actions"
    const val ACTIVITY_ACTION_SUFFIX = " action"
    const val CONNECTING = "Connecting to KrakenRebalancer..."
    const val WAITING_FIRST_CYCLE = "Waiting for first rebalance cycle"
    const val REBALANCER_RUNNING =
        "The rebalancer is running. Portfolio data will appear here after the first cycle completes."
    const val GLOBAL_PARAMETERS = "Global Parameters"
    const val SAFETY_MODES = "Safety Modes"
    const val LOOP_INTERVAL = "Loop Interval (Seconds)"
    const val DEVIATION_TRIGGER = "Deviation Trigger (%)"
    const val DUST_THRESHOLD = "Dust Threshold ($)"
    const val FIAT_MAX_DRAWDOWN = "Fiat Max Drawdown (%)"
    const val FIAT_DEPLOYMENT_EXPONENT = "Fiat Deployment Exponent"

    // SETT-1: safety toggle card copy (consequence prose + state pills).
    const val DRY_RUN_MODE_TITLE = "Dry Run Mode"
    const val SIMULATION_MODE_TITLE = "Simulation Mode"
    const val DRY_RUN_MODE_DESC =
        "Validates conditions and builds real Kraken orders, but never submits them. A safe way to verify config before going live."
    const val SIMULATION_MODE_DESC =
        "Runs the whole strategy against an offline Kraken emulator. No real funds are ever touched — ideal for testing."
    const val SAFETY_ON = "ON"
    const val SAFETY_OFF = "OFF"
    const val SAFETY_MODES_SUBTITLE =
        "Control how the rebalancer executes orders. Keep at least one safety on unless you intend to trade live."
    const val TARGET_ALLOCATIONS = "Target Allocations"
    const val LEGEND_OVER_TARGET = "Over target"
    const val LEGEND_UNDER_TARGET = "Under target"
    const val ADD_ASSET = "Add Asset"
    const val SAVE_CONFIGURATION = "Save Configuration"

    // GLOB-1: stream-health chip is renamed so it never reads as "live trading".
    const val STREAM = "STREAM"
    const val STREAM_STALE = "STALE"

    // GLOB-1/DASH-2: persistent trading-mode plate labels + descriptions.
    const val MODE_SIMULATION = "SIMULATION"
    const val MODE_DRY_RUN = "DRY RUN"
    const val MODE_LIVE = "LIVE TRADING"
    const val MODE_SIMULATION_TITLE = "Simulation mode — no real orders are placed"
    const val MODE_DRY_RUN_TITLE = "Dry run — real orders are validated but not submitted"
    const val MODE_LIVE_TITLE = "Live trading — real orders execute with real funds"
    const val NO_USD_DATA = "No USD Data"
    const val TARGET_PREFIX = "Target: "
    const val BASE_PREFIX = "Base: "
    const val DEV_PREFIX = "Dev: "
    const val ASSETS_SUFFIX = " Assets"
    const val REMOVE = "Remove"
    const val NEW_SYMBOL_PLACEHOLDER = "New Symbol (e.g. DOT)"
    const val TOTAL_INITIAL = "Total: 0.00%"
    const val TOTAL_PREFIX = "Total: "
    const val PLACEHOLDER_DASHES = "--"
    const val PRICE_NOT_FOUND_PREFIX = "Price not found for "
    const val DRY_RUN_PREFIX = "[DRY RUN] "

    // Table Headers
    const val HEADER_ASSET = "Asset"
    const val HEADER_PRICE = "Price"
    const val HEADER_VALUE = "Value"
    const val HEADER_TARGET_PCT = "Target %"
    const val HEADER_CURRENT_PCT = "Current %"
    const val HEADER_DEV_PCT = "Dev %"
    const val HEADER_TIME = "Time"
    const val HEADER_ACTION = "Action"

    // History page
    const val HISTORY_TITLE = "History"
    const val HISTORY_PORTFOLIO_VALUE = "Portfolio Value Over Time"
    const val HISTORY_ASSET_HOLDINGS = "Asset Holdings Over Time"
    const val HISTORY_ALLOCATION_DRIFT = "Allocation Deviation from Target"
    const val HISTORY_VS_TARGET = "vs target"
    const val HISTORY_NET_CASH_FLOW = "Cumulative Net Cash Flow"
    const val HISTORY_TRADE_LOG = "Trade History"
    const val HISTORY_ALL_TIME_HIGH = "All-Time High"
    const val HISTORY_TOTAL_TRADES = "Total Trades"
    const val HISTORY_TOTAL_VOLUME = "Total Volume Traded"
    const val HISTORY_TOTAL_FEES = "Total Fees Paid"
    const val HISTORY_AVG_FEE_RATE = "Avg Fee Rate"
    const val HISTORY_AVG_SLIPPAGE = "Avg Slippage"
    const val HISTORY_NO_DATA = "No historical data available yet. Data will appear after the first rebalance cycle."
    const val SHOW_DRY_RUN_TRADES = "Show Dry Run Trades"
    const val SYNCHRONIZING_TRADE_HISTORY = "Synchronizing Kraken Trade History..."
    const val INITIAL_SYNC_PROGRESS = "0 / 0 (0%)"
    const val LABEL_ALL = "All"

    // History views & zoom
    const val HISTORY_VIEWS = "Views"
    const val HISTORY_SAVE_VIEW = "Save view…"
    const val HISTORY_SET_DEFAULT = "Set as default"
    const val HISTORY_DELETE_VIEW = "Delete"
    const val HISTORY_SAVE_VIEW_PROMPT = "Name this view:"
    const val HISTORY_ZOOM_IN = "Zoom +"
    const val HISTORY_ZOOM_OUT = "Zoom −"
    const val HISTORY_ZOOM_RESET = "Reset"
    const val HISTORY_PAN_CHART = "Pan zoomed chart"
    const val HISTORY_VIEW_OVERVIEW = "Overview"
    const val HISTORY_VIEW_DAY_TOTAL = "Day · Total only"
    const val HISTORY_VIEW_WEEK_ALLOCATION = "Week · Allocation"
    const val HISTORY_VIEW_MONTH_NET_CASH_FLOW = "Month · Net Cash Flow"
    const val HISTORY_VIEW_UNSAVED = "Custom (unsaved)"
    const val HISTORY_VIEWS_STORAGE_KEY = "kraken.history.views"

    // Navigation
    const val NAV_DASHBOARD = "Dashboard"
    const val NAV_HISTORY = "History"
    const val NAV_SETTINGS = "Settings"

    // History trade table headers
    const val HEADER_PAIR = "Pair"
    const val HEADER_SIDE = "Side"
    const val HEADER_VOLUME = "Volume"
    const val HEADER_USD_AMOUNT = "USD Amount"
    const val HEADER_FEE = "Fee"
    const val HEADER_SLIPPAGE = "Slippage"
    const val HEADER_STATUS = "Status"

    // Time & Status formatting
    const val AGO_SECONDS = "s ago"
    const val AM = "AM"
    const val PM = "PM"
    const val STATUS_DRY_RUN = "DRY RUN"
    const val STATUS_SUCCESS = "SUCCESS"
    const val STATUS_FAILED = "FAILED"
    const val PERIOD_HIGH = "Period High"

    // HIST-2: legend strings shortened to <=~28 chars; caveat moved to a caption.
    const val NET_CASH_FLOW_ALL = "Net Cash Flow (incl. dry run)"
    const val NET_CASH_FLOW_REALIZED = "Net Cash Flow (realized)"
    const val NET_AFTER_FEES = "Net After Fees"
    const val NET_AFTER_FEES_ESTIMATED = "Net After Fees (est.)"
    const val NET_CASH_FLOW_CAPTION =
        "Dry-run rows use estimated fees and are not accounted P&L. Toggle \"Show Dry Run Trades\" below to include them."
    const val SLIPPAGE_ESTIMATED_TITLE = "Estimated at order time"
    const val TRADE_FAILED_TITLE_PREFIX = "Error: "
    const val EM_DASH = "—"
    const val NO_TRADES_FOUND_PERIOD = "No trades found for this period."
    const val INVALID_SYMBOL_ALERT = "Invalid symbol. Symbols must be alphanumeric and up to 16 characters."
    const val SYMBOL_EXISTS_ALERT = "Symbol already exists"
}

/** Built-in History view preset identifiers (localStorage). */
object HistoryViewIds {
    const val OVERVIEW = "overview"
    const val DAY_TOTAL = "day-total"
    const val WEEK_ALLOCATION = "week-allocation"
    const val MONTH_NET_CASH_FLOW = "month-net-cash-flow"
}
