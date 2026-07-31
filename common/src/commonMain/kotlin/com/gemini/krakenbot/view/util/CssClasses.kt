package com.gemini.krakenbot.view.util

/**
 * Type-safe CSS class management using sealed classes.
 * Shared between JVM backend template rendering and Kotlin/JS DOM manipulation.
 */
sealed class CssClass(open val value: String) {
    override fun toString(): String = value

    val querySelector: String
        get() = value.split(" ").filter { it.isNotBlank() }.joinToString("") { ".$it" }

    operator fun plus(other: CssClass): CssClass = Composite("$value ${other.value}".trim())

    class Composite(override val value: String) : CssClass(value)

    companion object {
        /** Shared active-state token used by nav links and history time-range buttons. */
        const val ACTIVE = "active"
    }

    // Layout & Panels
    sealed class Layout(override val value: String) : CssClass(value) {
        object Container : Layout("container")
        object GlassPanel : Layout("glass-panel")
        object DetailGrid : Layout("detail-grid")
        object HeaderTitleSection : Layout("header-title-section")
        object HeaderActions : Layout("header-actions")
        object BrandMark : Layout("brand-mark")
        object BrandPrimary : Layout("brand-primary")
        object BrandAccent : Layout("brand-accent")
        object HeaderStatus : Layout("header-status")
        object HeroGrid : Layout("hero-grid")
        object HeroSide : Layout("hero-side")
    }

    // GLOB-1/DASH-2: persistent trading-mode plate shown on every page.
    sealed class Mode(override val value: String) : CssClass(value) {
        object Plate : Mode("mode-plate")
        object Simulation : Mode("mode-plate mode-simulation")
        object DryRun : Mode("mode-plate mode-dry-run")
        object Live : Mode("mode-plate mode-live")
        object Dot : Mode("mode-plate-dot")
    }

    // DASH-1: dashboard hero (total portfolio) card.
    sealed class Hero(override val value: String) : CssClass(value) {
        object Card : Hero("glass-panel hero-card")
        object CardText : Hero("hero-card-text")
        object Label : Hero("hero-label")
        object Value : Hero("hero-value")
        object DeltaRow : Hero("hero-delta-row")
        object Delta : Hero("hero-delta")
        object DeltaUp : Hero("hero-delta up")
        object DeltaDown : Hero("hero-delta down")
        object DeltaFlat : Hero("hero-delta flat")
        object DeltaWindow : Hero("hero-delta-window")
        object Drawdown : Hero("hero-drawdown")
        object Spark : Hero("hero-spark")
        object TileCash : Hero("glass-panel hero-tile hero-tile-cash")
        object TileCrypto : Hero("glass-panel hero-tile hero-tile-crypto")
        object TileHeader : Hero("hero-tile-header")
        object TileTitle : Hero("hero-tile-title")
        object TileValue : Hero("hero-tile-value")
        object TileBarRow : Hero("hero-tile-bar-row")
        object TileBarTrack : Hero("hero-tile-bar-track")
        object TileBarFill : Hero("hero-tile-bar-fill")
        object TileMeta : Hero("hero-tile-meta")
    }

    // Status Cards
    sealed class StatusCard(override val value: String) : CssClass(value) {
        object Default : StatusCard("glass-panel status-card")
        object Header : StatusCard("status-card-header")
        object Title : StatusCard("status-card-title")
        object Icon : StatusCard("status-card-icon")
        object Value : StatusCard("status-card-value")
        object Badge : StatusCard("status-badge")
        object Live : StatusCard("status-badge live")
        object Delayed : StatusCard("status-badge delayed")
    }

    // Table Styling
    sealed class Table(override val value: String) : CssClass(value) {
        object Wrapper : Table("table-wrapper")
        object Hoverable : Table("hoverable")
        object MonoCol : Table("mono-col")
        object SymbolCol : Table("symbol-col")
        object Sortable : Table("sortable")
        object SortableAsc : Table("sortable asc")
        object StatusDot : Table("trade-status-dot")
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
        object AllocationTotal : Form("allocation-total")
        object AllocationTotalOk : Form("allocation-total-ok")
        object AllocationTotalBad : Form("allocation-total-bad")
        object AllocationEditSymbol : Form("allocation-edit-symbol")
        object AllocationColorSwatch : Form("allocation-color-swatch")
        object AllocationColorInput : Form("allocation-color-input")
        object AllocationEditInputWrapper : Form("allocation-edit-input-wrapper")
        object PercentSuffix : Form("percent-suffix")
        object AddAssetBox : Form("add-asset-box")
        object SectionHeader : Form("section-header")
        object SectionSubtitle : Form("form-section-subtitle")
        object SafetyGroup : Form("form-safety-group")
        object SafetyToggles : Form("form-safety-toggles")

        // SETT-1: safety toggle cards
        object SafetyCard : Form("safety-card")
        object SafetyCardInner : Form("safety-card-inner")
        object SafetyCardIcon : Form("safety-card-icon")
        object SafetyCardBody : Form("safety-card-body")
        object SafetyCardTitleRow : Form("safety-card-title-row")
        object SafetyCardTitle : Form("safety-card-title")
        object SafetyCardDesc : Form("safety-card-desc")
        object SafetyStatePill : Form("safety-state-pill")
        object SafetyStateOn : Form("safety-state-on")
        object SafetyStateOff : Form("safety-state-off")
    }

    // Buttons
    sealed class Button(override val value: String) : CssClass(value) {
        object Primary : Button("btn btn-primary")
        object Secondary : Button("btn btn-secondary")
        object Danger : Button("btn btn-danger")
        object DangerGhost : Button("btn btn-danger-ghost")
    }

    // Badges (shared outline system for activity + trade log)
    sealed class Badge(override val value: String) : CssClass(value) {
        object Buy : Badge("badge badge-buy")
        object Sell : Badge("badge badge-sell")
        object Info : Badge("badge badge-info")
        object Success : Badge("badge badge-success")
        object Failed : Badge("badge badge-failed")
        object SlippageFavorable : Badge("badge badge-slippage-favorable")
        object SlippageAdverse : Badge("badge badge-slippage-adverse")
        object SlippageNeutral : Badge("badge badge-slippage-neutral")
    }

    // Allocation Chart
    sealed class AllocationChart(override val value: String) : CssClass(value) {
        object Container : AllocationChart("allocation-chart-container")
        object BarRow : AllocationChart("allocation-bar-row")
        object BarLabel : AllocationChart("allocation-bar-label")
        object BarTrack : AllocationChart("allocation-bar-track")
        object BarFill : AllocationChart("allocation-bar-fill")
        object BarValue : AllocationChart("allocation-bar-value")
    }

    // Activity & History
    sealed class Activity(override val value: String) : CssClass(value) {
        object EmptyHistoryBox : Activity("empty-history-box")

        // DASH-3: cycle-grouped activity feed
        object Feed : Activity("activity-feed")
        object Cycle : Activity("activity-cycle")
        object CycleHeader : Activity("activity-cycle-header")
        object CycleTime : Activity("activity-cycle-time")
        object CycleMeta : Activity("activity-cycle-meta")
        object CycleBody : Activity("activity-cycle-body")
        object Item : Activity("activity-item")
        object ItemTrade : Activity("activity-item trade")
        object ItemText : Activity("activity-item-text")
        object FeedFooter : Activity("activity-feed-footer")
        object ViewAll : Activity("activity-view-all")
    }

    // Performance
    sealed class Performance(override val value: String) : CssClass(value) {
        object DevContainer : Performance("performance-dev-container")
        object DevUsdLabel : Performance("performance-dev-usd-label")
        object DevLegend : Performance("performance-dev-legend")
        object DevLegendItem : Performance("performance-dev-legend-item")
        object DevLegendOver : Performance("performance-dev-legend-over")
        object DevLegendUnder : Performance("performance-dev-legend-under")
    }

    // Data Age
    sealed class DataAge(override val value: String) : CssClass(value) {
        object Value : DataAge("data-age-value")
        object ValueStale : DataAge("data-age-value stale")
        object Time : DataAge("data-age-time")
    }

    // Loading
    sealed class Loading(override val value: String) : CssClass(value) {
        object SpinnerContainer : Loading("spinner-container")
        object Spinner : Loading("spinner")
    }

    // Dashboard
    sealed class Dashboard(override val value: String) : CssClass(value) {
        object WaitingTitle : Dashboard("dashboard-waiting-title")
        object WaitingText : Dashboard("dashboard-waiting-text")
    }

    // Navigation
    sealed class Navigation(override val value: String) : CssClass(value) {
        object Bar : Navigation("nav-bar")
        object Link : Navigation("nav-link")
        object LinkActive : Navigation("nav-link active")
    }

    // History page
    sealed class History(override val value: String) : CssClass(value) {
        object StatsGrid : History("history-stats-grid")
        object ToolbarRow : History("history-toolbar-row")
        object TimeRangeSelector : History("time-range-selector")
        object TimeRangeBtn : History("time-range-btn")
        object TimeRangeBtnActive : History("time-range-btn active")
        object ViewsToolbar : History("history-views-toolbar")
        object ViewsLabel : History("history-views-label")
        object ViewsSelect : History("history-views-select")
        object ViewsActions : History("history-views-actions")
        object ViewsBtn : History("history-views-btn")
        object ChartTools : History("history-chart-tools")
        object ChartHeader : History("history-chart-header")
        object ChartHeaderTitle : History("history-chart-header-title")
        object ZoomBtn : History("history-zoom-btn")
        object ChartContainer : History("chart-container")
        object ChartCaption : History("history-chart-caption")
        object ChartScrubber : History("history-chart-scrubber")
        object ChartScrubberInput : History("history-chart-scrubber-input")
        object TradeLogHeader : History("history-trade-log-header")
        object TradeLog : History("history-trade-log")
        object MutedSmallText : History("history-muted-small-text")
        object EmptyTableCell : History("history-empty-table-cell")
        object SyncBanner : History("history-sync-banner")
        object SyncHeader : History("history-sync-header")
        object SyncTitle : History("history-sync-title")
        object SyncSpinner : History("history-sync-spinner")
        object SyncText : History("history-sync-text")
        object ProgressTrack : History("history-progress-track")
        object ProgressBar : History("history-progress-bar")
        object TitleNoMargin : History("history-title-no-margin")
        object ComparisonHeader : History("comparison-header")
        object ComparisonDelta : History("comparison-delta")
        object ComparisonUnavailable : History("comparison-unavailable")
        object ComparisonChartArea : History("comparison-chart-area")
        object ComparisonConfidenceBadge : History("comparison-confidence-badge")
    }

    // Utility
    sealed class Utility(override val value: String) : CssClass(value) {
        object TextDanger : Utility("text-danger")
        object TextOverweight : Utility("text-overweight")
        object TextUnderweight : Utility("text-underweight")
        object GlassPanelTitle : Utility("glass-panel-title")
        object ErrorBanner : Utility("error-banner")
        object Stale : Utility("stale")
        object Live : Utility("live")
        object Delayed : Utility("delayed")
        object Asc : Utility("asc")
        object Desc : Utility("desc")
    }

    // Type-safe CSS Selectors for DOM queries
    object Query {
        val DATA_AGE_VALUE = DataAge.Value.querySelector
        val DATA_AGE_TIME = DataAge.Time.querySelector
        val STATUS_BADGE = StatusCard.Badge.querySelector
        val SORTABLE_TH = HtmlTags.TH + Table.Sortable.querySelector
        val HOVERABLE_TR = HtmlTags.TR + Table.Hoverable.querySelector
        val TIME_RANGE_BTNS = History.TimeRangeBtn.querySelector
        val ZOOM_BTNS = History.ZoomBtn.querySelector
        val CHART_SCRUBBERS = History.ChartScrubberInput.querySelector
        const val TARGET_INPUTS = "${HtmlTags.INPUT}[name=\"${FormFields.TARGETS}\"]"
        const val SYMBOL_INPUTS = "${HtmlTags.INPUT}[name=\"${FormFields.SYMBOLS}\"]"
    }
}
