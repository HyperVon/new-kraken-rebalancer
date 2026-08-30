package com.gemini.krakenbot.view.util

/** DOM selectors shared by server-rendered HTML and the Kotlin/JS client. */
object HtmlQueries {
    const val CSRF_TOKEN = "#${HtmlIds.CSRF_TOKEN}"
    val DATA_AGE_VALUE = CssClass.DataAge.Value.querySelector
    val DATA_AGE_TIME = CssClass.DataAge.Time.querySelector
    val STATUS_BADGE = CssClass.StatusCard.Badge.querySelector
    val SORTABLE_TH = HtmlTags.TH + CssClass.Table.Sortable.querySelector
    val HOVERABLE_TR = HtmlTags.TR + CssClass.Table.Hoverable.querySelector
    val TIME_RANGE_BTNS = CssClass.History.TimeRangeBtn.querySelector
    val ZOOM_BTNS = CssClass.History.ZoomBtn.querySelector
    val CHART_SCRUBBERS = CssClass.History.ChartScrubberInput.querySelector
    const val TARGET_INPUTS = "${HtmlTags.INPUT}[name=\"${FormFields.TARGETS}\"]"
    const val SYMBOL_INPUTS = "${HtmlTags.INPUT}[name=\"${FormFields.SYMBOLS}\"]"
}
