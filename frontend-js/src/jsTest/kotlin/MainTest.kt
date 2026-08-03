package com.gemini.krakenbot.frontend

import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.*
import kotlin.js.Date
class MainTest : StringSpec() {
    override fun isolationMode() = IsolationMode.InstancePerTest

    init {
        "initOnLoad initializes dashboard content" {
            val container = document.createElement("div")
            container.innerHTML = TestDomBuilders.dataAgeDom(Date.now().toString())
            document.body!!.appendChild(container)

            try {
                initOnLoad()

                val ageVal = document.querySelector(".data-age-value") as HTMLElement
                ageVal.textContent!!.shouldBe("0s ago")
            } finally {
                document.body!!.removeChild(container)
            }
        }

        "initOnLoad initializes history content" {
            val container = document.createElement("div")
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

            val container = document.createElement("div")
            container.innerHTML = TestDomBuilders.dataAgeDom(Date.now().toString())
            document.body!!.appendChild(container)

            try {
                main()

                intervalCb?.invoke()
                val ageVal = document.querySelector(".data-age-value") as HTMLElement
                ageVal.textContent!!.shouldBe("0s ago")

                val event = document.createEvent("Event")
                event.initEvent(type = "htmx:afterSwap", bubbles = true, cancelable = true)
                document.dispatchEvent(event)
            } finally {
                window.asDynamic().setInterval = oldSetInterval
                document.body!!.removeChild(container)
            }
        }

        "main reinitializes settings controls after an HTMX error swap" {
            val oldSetInterval = window.asDynamic().setInterval
            window.asDynamic().setInterval = { _: () -> Unit, _: Int -> 0 }
            val container = document.createElement("div")
            fun settingsMarkup(firstTarget: String, secondTarget: String): String =
                """
                ${TestDomBuilders.settingsDom()}
                <input name="targets" value="$firstTarget">
                <input name="symbols" value="BTC">
                <input name="targets" value="$secondTarget">
                <input name="symbols" value="USD">
                """.trimIndent()
            container.innerHTML = settingsMarkup("50.0", "50.0")
            document.body!!.appendChild(container)

            try {
                main()
                container.innerHTML = settingsMarkup("40.0", "40.0")

                val event = document.createEvent("Event")
                event.initEvent(type = "htmx:afterSwap", bubbles = true, cancelable = true)
                document.dispatchEvent(event)

                val totalDisplay = document.getElementById("total-allocated-display")
                    as HTMLElement
                val saveButton = document.getElementById("save-button")
                    as HTMLButtonElement
                totalDisplay.textContent shouldBe "Total: 80.00%"
                saveButton.disabled shouldBe true

                val simulation = document.querySelector(
                    "input[name=\"simulation\"]",
                ) as HTMLInputElement
                simulation.checked = true
                val change = document.createEvent("Event")
                change.initEvent(type = "change", bubbles = true, cancelable = true)
                simulation.dispatchEvent(change)
                document.getElementById("mode-plate-label")?.textContent shouldBe "SIMULATION"
            } finally {
                window.asDynamic().setInterval = oldSetInterval
                document.body!!.removeChild(container)
            }
        }

        "main registers DOMContentLoaded when body is null" {
            val oldSetInterval = window.asDynamic().setInterval
            window.asDynamic().setInterval = { _: () -> Unit, _: Int -> 0 }

            val container = document.createElement("div")
            container.innerHTML = TestDomBuilders.dataAgeDom(Date.now().toString())
            document.body!!.appendChild(container)

            val oldBody = document.body
            defineGetter(document, "body", { null })

            try {
                main()

                val event = document.createEvent("Event")
                event.initEvent(type = "DOMContentLoaded", bubbles = true, cancelable = true)
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

            val container = document.createElement("div")
            container.innerHTML =
                """
                ${TestDomBuilders.assetEditDom("")}
                ${TestDomBuilders.settingsDom()}
                <table>
                  <thead>
                    <tr><th class="sortable">Asset</th></tr>
                  </thead>
                  <tbody></tbody>
                </table>
                """.trimIndent()
            document.body!!.appendChild(container)
            try {
                window.asDynamic().updateAllocationTotal()
                window.asDynamic().addAssetRow()
                window.asDynamic().sortTable(document.querySelector("th.sortable"), 0)
            } finally {
                document.body!!.removeChild(container)
            }
        }
    }
}
