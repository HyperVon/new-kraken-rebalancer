package com.gemini.krakenbot.view.util

/**
 * Type-safe CSS class management using sealed classes.
 * Provides better structure, IDE support, and prevents typos in CSS class names.
 * 
 * Each category is represented as a sealed class with constants defined as companion object properties.
 * Use CssClass.Category.propertyName for type-safe access, or CssClasses.CONSTANT_NAME for legacy support.
 */

sealed class CssClass(open val value: String) {
    override fun toString(): String = value

    operator fun plus(other: CssClass): String = "$value ${other.value}".trim()
    operator fun plus(other: String): String = "$value $other".trim()

    // Layout & Panels
    sealed class Layout(override val value: String) : CssClass(value) {
        object Container : Layout("container")
        object GlassPanel : Layout("glass-panel")
        object DetailGrid : Layout("detail-grid")
        object HeaderTitleSection : Layout("header-title-section")
        object HeaderActions : Layout("header-actions")
        object OverviewGrid : Layout("overview-grid")

        companion object {
            const val CONTAINER = "container"
            const val GLASS_PANEL = "glass-panel"
            const val DETAIL_GRID = "detail-grid"
            const val HEADER_TITLE_SECTION = "header-title-section"
            const val HEADER_ACTIONS = "header-actions"
            const val OVERVIEW_GRID = "overview-grid"
        }
    }

    // Status Cards
    sealed class StatusCard(override val value: String) : CssClass(value) {
        object Default : StatusCard("glass-panel status-card")
        object Success : StatusCard("glass-panel status-card success")
        object Header : StatusCard("status-card-header")
        object Title : StatusCard("status-card-title")
        object Icon : StatusCard("status-card-icon")
        object Value : StatusCard("status-card-value")
        object Sub : StatusCard("status-card-sub")
        object Live : StatusCard("status-badge live")
        object Delayed : StatusCard("status-badge delayed")

        companion object {
            const val STATUS_CARD = "glass-panel status-card"
            const val STATUS_CARD_SUCCESS = "glass-panel status-card success"
            const val STATUS_CARD_HEADER = "status-card-header"
            const val STATUS_CARD_TITLE = "status-card-title"
            const val STATUS_CARD_ICON = "status-card-icon"
            const val STATUS_CARD_VALUE = "status-card-value"
            const val STATUS_CARD_SUB = "status-card-sub"
            const val STATUS_BADGE_LIVE = "status-badge live"
            const val STATUS_BADGE_DELAYED = "status-badge delayed"
        }
    }

    // Table Styling
    sealed class Table(override val value: String) : CssClass(value) {
        object Wrapper : Table("table-wrapper")
        object Hoverable : Table("hoverable")
        object MonoCol : Table("mono-col")
        object SymbolCol : Table("symbol-col")
        object Sortable : Table("sortable")
        object SortableAsc : Table("sortable asc")

        companion object {
            const val TABLE_WRAPPER = "table-wrapper"
            const val HOVERABLE = "hoverable"
            const val MONO_COL = "mono-col"
            const val SYMBOL_COL = "symbol-col"
            const val SORTABLE = "sortable"
            const val SORTABLE_ASC = "sortable asc"
        }
    }

    // Form Elements
    sealed class Form(override val value: String) : CssClass(value) {
        object InputGlass : Form("input-glass")
        object Group : Form("form-group")
        object Section : Form("form-section")
        object SectionTitle : Form("form-section-title")
        object Label : Form("form-label")
        object Grid2Col : Form("grid-2col")
        object CheckboxContainer : Form("checkbox-container")
        object CheckboxCustom : Form("checkbox-custom")
        object AllocationListContainer : Form("allocation-list-container")
        object AllocationEditRow : Form("allocation-edit-row")
        object AllocationEditSymbol : Form("allocation-edit-symbol")
        object AllocationEditInputWrapper : Form("allocation-edit-input-wrapper")
        object PercentSuffix : Form("percent-suffix")
        object AddAssetBox : Form("add-asset-box")
        object GroupCentered : Form("form-group-centered")
        object SectionHeader : Form("section-header")

        companion object {
            const val INPUT_GLASS = "input-glass"
            const val FORM_GROUP = "form-group"
            const val FORM_SECTION = "form-section"
            const val FORM_SECTION_TITLE = "form-section-title"
            const val FORM_LABEL = "form-label"
            const val GRID_2COL = "grid-2col"
            const val CHECKBOX_CONTAINER = "checkbox-container"
            const val CHECKBOX_CUSTOM = "checkbox-custom"
            const val ALLOCATION_LIST_CONTAINER = "allocation-list-container"
            const val ALLOCATION_EDIT_ROW = "allocation-edit-row"
            const val ALLOCATION_EDIT_SYMBOL = "allocation-edit-symbol"
            const val ALLOCATION_EDIT_INPUT_WRAPPER = "allocation-edit-input-wrapper"
            const val PERCENT_SUFFIX = "percent-suffix"
            const val ADD_ASSET_BOX = "add-asset-box"
            const val FORM_GROUP_CENTERED = "form-group-centered"
        }
    }

    // Buttons
    sealed class Button(override val value: String) : CssClass(value) {
        object Primary : Button("btn btn-primary")
        object Secondary : Button("btn btn-secondary")
        object Danger : Button("btn btn-danger")
        object Icon : Button("btn-icon")

        companion object {
            const val BTN_PRIMARY = "btn btn-primary"
            const val BTN_SECONDARY = "btn btn-secondary"
            const val BTN_DANGER = "btn btn-danger"
            const val BTN_ICON = "btn-icon"
        }
    }

    // Badges
    sealed class Badge(override val value: String) : CssClass(value) {
        object Buy : Badge("badge badge-buy")
        object Sell : Badge("badge badge-sell")
        object Info : Badge("badge badge-info")

        companion object {
            const val BADGE_BUY = "badge badge-buy"
            const val BADGE_SELL = "badge badge-sell"
            const val BADGE_INFO = "badge badge-info"
        }
    }

    // Allocation Chart
    sealed class AllocationChart(override val value: String) : CssClass(value) {
        object Container : AllocationChart("allocation-chart-container")
        object BarRow : AllocationChart("allocation-bar-row")
        object BarLabel : AllocationChart("allocation-bar-label")
        object BarTrack : AllocationChart("allocation-bar-track")
        object BarFill : AllocationChart("allocation-bar-fill")
        object BarValue : AllocationChart("allocation-bar-value")

        companion object {
            const val ALLOCATION_CHART_CONTAINER = "allocation-chart-container"
            const val ALLOCATION_BAR_ROW = "allocation-bar-row"
            const val ALLOCATION_BAR_LABEL = "allocation-bar-label"
            const val ALLOCATION_BAR_TRACK = "allocation-bar-track"
            const val ALLOCATION_BAR_FILL = "allocation-bar-fill"
            const val ALLOCATION_BAR_VALUE = "allocation-bar-value"
        }
    }

    // Activity & History
    sealed class Activity(override val value: String) : CssClass(value) {
        object EmptyText : Activity("recent-activity-empty-text")
        object DotMarker : Activity("recent-activity-dot-marker")
        object RowContainer : Activity("recent-activity-row-container")
        object EmptyHistoryBox : Activity("empty-history-box")
        object CustomScrollbarMaxH100 : Activity("custom-scrollbar max-h-100")

        companion object {
            const val RECENT_ACTIVITY_EMPTY_TEXT = "recent-activity-empty-text"
            const val RECENT_ACTIVITY_DOT_MARKER = "recent-activity-dot-marker"
            const val RECENT_ACTIVITY_ROW_CONTAINER = "recent-activity-row-container"
            const val EMPTY_HISTORY_BOX = "empty-history-box"
            const val CUSTOM_SCROLLBAR_MAX_H_100 = "custom-scrollbar max-h-100"
        }
    }

    // Performance
    sealed class Performance(override val value: String) : CssClass(value) {
        object DevContainer : Performance("performance-dev-container")
        object DevUsdLabel : Performance("performance-dev-usd-label")

        companion object {
            const val PERFORMANCE_DEV_CONTAINER = "performance-dev-container"
            const val PERFORMANCE_DEV_USD_LABEL = "performance-dev-usd-label"
        }
    }

    // Data Age
    sealed class DataAge(override val value: String) : CssClass(value) {
        object Container : DataAge("data-age-container")
        object Label : DataAge("data-age-label")
        object Value : DataAge("data-age-value")
        object ValueStale : DataAge("data-age-value stale")
        object Time : DataAge("data-age-time")

        companion object {
            const val DATA_AGE_CONTAINER = "data-age-container"
            const val DATA_AGE_LABEL = "data-age-label"
            const val DATA_AGE_VALUE = "data-age-value"
            const val DATA_AGE_VALUE_STALE = "data-age-value stale"
            const val DATA_AGE_TIME = "data-age-time"
        }
    }

    // Loading
    sealed class Loading(override val value: String) : CssClass(value) {
        object SpinnerContainer : Loading("spinner-container")
        object Spinner : Loading("spinner")

        companion object {
            const val SPINNER_CONTAINER = "spinner-container"
            const val SPINNER = "spinner"
        }
    }

    // Dashboard
    sealed class Dashboard(override val value: String) : CssClass(value) {
        object WaitingTitle : Dashboard("dashboard-waiting-title")
        object WaitingText : Dashboard("dashboard-waiting-text")

        companion object {
            const val DASHBOARD_WAITING_TITLE = "dashboard-waiting-title"
            const val DASHBOARD_WAITING_TEXT = "dashboard-waiting-text"
        }
    }

    // Navigation
    sealed class Navigation(override val value: String) : CssClass(value) {
        object Bar : Navigation("nav-bar")
        object Link : Navigation("nav-link")
        object LinkActive : Navigation("nav-link active")

        companion object {
            const val NAV_BAR = "nav-bar"
            const val NAV_LINK = "nav-link"
            const val NAV_LINK_ACTIVE = "nav-link active"
        }
    }

    // History
    sealed class History(override val value: String) : CssClass(value) {
        object StatsGrid : History("history-stats-grid")
        object TimeRangeSelector : History("time-range-selector")
        object TimeRangeBtn : History("time-range-btn")
        object TimeRangeBtnActive : History("time-range-btn active")
        object ChartContainer : History("chart-container")

        companion object {
            const val HISTORY_STATS_GRID = "history-stats-grid"
            const val TIME_RANGE_SELECTOR = "time-range-selector"
            const val TIME_RANGE_BTN = "time-range-btn"
            const val TIME_RANGE_BTN_ACTIVE = "time-range-btn active"
            const val CHART_CONTAINER = "chart-container"
        }
    }

    // Utility
    sealed class Utility(override val value: String) : CssClass(value) {
        object TextDanger : Utility("text-danger")
        object GlassPanelTitle : Utility("glass-panel-title")
        object ErrorBanner : Utility("error-banner")

        companion object {
            const val TEXT_DANGER = "text-danger"
            const val GLASS_PANEL_TITLE = "glass-panel-title"
            const val ERROR_BANNER = "error-banner"
        }
    }
}


