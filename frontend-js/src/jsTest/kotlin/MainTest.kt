package com.gemini.krakenbot.frontend

import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.*
import kotlin.js.Date
import kotlin.js.js

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
            } finally {
                window.asDynamic().setInterval = oldSetInterval
                document.body!!.removeChild(container)
            }
        }

        "only marked HTML settings and order-intent errors are swapped" {
            shouldSwapRenderedError(
                422,
                "<form hx-post=\"/settings\"><div class=\"error-banner\">nope</div></form>",
                "text/html; charset=UTF-8",
                "settings-form",
            ) shouldBe true
            shouldSwapRenderedError(
                403,
                "<form hx-post=\"/settings\"><div class=\"error-banner\">forbidden</div></form>",
                "text/html",
                "settings-form",
            ) shouldBe true
            shouldSwapRenderedError(
                422,
                "{\"error\":\"invalid\"}",
                "application/json",
                null,
            ) shouldBe false
            shouldSwapRenderedError(422, "<div>unmarked</div>", "text/html", null) shouldBe false
            shouldSwapRenderedError(
                422,
                "<div class=\"error-banner\">operator message</div>",
                "text/html",
                "order-intent",
            ) shouldBe true
            shouldSwapRenderedError(
                409,
                "<div class=\"error-banner\">conflict</div>",
                "text/html",
                "order-intent",
            ) shouldBe true
            shouldSwapRenderedError(500, "<div>boom</div>", "text/html", "order-intent") shouldBe false
            shouldSwapRenderedError(422, "", "text/html", "settings-form") shouldBe false
            shouldSwapRenderedError(422, "<div>message</div>", null, "settings-form") shouldBe false
            shouldSwapRenderedError(399, "<div>message</div>", "text/html", "order-intent") shouldBe false
        }

        "the htmx beforeSwap listener swaps marked errors but preserves the form on stale CSRF" {
            val oldSetInterval = window.asDynamic().setInterval
            val oldAlert = window.asDynamic().alert
            window.asDynamic().setInterval = { _: () -> Unit, _: Int -> 0 }
            var alertMessage: String? = null
            window.asDynamic().alert = { message: String -> alertMessage = message }
            val container = document.createElement("div")
            container.innerHTML = """
                <input name="csrfToken" value="old-token">
                <input name="csrfToken" value="old-token">
                <input name="deviationTriggerPercent" value="9.5">
            """.trimIndent()
            document.body!!.appendChild(container)

            try {
                main()

                fun dispatchBeforeSwap(
                    status: Int,
                    body: String,
                    contentType: String,
                    errorKind: String?,
                    csrfToken: String? = null,
                    sessionExpired: Boolean = false,
                ): dynamic {
                    val xhr = js("({})")
                    xhr.status = status
                    xhr.responseText = body
                    xhr.getResponseHeader = { name: String ->
                        when (name) {
                            "Content-Type" -> contentType
                            "X-Rebalancer-Error-Fragment" -> errorKind
                            "X-Rebalancer-CSRF-Token" -> csrfToken
                            "X-Rebalancer-CSRF-Session-Expired" -> if (sessionExpired) "true" else null
                            else -> null
                        }
                    }
                    val detail = js("({})")
                    detail.xhr = xhr
                    detail.shouldSwap = false
                    detail.isError = true
                    val event = document.createEvent("CustomEvent")
                    event.asDynamic().initCustomEvent("htmx:beforeSwap", true, true, detail)
                    document.dispatchEvent(event)
                    return detail
                }

                val operatorError = dispatchBeforeSwap(
                    409,
                    "<div class=\"error-banner\">conflict</div>",
                    "text/html; charset=UTF-8",
                    "order-intent",
                )
                (operatorError.shouldSwap as Boolean) shouldBe true
                (operatorError.isError as Boolean) shouldBe false

                val apiError = dispatchBeforeSwap(
                    422,
                    "{\"error\":\"invalid\"}",
                    "application/json",
                    null,
                )
                (apiError.shouldSwap as Boolean) shouldBe false
                (apiError.isError as Boolean) shouldBe true

                val staleCsrf = dispatchBeforeSwap(
                    403,
                    "",
                    "",
                    null,
                    csrfToken = "fresh-token",
                    sessionExpired = true,
                )
                (staleCsrf.shouldSwap as Boolean) shouldBe false
                (staleCsrf.isError as Boolean) shouldBe true
                alertMessage shouldBe
                    "This page's security token is invalid or expired. Your action was not applied. Please try again."
                document.querySelectorAll("input[name=csrfToken]").let { inputs ->
                    inputs.length shouldBe 2
                    repeat(inputs.length) { index ->
                        (inputs.item(index) as HTMLInputElement).value shouldBe "fresh-token"
                    }
                }
                (document.querySelector("input[name=deviationTriggerPercent]") as HTMLInputElement)
                    .value shouldBe "9.5"
            } finally {
                window.asDynamic().setInterval = oldSetInterval
                window.asDynamic().alert = oldAlert
                document.body!!.removeChild(container)
            }
        }

        "only a settings mutation clears its error while a background proposal GET preserves it" {
            val oldSetInterval = window.asDynamic().setInterval
            window.asDynamic().setInterval = { _: () -> Unit, _: Int -> 0 }
            val container = document.createElement("div")
            container.innerHTML = """
                <form>
                  <div class="error-banner">a previous rejection</div>
                  <input name="targets" value="50.0">
                </form>
                <div class="error-banner">a dashboard error, not a form one</div>
            """.trimIndent()
            document.body!!.appendChild(container)

            try {
                main()
                document.querySelectorAll(".error-banner").length shouldBe 2

                fun dispatchBeforeRequest(verb: String, path: String) {
                    val event = document.createEvent("CustomEvent")
                    val requestConfig = js("({})")
                    requestConfig.verb = verb
                    requestConfig.path = path
                    val detail = js("({})")
                    detail.requestConfig = requestConfig
                    event.asDynamic().initCustomEvent(
                        "htmx:beforeRequest",
                        true,
                        true,
                        detail,
                    )
                    document.dispatchEvent(event)
                }

                dispatchBeforeRequest("get", "/fragments/settings-proposal")
                (document.querySelector("form .error-banner") != null) shouldBe true

                val missingConfigEvent = document.createEvent("CustomEvent")
                val missingConfigDetail = js("({})")
                missingConfigEvent.asDynamic().initCustomEvent(
                    "htmx:beforeRequest",
                    true,
                    true,
                    missingConfigDetail,
                )
                document.dispatchEvent(missingConfigEvent)
                (document.querySelector("form .error-banner") != null) shouldBe true

                dispatchBeforeRequest("post", "/settings")

                // The stale attempt message goes, so a successful swap cannot leave it above
                // freshly rendered content; the dashboard's own region is untouched.
                (document.querySelector("form .error-banner") == null) shouldBe true
                document.querySelectorAll(".error-banner").length shouldBe 1

                shouldClearSettingsError("POST", "/fragments/settings-allocations-preview") shouldBe true
                shouldClearSettingsError("POST", "/settings?tab=allocations#targets") shouldBe true
                shouldClearSettingsError("POST", "/settings#targets") shouldBe true
                shouldClearSettingsError("GET", "/settings") shouldBe false
                shouldClearSettingsError("POST", "/fragments/settings-proposal") shouldBe false
                shouldClearSettingsError("POST", null) shouldBe false
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
