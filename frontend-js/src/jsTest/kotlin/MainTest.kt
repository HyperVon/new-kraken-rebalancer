package com.gemini.krakenbot.frontend

import com.gemini.krakenbot.view.util.CssClass
import com.gemini.krakenbot.view.util.FormFields
import com.gemini.krakenbot.view.util.HtmlEvents
import com.gemini.krakenbot.view.util.HtmlIds
import com.gemini.krakenbot.view.util.HtmlTags
import com.gemini.krakenbot.view.util.ViewText
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.*
import kotlin.js.Date
import com.gemini.krakenbot.view.util.CssClass.Query.DATA_AGE_VALUE as DATA_AGE_VALUE_QUERY

class MainTest : StringSpec() {
    override fun isolationMode() = IsolationMode.InstancePerTest

    init {
        "initOnLoad initializes dashboard content" {
            val container = document.createElement(HtmlTags.DIV)
            container.innerHTML = TestDomBuilders.dataAgeDom(Date.now().toString())
            document.body!!.appendChild(container)

            try {
                initOnLoad()

                val ageVal = document.querySelector(DATA_AGE_VALUE_QUERY) as HTMLElement
                ageVal.textContent!!.shouldBe("0s ago")
            } finally {
                document.body!!.removeChild(container)
            }
        }

        "initOnLoad initializes history content" {
            val container = document.createElement(HtmlTags.DIV)
            container.innerHTML = TestDomBuilders.chartsDom()
            document.body!!.appendChild(container)

            try {
                initOnLoad()
                (window.asDynamic().chartDefaults != null) shouldBe true
            } finally {
                document.body!!.removeChild(container)
            }
        }

        "main registers htmx event listener and interval" {
            val oldSetInterval = window.asDynamic().setInterval
            var intervalCb: (() -> Unit)? = null
            window.asDynamic().setInterval = { cb: () -> Unit, _: Int ->
                intervalCb = cb
                0
            }

            val container = document.createElement(HtmlTags.DIV)
            container.innerHTML = TestDomBuilders.dataAgeDom(Date.now().toString())
            document.body!!.appendChild(container)

            try {
                main()

                intervalCb?.invoke()
                val ageVal = document.querySelector(DATA_AGE_VALUE_QUERY) as HTMLElement
                ageVal.textContent!!.shouldBe("0s ago")

                val event = document.createEvent(HtmlEvents.EVENT)
                event.initEvent(type = HtmlEvents.HTMX_AFTER_SWAP, bubbles = true, cancelable = true)
                document.dispatchEvent(event)
            } finally {
                window.asDynamic().setInterval = oldSetInterval
                document.body!!.removeChild(container)
            }
        }

        "main reinitializes settings controls after an HTMX error swap" {
            val oldSetInterval = window.asDynamic().setInterval
            window.asDynamic().setInterval = { _: () -> Unit, _: Int -> 0 }
            val container = document.createElement(HtmlTags.DIV)
            fun settingsMarkup(firstTarget: String, secondTarget: String): String =
                """
                ${TestDomBuilders.settingsDom()}
                <input name="${FormFields.TARGETS}" value="$firstTarget">
                <input name="${FormFields.SYMBOLS}" value="BTC">
                <input name="${FormFields.TARGETS}" value="$secondTarget">
                <input name="${FormFields.SYMBOLS}" value="USD">
                """.trimIndent()
            container.innerHTML = settingsMarkup("50.0", "50.0")
            document.body!!.appendChild(container)

            try {
                main()
                container.innerHTML = settingsMarkup("40.0", "40.0")

                val event = document.createEvent(HtmlEvents.EVENT)
                event.initEvent(type = HtmlEvents.HTMX_AFTER_SWAP, bubbles = true, cancelable = true)
                document.dispatchEvent(event)

                val totalDisplay = document.getElementById(HtmlIds.TOTAL_ALLOCATED_DISPLAY)
                    as HTMLElement
                val saveButton = document.getElementById(HtmlIds.SAVE_BUTTON)
                    as HTMLButtonElement
                totalDisplay.textContent shouldBe "Total: 80.00%"
                saveButton.disabled shouldBe true

                val simulation = document.querySelector(
                    "input[name=\"${FormFields.SIMULATION}\"]",
                ) as HTMLInputElement
                simulation.checked = true
                val change = document.createEvent(HtmlEvents.EVENT)
                change.initEvent(type = HtmlEvents.CHANGE, bubbles = true, cancelable = true)
                simulation.dispatchEvent(change)
                document.getElementById(HtmlIds.MODE_PLATE_LABEL)?.textContent shouldBe ViewText.MODE_SIMULATION
            } finally {
                window.asDynamic().setInterval = oldSetInterval
                document.body!!.removeChild(container)
            }
        }

        "main registers DOMContentLoaded when body is null" {
            val oldSetInterval = window.asDynamic().setInterval
            window.asDynamic().setInterval = { _: () -> Unit, _: Int -> 0 }

            val container = document.createElement(HtmlTags.DIV)
            container.innerHTML = TestDomBuilders.dataAgeDom(Date.now().toString())
            document.body!!.appendChild(container)

            val oldBody = document.body
            defineGetter(document, "body", { null })

            try {
                main()

                val event = document.createEvent(HtmlEvents.EVENT)
                event.initEvent(type = HtmlEvents.DOM_CONTENT_LOADED, bubbles = true, cancelable = true)
                document.dispatchEvent(event)
            } finally {
                defineGetter(document, "body", { oldBody })
                window.asDynamic().setInterval = oldSetInterval
                document.body!!.removeChild(container)
            }
        }

        "registerSettingsGlobals and registerDashboardGlobals wrappers can be called" {
            registerSettingsGlobals()
            registerDashboardGlobals()

            val container = document.createElement(HtmlTags.DIV)
            container.innerHTML =
                """
                ${TestDomBuilders.assetEditDom("")}
                ${TestDomBuilders.settingsDom()}
                <table>
                  <thead>
                    <tr><th class="${CssClass.Table.Sortable}">${ViewText.HEADER_ASSET}</th></tr>
                  </thead>
                  <tbody></tbody>
                </table>
                """.trimIndent()
            document.body!!.appendChild(container)
            try {
                window.asDynamic().updateAllocationTotal()
                window.asDynamic().addAssetRow()
                window.asDynamic().sortTable(document.querySelector(CssClass.Query.SORTABLE_TH), 0)
            } finally {
                document.body!!.removeChild(container)
            }
        }
    }
}
