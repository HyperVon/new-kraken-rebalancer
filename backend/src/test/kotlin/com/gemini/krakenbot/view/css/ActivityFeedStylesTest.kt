package com.gemini.krakenbot.view.css

import com.gemini.krakenbot.view.css.ActivityFeedStyles.applyActivityFeedStyles
import com.gemini.krakenbot.view.css.FormStyles.applyFormStyles
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.string.shouldContain
import kotlinx.css.CssBuilder

class ActivityFeedStylesTest : StringSpec() {
    init {
        "hidden activity paging overrides the button display rule" {
            val css = CssBuilder().apply {
                applyActivityFeedStyles()
                applyFormStyles()
            }.toString()

            css shouldContain "#dashboard-activity-show-older[hidden] {\ndisplay: none;"
            css shouldContain ".btn {\ndisplay: inline-flex;"
        }
    }
}
