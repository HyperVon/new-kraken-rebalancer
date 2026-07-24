package com.gemini.krakenbot.view.css

import com.gemini.krakenbot.view.css.ComponentStyles.applyComponentStyles
import com.gemini.krakenbot.view.css.CssTheme.applyRootVariables
import com.gemini.krakenbot.view.css.FormStyles.applyFormStyles
import com.gemini.krakenbot.view.css.LayoutStyles.applyLayoutStyles
import com.gemini.krakenbot.view.css.MediaQueries.applyMediaQueries
import com.gemini.krakenbot.view.css.NavigationStyles.applyNavigationStyles
import com.gemini.krakenbot.view.css.TableStyles.applyTableStyles
import kotlinx.css.CssBuilder

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
        applyMediaQueries()
    }
}
