package com.gemini.krakenbot.view.css

import com.gemini.krakenbot.view.css.FormStyles.applyFormStyles
import com.gemini.krakenbot.view.css.MediaQueries.applyMediaQueries
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.css.CssBuilder

class SettingsAllocationResponsiveStylesTest : StringSpec() {
    override fun isolationMode() = IsolationMode.InstancePerTest

    private val stylesheet = CssBuilder().apply {
        applyFormStyles()
        applyMediaQueries()
    }.toString()

    init {
        "mobile allocation rows fit one column and keep every control in the layout" {
            val mobileRules = stylesheet.substringAfter("@media (max-width: 639px)")
            val selectors = mobileRules.lines().map(String::trim)
            val scoreRule =
                ruleDeclarations(mobileRules, """.allocation-edit-input-wrapper:has(input[name="scores"])""")
            val targetRule =
                ruleDeclarations(mobileRules, """.allocation-edit-input-wrapper:has(input[name="targets"])""")
            val removeRule = ruleDeclarations(mobileRules, ".allocation-edit-row > .btn.btn-danger-ghost")
            val allocationControlsRule = ruleDeclarations(mobileRules, ".allocation-control-inputs")
            val allocationControlButtonRule = ruleDeclarations(mobileRules, ".allocation-control-inputs > button")

            mobileRules shouldContain ".allocation-list-container"
            mobileRules shouldContain "grid-template-columns: minmax(0, 1fr)"
            mobileRules shouldContain ".allocation-edit-row"
            mobileRules shouldContain "grid-template-columns: 2rem minmax(0, 1fr) minmax(0, 1fr)"
            selectors shouldContain ".allocation-edit-row > label {"
            selectors shouldContain ".allocation-edit-input-wrapper:has(input[name=\"scores\"]) {"
            selectors shouldContain ".allocation-edit-input-wrapper:has(input[name=\"targets\"]) {"
            selectors shouldContain ".allocation-edit-row > .btn.btn-danger-ghost {"
            selectors shouldContain ".allocation-control-inputs {"
            selectors shouldContain ".allocation-control-inputs > button {"

            ruleDeclarations(mobileRules, ".allocation-edit-symbol") shouldContain "grid-column: 1 / span 2"
            ruleDeclarations(mobileRules, ".allocation-edit-symbol") shouldContain "grid-row: 1"
            ruleDeclarations(mobileRules, ".allocation-edit-row > label") shouldContain "grid-column: 1"
            ruleDeclarations(mobileRules, ".allocation-edit-row > label") shouldContain "grid-row: 2"
            scoreRule shouldContain "grid-column: 2"
            scoreRule shouldContain "grid-row: 2"
            targetRule shouldContain "grid-column: 3"
            targetRule shouldContain "grid-row: 2"
            removeRule shouldContain "grid-column: 3"
            removeRule shouldContain "grid-row: 1"
            allocationControlsRule shouldContain "flex-wrap: wrap"
            allocationControlButtonRule shouldContain "flex: 1 1 100%"
        }

        "desktop allocation rows retain their existing list and field sizing" {
            val desktopRules = stylesheet.substringBefore("@media (max-width: 639px)")

            desktopRules shouldContain "repeat(auto-fit, minmax(30rem, 1fr))"
            desktopRules shouldContain "5rem 2rem 7.5rem 7.5rem 1fr"
        }
    }

    private fun ruleDeclarations(css: String, selector: String): String {
        val lines = css.lines().map(String::trim)
        val start = lines.indexOf("$selector {")
        (start >= 0) shouldBe true
        val declarations = lines.drop(start + 1).takeWhile { it != "}" }
        declarations.isNotEmpty() shouldBe true
        return declarations.joinToString(" ")
    }
}
