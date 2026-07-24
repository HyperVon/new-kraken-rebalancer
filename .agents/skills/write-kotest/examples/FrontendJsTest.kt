package com.gemini.krakenbot.frontend

import com.gemini.krakenbot.view.util.CssClass
import com.gemini.krakenbot.view.util.HtmlIds
import com.gemini.krakenbot.view.util.HtmlTags
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.browser.document

/**
 * Example Kotest StringSpec for `:frontend-js` — prefer this shape over kotlin.test.
 * Production specs live under `frontend-js/src/jsTest/kotlin/`.
 */
class FrontendJsTest : StringSpec() {
    override fun isolationMode() = IsolationMode.InstancePerTest

    init {
        "should render a status badge element" {
            val badge = document.createElement(HtmlTags.SPAN)
            badge.id = HtmlIds.STAT_ATH
            badge.className = CssClass.StatusCard.Badge.toString()
            badge.textContent = "ACTIVE"
            document.body?.appendChild(badge)

            val found = document.getElementById(HtmlIds.STAT_ATH)
            found.shouldNotBeNull()
            found.textContent shouldBe "ACTIVE"
            found.className shouldBe CssClass.StatusCard.Badge.toString()

            document.body?.removeChild(badge)
        }
    }
}
