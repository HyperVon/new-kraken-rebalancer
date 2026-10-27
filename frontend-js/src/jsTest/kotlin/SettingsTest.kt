package com.gemini.krakenbot.frontend

import com.gemini.krakenbot.model.Asset
import com.gemini.krakenbot.view.util.HealthStatusKeys
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.*
import kotlin.js.js

class SettingsTest : StringSpec() {
    override fun isolationMode() = IsolationMode.InstancePerTest

    private fun saveResponse(publicationPending: Boolean, activeMode: String, savedAt: String) =
        """{"configPublicationPending":$publicationPending,"activeMode":"$activeMode","savedAt":"$savedAt"}"""

    init {
        "settings dirty state confirms navigation only while values differ from baseline" {
            val form = settingsStateForm()
            document.body!!.appendChild(form)
            val input = form.querySelector("input[name=loopDelaySeconds]") as HTMLInputElement
            val status = form.querySelector("#settings-save-status") as HTMLElement
            initSettings()
            val originalAddEventListener = window.asDynamic().addEventListener
            val originalRemoveEventListener = window.asDynamic().removeEventListener
            val addedListeners = mutableListOf<String>()
            val removedListeners = mutableListOf<String>()
            val listeners = mutableMapOf<String, dynamic>()
            window.asDynamic().addEventListener = { type: String, listener: dynamic ->
                addedListeners.add(type)
                listeners[type] = listener
            }
            window.asDynamic().removeEventListener = { type: String, _: dynamic ->
                removedListeners.add(type)
                listeners.remove(type)
            }

            try {
                initSettings()
                status.classList.contains("hidden").shouldBeTrue()
                dispatchSettingsInput(input, "61")
                status.textContent shouldBe "Unsaved changes"
                status.classList.contains("hidden").shouldBeFalse()
                addedListeners.contains("beforeunload").shouldBeTrue()
                val beforeUnloadEvent: dynamic = js("new Event('beforeunload', { cancelable: true })")
                val beforeUnloadHandler = checkNotNull(listeners["beforeunload"])
                beforeUnloadHandler(beforeUnloadEvent)
                (beforeUnloadEvent.defaultPrevented as Boolean).shouldBeTrue()

                dispatchSettingsInput(input, "60")
                status.textContent shouldBe ""
                status.classList.contains("hidden").shouldBeTrue()
                removedListeners.contains("beforeunload").shouldBeTrue()
                listeners.containsKey("beforeunload").shouldBeFalse()
            } finally {
                window.asDynamic().addEventListener = originalAddEventListener
                window.asDynamic().removeEventListener = originalRemoveEventListener
                document.body!!.removeChild(form)
                initSettings()
            }
        }

        "settings save response preserves the form and reports publication state and saved time" {
            val form = settingsStateForm()
            document.body!!.appendChild(form)
            val input = form.querySelector("input[name=loopDelaySeconds]") as HTMLInputElement
            val status = form.querySelector("#settings-save-status") as HTMLElement

            try {
                registerSettingsGlobals()
                initSettings()
                dispatchSettingsInput(input, "61")

                val failedSaveXhr = dispatchSettingsBeforeRequest("/settings", form)
                val persistenceFailure = dispatchSettingsResponse(
                    status = 500,
                    response = "{\"error\":\"persistence failed\"}",
                    xhr = failedSaveXhr,
                )
                (persistenceFailure.shouldSwap as Boolean).shouldBeFalse()
                input.value shouldBe "61"
                status.textContent shouldBe "Unsaved changes"

                val inFlightSaveXhr = dispatchSettingsBeforeRequest("/settings", form)
                dispatchSettingsInput(input, "62")
                val inFlightEdit = dispatchSettingsResponse(
                    status = 200,
                    response = saveResponse(false, "LIVE", "2026-10-19T14:40:00Z"),
                    xhr = inFlightSaveXhr,
                )
                (inFlightEdit.shouldSwap as Boolean).shouldBeFalse()
                input.value shouldBe "62"
                status.textContent shouldBe "Unsaved changes"
                status.classList.contains("hidden").shouldBeFalse()

                val staleSaveXhr = dispatchSettingsBeforeRequest("/settings", form)
                dispatchSettingsInput(input, "63")
                val previewXhr = dispatchSettingsBeforeRequest("/fragments/settings-allocations-preview", form)
                val previewEdit = dispatchSettingsResponse(
                    status = 200,
                    response = saveResponse(false, "LIVE", "2026-10-19T14:41:00Z"),
                    xhr = previewXhr,
                    contentType = "text/html; charset=UTF-8",
                )
                (previewEdit.shouldSwap as Boolean).shouldBeTrue()
                input.value shouldBe "63"
                status.textContent shouldBe "Unsaved changes"

                dispatchSettingsResponse(
                    status = 500,
                    response = "{\"error\":\"persistence failed\"}",
                    xhr = staleSaveXhr,
                )
                status.textContent shouldBe "Unsaved changes"

                val deferredSaveXhr = dispatchSettingsBeforeRequest("/settings", form)
                val deferredSave = dispatchSettingsResponse(
                    status = 200,
                    response = saveResponse(true, "LIVE", "2026-10-19T14:42:00Z"),
                    xhr = deferredSaveXhr,
                )
                (deferredSave.shouldSwap as Boolean).shouldBeFalse()
                input.value shouldBe "63"
                status.textContent shouldBe "Saved — applies after current cycle"
                status.getAttribute("datetime") shouldBe "2026-10-19T14:42:00Z"
            } finally {
                document.body!!.removeChild(form)
                initSettings()
            }
        }

        "overlapping settings saves acknowledge only their own submitted snapshot" {
            val form = settingsStateForm()
            document.body!!.appendChild(form)
            val input = form.querySelector("input[name=loopDelaySeconds]") as HTMLInputElement
            val status = form.querySelector("#settings-save-status") as HTMLElement

            try {
                registerSettingsGlobals()
                initSettings()
                val firstSaveXhr = dispatchSettingsBeforeRequest("/settings", form)
                dispatchSettingsInput(input, "61")
                val secondSaveXhr = dispatchSettingsBeforeRequest("/settings", form)

                dispatchSettingsResponse(
                    status = 200,
                    response = saveResponse(false, "LIVE", "2026-10-19T14:43:00Z"),
                    xhr = firstSaveXhr,
                )

                status.textContent shouldBe "Unsaved changes"
                dispatchSettingsResponse(
                    status = 200,
                    response = saveResponse(false, "LIVE", "2026-10-19T14:44:00Z"),
                    xhr = secondSaveXhr,
                )

                status.textContent shouldBe "Saved and active"
                status.getAttribute("datetime") shouldBe "2026-10-19T14:44:00Z"

                dispatchSettingsInput(input, "62")
                val olderSaveXhr = dispatchSettingsBeforeRequest("/settings", form)
                dispatchSettingsInput(input, "63")
                val latestSaveXhr = dispatchSettingsBeforeRequest("/settings", form)
                dispatchSettingsResponse(
                    status = 200,
                    response = saveResponse(false, "LIVE", "2026-10-19T14:46:00Z"),
                    xhr = latestSaveXhr,
                )
                status.textContent shouldBe "Saved and active"
                status.getAttribute("datetime") shouldBe "2026-10-19T14:46:00Z"

                dispatchSettingsResponse(
                    status = 200,
                    response = saveResponse(false, "SIMULATION", "2026-10-19T14:45:00Z"),
                    xhr = olderSaveXhr,
                )
                status.textContent shouldBe "Saved and active"
                status.getAttribute("datetime") shouldBe "2026-10-19T14:46:00Z"
            } finally {
                document.body!!.removeChild(form)
                initSettings()
            }
        }

        "settings mode plate follows active mode and waits for deferred publication" {
            val form = settingsStateForm()
            document.body!!.appendChild(form)
            val simulation = form.querySelector("input[name=simulation]") as HTMLInputElement
            val modeLabel = form.querySelector("#mode-plate-label") as HTMLElement
            val modePlate = form.querySelector("#mode-plate") as HTMLElement
            val originalHtmx = window.asDynamic().htmx
            val originalSetTimeout = window.asDynamic().setTimeout
            val requests = mutableListOf<dynamic>()
            var scheduledRefresh: dynamic = null
            val fakeHtmx = js("({})")
            fakeHtmx.ajax = { method: String, path: String, options: dynamic ->
                method shouldBe "GET"
                path shouldBe "/fragments/dashboard"
                (options.target as String) shouldBe "body"
                (options.swap as String) shouldBe "none"
                val promise =
                    js(
                        "({ then: function(callback) { this.onFulfilled = callback; return this; }, catch: function(callback) { this.onRejected = callback; return this; } })",
                    )
                requests.add(promise)
                promise
            }
            window.asDynamic().htmx = fakeHtmx
            window.asDynamic().setTimeout = { callback: dynamic, _: Int ->
                scheduledRefresh = callback
                1
            }

            try {
                registerSettingsGlobals()
                initSettings()
                simulation.checked = true
                val deferredXhr = dispatchSettingsBeforeRequest("/settings", form)
                dispatchSettingsResponse(
                    status = 200,
                    response = saveResponse(true, "SIMULATION", "2026-10-19T14:47:00Z"),
                    xhr = deferredXhr,
                )

                modeLabel.textContent shouldBe "LIVE TRADING"
                modePlate.getAttribute("class") shouldBe "mode-plate mode-live"
                requests.size shouldBe 1

                requests[0].onFulfilled(null)
                val firstRefresh = scheduledRefresh
                scheduledRefresh = null
                firstRefresh()
                requests.size shouldBe 2

                updateModePlate(HealthStatusKeys.MODE_SIMULATION)
                requests[1].onFulfilled(null)
                modeLabel.textContent shouldBe "SIMULATION"
                modePlate.getAttribute("class") shouldBe "mode-plate mode-simulation"
                modePlate.getAttribute("title") shouldBe "Simulation mode — no real orders are placed"
                (scheduledRefresh == null).shouldBeTrue()
            } finally {
                window.asDynamic().htmx = originalHtmx
                window.asDynamic().setTimeout = originalSetTimeout
                document.body!!.removeChild(form)
                initSettings()
            }
        }

        "stale settings save response cannot replace the current mode plate" {
            val form = settingsStateForm()
            document.body!!.appendChild(form)
            val simulation = form.querySelector("input[name=simulation]") as HTMLInputElement
            val dryRun = form.querySelector("input[name=dryRun]") as HTMLInputElement
            val modeLabel = form.querySelector("#mode-plate-label") as HTMLElement

            try {
                registerSettingsGlobals()
                initSettings()
                simulation.checked = true
                val older = dispatchSettingsBeforeRequest("/settings", form)
                simulation.checked = false
                dryRun.checked = true
                val latest = dispatchSettingsBeforeRequest("/settings", form)

                dispatchSettingsResponse(
                    status = 200,
                    response = saveResponse(false, "DRY_RUN", "2026-10-19T14:48:00Z"),
                    xhr = latest,
                )
                modeLabel.textContent shouldBe "DRY RUN"

                dispatchSettingsResponse(
                    status = 200,
                    response = saveResponse(false, "SIMULATION", "2026-10-19T14:49:00Z"),
                    xhr = older,
                )
                modeLabel.textContent shouldBe "DRY RUN"
            } finally {
                document.body!!.removeChild(form)
                initSettings()
            }
        }

        "toFixed formats decimal places" {
            val num1 = 12.3456
            num1.toFixed(2) shouldBe "12.35"

            val num2 = 0.0
            num2.toFixed(2) shouldBe "0.00"
        }

        "updateAllocationTotal validates totals and USD allocation" {
            val (container, totalDisplay, saveButton, input2, sym2) = allocationTotalFixture("30.0", "70.0")

            try {
                updateAllocationTotal()
                totalDisplay.textContent shouldBe "Total: 100.00%"
                saveButton.disabled.shouldBeFalse()
                totalDisplay.classList.contains("allocation-total-ok").shouldBeTrue()

                input2.value = "60.0"
                updateAllocationTotal()
                totalDisplay.textContent shouldBe "Total: 90.00%"
                saveButton.disabled.shouldBeTrue()
                totalDisplay.classList.contains("allocation-total-bad").shouldBeTrue()

                input2.value = "70.0"
                sym2.value = Asset.ETH
                updateAllocationTotal()
                saveButton.disabled.shouldBeTrue()
                totalDisplay.classList.contains("allocation-total-bad").shouldBeTrue()
                totalDisplay.textContent shouldBe "Total: 100.00%"
            } finally {
                document.body!!.removeChild(container)
            }
        }

        "updateAllocationTotal accepts ±0.01 tolerance edges and rejects outside" {
            // IEEE-safe sums: exact 99.99 (30+69.99) exceeds 0.01 by ulp and fails `<=`.
            val (container, totalDisplay, saveButton, secondTarget) = allocationTotalFixture("50", "49.995")

            try {
                updateAllocationTotal()
                totalDisplay.classList.contains("allocation-total-ok").shouldBeTrue()
                saveButton.disabled.shouldBeFalse()

                secondTarget.value = "50.005"
                updateAllocationTotal()
                totalDisplay.classList.contains("allocation-total-ok").shouldBeTrue()
                saveButton.disabled.shouldBeFalse()

                secondTarget.value = "50.02"
                updateAllocationTotal()
                totalDisplay.classList.contains("allocation-total-bad").shouldBeTrue()
                saveButton.disabled.shouldBeTrue()
            } finally {
                document.body!!.removeChild(container)
            }
        }

        "updateAllocationTotal exact boundary: 100.00 ok, 100.01 and 99.99 also ok (CQ-18-11)" {
            val (container, totalDisplay, saveButton, secondTarget) = allocationTotalFixture("50", "50")

            try {
                updateAllocationTotal()
                totalDisplay.classList.contains("allocation-total-ok").shouldBeTrue()
                saveButton.disabled.shouldBeFalse()

                secondTarget.value = "50.01"
                updateAllocationTotal()
                totalDisplay.classList.contains("allocation-total-ok").shouldBeTrue()
                saveButton.disabled.shouldBeFalse()

                secondTarget.value = "49.99"
                updateAllocationTotal()
                totalDisplay.classList.contains("allocation-total-ok").shouldBeTrue()
                saveButton.disabled.shouldBeFalse()

                secondTarget.value = "50.02"
                updateAllocationTotal()
                totalDisplay.classList.contains("allocation-total-bad").shouldBeTrue()
                saveButton.disabled.shouldBeTrue()
            } finally {
                document.body!!.removeChild(container)
            }
        }

        "addAssetRow appends a valid allocation" {
            val container = document.createElement("div") as HTMLDivElement

            val totalDisplay = document.createElement("span") as HTMLSpanElement
            totalDisplay.id = "total-allocated-display"
            container.appendChild(totalDisplay)

            val saveButton = document.createElement("button") as HTMLButtonElement
            saveButton.id = "save-button"
            container.appendChild(saveButton)

            val symContainer = document.createElement("div") as HTMLDivElement
            symContainer.id = "allocations-container"
            container.appendChild(symContainer)

            val symInput = document.createElement("input") as HTMLInputElement
            symInput.id = "new-symbol-input"
            symInput.value = Asset.LTC
            container.appendChild(symInput)

            val existingSym = document.createElement("input") as HTMLInputElement
            existingSym.name = "symbols"
            existingSym.value = Asset.BTC
            container.appendChild(existingSym)

            document.body!!.appendChild(container)

            try {
                addAssetRow()
                symInput.value shouldBe ""

                val rows = symContainer.querySelectorAll(".allocation-edit-row")
                rows.length shouldBe 1
                rows.item(0)!!.textContent!!.shouldContain(Asset.LTC)

                val firstRow = rows.item(0) as HTMLElement
                val hiddenSymInput = firstRow.querySelector("input[name=\"symbols\"]") as HTMLInputElement
                hiddenSymInput.value shouldBe Asset.LTC

                val numInput = firstRow.querySelector("input[name=\"targets\"]") as HTMLInputElement
                numInput.min shouldBe "0"
                numInput.max shouldBe "100"
            } finally {
                document.body!!.removeChild(container)
            }
        }

        "addAssetRow alerts and does not append invalid symbols" {
            val container = document.createElement("div") as HTMLDivElement
            container.innerHTML = TestDomBuilders.assetEditDom()
            document.body!!.appendChild(container)

            val symbolInput = document.getElementById("new-symbol-input") as HTMLInputElement
            val allocations = document.getElementById("allocations-container") as HTMLElement
            val originalAlert = window.asDynamic().alert
            var alertMessage: String? = null
            window.asDynamic().alert = { message: String -> alertMessage = message }

            try {
                symbolInput.value = "BTC-USD"
                addAssetRow()

                alertMessage shouldBe "Invalid symbol. Symbols must be alphanumeric and up to 16 characters."
                allocations.childElementCount shouldBe 0
                symbolInput.value shouldBe "BTC-USD"
            } finally {
                window.asDynamic().alert = originalAlert
                document.body!!.removeChild(container)
            }
        }

        "addAssetRow wires up target input and remove button callbacks" {
            val container = document.createElement("div") as HTMLDivElement

            val totalDisplay = document.createElement("span") as HTMLSpanElement
            totalDisplay.id = "total-allocated-display"
            container.appendChild(totalDisplay)

            val saveButton = document.createElement("button") as HTMLButtonElement
            saveButton.id = "save-button"
            container.appendChild(saveButton)

            val symContainer = document.createElement("div") as HTMLDivElement
            symContainer.id = "allocations-container"
            container.appendChild(symContainer)

            val symInput = document.createElement("input") as HTMLInputElement
            symInput.id = "new-symbol-input"
            symInput.value = Asset.LTC
            container.appendChild(symInput)

            document.body!!.appendChild(container)

            try {
                registerSettingsGlobals()
                addAssetRow()
                val row = symContainer.querySelector(".allocation-edit-row") as HTMLElement
                val targetInput = row.querySelector("input[name=\"targets\"]") as HTMLInputElement

                targetInput.value = "25.0"
                val inputEvent = document.createEvent("Event")
                inputEvent.initEvent(type = "input", bubbles = true, cancelable = true)
                targetInput.dispatchEvent(inputEvent)
                totalDisplay.textContent shouldBe "Total: 25.00%"

                val removeBtn = row.querySelector(".btn.btn-danger-ghost") as HTMLButtonElement
                removeBtn.click()
                symContainer.querySelectorAll(".allocation-edit-row").length shouldBe 0
                totalDisplay.textContent shouldBe "Total: 0.00%"
            } finally {
                document.body!!.removeChild(container)
            }
        }

        "settings helpers tolerate missing or incomplete fields" {
            updateAllocationTotal()
            addAssetRow()

            val container = document.createElement("div")
            container.innerHTML = "${TestDomBuilders.settingsDom()}\n${TestDomBuilders.assetEditDom(" ")}"
            document.body!!.appendChild(container)
            try {
                updateAllocationTotal()
                (document.getElementById("save-button") as HTMLButtonElement).disabled.shouldBeTrue()
                addAssetRow()
                (document.getElementById("allocations-container") as HTMLElement).children.length shouldBe 0
            } finally {
                document.body!!.removeChild(container)
            }
        }

        "addAssetRow handles edge cases" {
            val container = document.createElement("div")
            container.innerHTML = TestDomBuilders.assetEditDom(Asset.BTC)
            document.body!!.appendChild(container)
            try {
                val symbolInput = document.getElementById("new-symbol-input") as HTMLInputElement
                symbolInput.value = Asset.BTC
                val allocContainer = document.getElementById("allocations-container") as HTMLElement
                val existingRow = document.createElement("div")
                existingRow.className = "allocation-edit-row"
                existingRow.innerHTML =
                    """
                    <input type="hidden" name="symbols" value="${Asset.BTC}">
                    """.trimIndent()
                allocContainer.appendChild(existingRow)

                window.asDynamic().alertCalled = false
                window.asDynamic().alert = { _: String -> window.asDynamic().alertCalled = true }
                try {
                    addAssetRow()
                    (window.asDynamic().alertCalled as Boolean) shouldBe true
                    allocContainer.childElementCount.shouldBe(1)
                } finally {
                    window.asDynamic().alert = null
                }

                symbolInput.value = "NEW"
                allocContainer.remove()
                addAssetRow()
            } finally {
                if (container.parentNode != null) {
                    document.body!!.removeChild(container)
                }
            }
        }

        "updateAllocationTotal ignores non-input elements and invalid numbers" {
            val container = document.createElement("div")
            container.innerHTML = TestDomBuilders.settingsFormDom()
            document.body!!.appendChild(container)
            try {
                val nonInputTarget = document.createElement("div")
                nonInputTarget.setAttribute("name", "targets")
                container.appendChild(nonInputTarget)

                val invalidInputTarget = document.createElement("input") as HTMLInputElement
                invalidInputTarget.name = "targets"
                invalidInputTarget.value = "invalid-double"
                container.appendChild(invalidInputTarget)

                val nonInputSymbol = document.createElement("div")
                nonInputSymbol.setAttribute("name", "symbols")
                container.appendChild(nonInputSymbol)

                updateAllocationTotal()
            } finally {
                document.body!!.removeChild(container)
            }
        }
    }
}

private data class AllocationTotalFixture(
    val container: Element,
    val totalDisplay: Element,
    val saveButton: HTMLButtonElement,
    val secondTarget: HTMLInputElement,
    val secondSymbol: HTMLInputElement,
)

private fun allocationTotalFixture(first: String, second: String): AllocationTotalFixture {
    val container = document.createElement("div")
    container.innerHTML = """
        <span id="total-allocated-display"></span>
        <button id="save-button"></button>
        <input name="targets" value="$first">
        <input name="symbols" value="BTC">
        <input name="targets" value="$second">
        <input name="symbols" value="USD">
    """.trimIndent()
    document.body!!.appendChild(container)
    return AllocationTotalFixture(
        container,
        container.querySelector("#total-allocated-display")!!,
        container.querySelector("#save-button") as HTMLButtonElement,
        container.querySelectorAll("input[name=targets]").item(1) as HTMLInputElement,
        container.querySelectorAll("input[name=symbols]").item(1) as HTMLInputElement,
    )
}

private fun settingsStateForm(): HTMLFormElement {
    val form = document.createElement("form") as HTMLFormElement
    form.id = "settings-form"
    form.setAttribute("data-settings-baseline", "16:loopDelaySeconds2:6010:simulation5:false6:dryRun5:false")
    form.innerHTML = """
        <input name="loopDelaySeconds" value="60">
        <input type="checkbox" name="simulation">
        <input type="checkbox" name="dryRun">
        <span id="mode-plate" class="mode-plate mode-live" title="Live trading — real orders execute with real funds">
            <span id="mode-plate-label">LIVE TRADING</span>
        </span>
        <span id="total-allocated-display"></span>
        <button id="save-button"></button>
        <time id="settings-save-status" class="hidden"></time>
    """.trimIndent()
    return form
}

private fun dispatchSettingsInput(input: HTMLInputElement, value: String) {
    input.value = value
    val event = document.createEvent("Event")
    event.initEvent(type = "input", bubbles = true, cancelable = true)
    input.dispatchEvent(event)
}

private fun dispatchSettingsBeforeRequest(path: String, target: Element): dynamic {
    val xhr = js("({})")
    val requestConfig = js("({})")
    requestConfig.path = path
    requestConfig.verb = "post"
    val detail = js("({})")
    detail.xhr = xhr
    detail.requestConfig = requestConfig
    val event = document.createEvent("CustomEvent")
    event.asDynamic().initCustomEvent("htmx:beforeRequest", true, true, detail)
    target.dispatchEvent(event)
    return xhr
}

private fun dispatchSettingsResponse(
    status: Int,
    response: String,
    xhr: dynamic = js("({})"),
    contentType: String = "application/json; charset=UTF-8",
): dynamic {
    xhr.status = status
    xhr.responseText = response
    xhr.getResponseHeader = { name: String ->
        if (name == "Content-Type") contentType else null
    }
    val detail = js("({})")
    detail.xhr = xhr
    detail.shouldSwap = true
    val event = document.createEvent("CustomEvent")
    event.asDynamic().initCustomEvent("htmx:beforeSwap", true, true, detail)
    document.dispatchEvent(event)
    return detail
}
