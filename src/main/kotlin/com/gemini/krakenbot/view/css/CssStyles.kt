package com.gemini.krakenbot.view.css

import com.gemini.krakenbot.view.css.ComponentStyles.applyComponentStyles
import com.gemini.krakenbot.view.css.CssTheme.applyRootVariables
import com.gemini.krakenbot.view.css.FormStyles.applyFormStyles
import com.gemini.krakenbot.view.css.HistoryChartStyles.applyHistoryChartStyles
import com.gemini.krakenbot.view.css.HistoryComparisonStyles.applyHistoryComparisonStyles
import com.gemini.krakenbot.view.css.HistoryContentStyles.applyHistoryContentStyles
import com.gemini.krakenbot.view.css.HistoryToolbarStyles.applyHistoryToolbarStyles
import com.gemini.krakenbot.view.css.LayoutStyles.applyLayoutStyles
import com.gemini.krakenbot.view.css.MediaQueries.applyMediaQueries
import com.gemini.krakenbot.view.css.NavigationStyles.applyNavigationStyles
import com.gemini.krakenbot.view.css.PerformanceDevStyles.applyPerformanceDevStyles
import com.gemini.krakenbot.view.css.TableStyles.applyTableStyles
import com.gemini.krakenbot.view.css.UtilityStyles.applyUtilityStyles
import kotlinx.css.BorderStyle
import kotlinx.css.Color
import kotlinx.css.CssBuilder
import kotlinx.css.LinearDimension
import kotlinx.css.borderColor
import kotlinx.css.borderStyle
import kotlinx.css.borderWidth
import kotlinx.css.px

/**
 * Aggregator facade for application-wide CSS stylesheet generation.
 * Modularized into domain styles under `com.gemini.krakenbot.view.css`.
 */
object CssStyles {
    val stylesheet = CssBuilder().apply {
        applyRootVariables()
        applyLayoutStyles()
        applyComponentStyles()
        applyTableStyles()
        applyFormStyles()
        applyNavigationStyles()
        applyHistoryToolbarStyles()
        applyHistoryChartStyles()
        applyHistoryContentStyles()
        applyPerformanceDevStyles()
        applyUtilityStyles()
        applyHistoryComparisonStyles()
        applyMediaQueries()
    }
}

/** 1px solid border with a given color, matching the contiguous width/style/color triple. */
internal fun CssBuilder.solidBorder(color: Color, width: LinearDimension = 1.px) {
    borderWidth = width
    borderStyle = BorderStyle.solid
    borderColor = color
}
