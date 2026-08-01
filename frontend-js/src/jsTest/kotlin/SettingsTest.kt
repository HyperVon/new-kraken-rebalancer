package com.gemini.krakenbot.frontend

import com.gemini.krakenbot.model.Asset
import com.gemini.krakenbot.view.util.CssClass
import com.gemini.krakenbot.view.util.FormFields
import com.gemini.krakenbot.view.util.HtmlAttrs
import com.gemini.krakenbot.view.util.HtmlEvents
import com.gemini.krakenbot.view.util.HtmlIds
import com.gemini.krakenbot.view.util.HtmlTags
import com.gemini.krakenbot.view.util.ViewText
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.*

class SettingsTest : StringSpec() {
    override fun isolationMode() = IsolationMode.InstancePerTest

    init {
        "toFixed formats decimal places" {
            val num1 = 12.3456
            num1.toFixed(2) shouldBe "12.35"

            val num2 = 0.0
            num2.toFixed(2) shouldBe "0.00"
        }

        "updateAllocationTotal validates totals and USD allocation" {
            val container = document.createElement(HtmlTags.DIV) as HTMLDivElement

            val totalDisplay = document.createElement(HtmlTags.SPAN) as HTMLSpanElement
            totalDisplay.id = HtmlIds.TOTAL_ALLOCATED_DISPLAY
            container.appendChild(totalDisplay)

            val saveButton = document.createElement(HtmlTags.BUTTON) as HTMLButtonElement
            saveButton.id = HtmlIds.SAVE_BUTTON
            container.appendChild(saveButton)

            val input1 = document.createElement(HtmlTags.INPUT) as HTMLInputElement
            input1.name = FormFields.TARGETS
            input1.value = "30.0"
            container.appendChild(input1)

            val sym1 = document.createElement(HtmlTags.INPUT) as HTMLInputElement
            sym1.name = FormFields.SYMBOLS
            sym1.value = Asset.BTC
            container.appendChild(sym1)

            val input2 = document.createElement(HtmlTags.INPUT) as HTMLInputElement
            input2.name = FormFields.TARGETS
            input2.value = "70.0"
            container.appendChild(input2)

            val sym2 = document.createElement(HtmlTags.INPUT) as HTMLInputElement
            sym2.name = FormFields.SYMBOLS
            sym2.value = Asset.USD
            container.appendChild(sym2)

            document.body!!.appendChild(container)

            try {
                updateAllocationTotal()
                totalDisplay.textContent shouldBe "Total: 100.00%"
                saveButton.disabled.shouldBeFalse()
                totalDisplay.classList.contains(CssClass.Form.AllocationTotalOk).shouldBeTrue()

                input2.value = "60.0"
                updateAllocationTotal()
                totalDisplay.textContent shouldBe "Total: 90.00%"
                saveButton.disabled.shouldBeTrue()
                totalDisplay.classList.contains(CssClass.Form.AllocationTotalBad).shouldBeTrue()

                input2.value = "70.0"
                sym2.value = Asset.ETH
                updateAllocationTotal()
                saveButton.disabled.shouldBeTrue()
                totalDisplay.classList.contains(CssClass.Form.AllocationTotalBad).shouldBeTrue()
                totalDisplay.textContent shouldBe "Total: 100.00%"
            } finally {
                document.body!!.removeChild(container)
            }
        }

        "updateAllocationTotal accepts ±0.01 tolerance edges and rejects outside" {
            // IEEE-safe sums: exact 99.99 (30+69.99) exceeds 0.01 by ulp and fails `<=`.
            val container = document.createElement(HtmlTags.DIV) as HTMLDivElement

            val totalDisplay = document.createElement(HtmlTags.SPAN) as HTMLSpanElement
            totalDisplay.id = HtmlIds.TOTAL_ALLOCATED_DISPLAY
            container.appendChild(totalDisplay)

            val saveButton = document.createElement(HtmlTags.BUTTON) as HTMLButtonElement
            saveButton.id = HtmlIds.SAVE_BUTTON
            container.appendChild(saveButton)

            val firstTarget = document.createElement(HtmlTags.INPUT) as HTMLInputElement
            firstTarget.name = FormFields.TARGETS
            firstTarget.value = "50"
            container.appendChild(firstTarget)
            val firstSymbol = document.createElement(HtmlTags.INPUT) as HTMLInputElement
            firstSymbol.name = FormFields.SYMBOLS
            firstSymbol.value = Asset.BTC
            container.appendChild(firstSymbol)

            val secondTarget = document.createElement(HtmlTags.INPUT) as HTMLInputElement
            secondTarget.name = FormFields.TARGETS
            secondTarget.value = "49.995"
            container.appendChild(secondTarget)
            val secondSymbol = document.createElement(HtmlTags.INPUT) as HTMLInputElement
            secondSymbol.name = FormFields.SYMBOLS
            secondSymbol.value = Asset.USD
            container.appendChild(secondSymbol)

            document.body!!.appendChild(container)

            try {
                updateAllocationTotal()
                totalDisplay.classList.contains(CssClass.Form.AllocationTotalOk).shouldBeTrue()
                saveButton.disabled.shouldBeFalse()

                secondTarget.value = "50.005"
                updateAllocationTotal()
                totalDisplay.classList.contains(CssClass.Form.AllocationTotalOk).shouldBeTrue()
                saveButton.disabled.shouldBeFalse()

                secondTarget.value = "50.02"
                updateAllocationTotal()
                totalDisplay.classList.contains(CssClass.Form.AllocationTotalBad).shouldBeTrue()
                saveButton.disabled.shouldBeTrue()
            } finally {
                document.body!!.removeChild(container)
            }
        }

        "addAssetRow appends a valid allocation" {
            val container = document.createElement(HtmlTags.DIV) as HTMLDivElement

            val totalDisplay = document.createElement(HtmlTags.SPAN) as HTMLSpanElement
            totalDisplay.id = HtmlIds.TOTAL_ALLOCATED_DISPLAY
            container.appendChild(totalDisplay)

            val saveButton = document.createElement(HtmlTags.BUTTON) as HTMLButtonElement
            saveButton.id = HtmlIds.SAVE_BUTTON
            container.appendChild(saveButton)

            val symContainer = document.createElement(HtmlTags.DIV) as HTMLDivElement
            symContainer.id = HtmlIds.ALLOCATIONS_CONTAINER
            container.appendChild(symContainer)

            val symInput = document.createElement(HtmlTags.INPUT) as HTMLInputElement
            symInput.id = HtmlIds.NEW_SYMBOL_INPUT
            symInput.value = Asset.LTC
            container.appendChild(symInput)

            val existingSym = document.createElement(HtmlTags.INPUT) as HTMLInputElement
            existingSym.name = FormFields.SYMBOLS
            existingSym.value = Asset.BTC
            container.appendChild(existingSym)

            document.body!!.appendChild(container)

            try {
                addAssetRow()
                symInput.value shouldBe ""

                val rows = symContainer.querySelectorAll(".${CssClass.Form.AllocationEditRow}")
                rows.length shouldBe 1
                rows.item(0)!!.textContent!!.shouldContain(Asset.LTC)

                val firstRow = rows.item(0) as HTMLElement
                val hiddenSymInput = firstRow.querySelector(CssClass.Query.SYMBOL_INPUTS) as HTMLInputElement
                hiddenSymInput.value shouldBe Asset.LTC

                val numInput = firstRow.querySelector(CssClass.Query.TARGET_INPUTS) as HTMLInputElement
                numInput.min shouldBe "0"
                numInput.max shouldBe "100"

                val colorInput = firstRow.querySelector("input[type=\"color\"]") as HTMLInputElement
            } finally {
                document.body!!.removeChild(container)
            }
        }

        "addAssetRow alerts and does not append invalid symbols" {
            val container = document.createElement(HtmlTags.DIV) as HTMLDivElement
            container.innerHTML = TestDomBuilders.assetEditDom()
            document.body!!.appendChild(container)

            val symbolInput = document.getElementById(HtmlIds.NEW_SYMBOL_INPUT) as HTMLInputElement
            val allocations = document.getElementById(HtmlIds.ALLOCATIONS_CONTAINER) as HTMLElement
            val originalAlert = window.asDynamic().alert
            var alertMessage: String? = null
            window.asDynamic().alert = { message: String -> alertMessage = message }

            try {
                symbolInput.value = "BTC-USD"
                addAssetRow()

                alertMessage shouldBe ViewText.INVALID_SYMBOL_ALERT
                allocations.childElementCount shouldBe 0
                symbolInput.value shouldBe "BTC-USD"
            } finally {
                window.asDynamic().alert = originalAlert
                document.body!!.removeChild(container)
            }
        }

        "addAssetRow wires up target input and remove button callbacks" {
            val container = document.createElement(HtmlTags.DIV) as HTMLDivElement

            val totalDisplay = document.createElement(HtmlTags.SPAN) as HTMLSpanElement
            totalDisplay.id = HtmlIds.TOTAL_ALLOCATED_DISPLAY
            container.appendChild(totalDisplay)

            val saveButton = document.createElement(HtmlTags.BUTTON) as HTMLButtonElement
            saveButton.id = HtmlIds.SAVE_BUTTON
            container.appendChild(saveButton)

            val symContainer = document.createElement(HtmlTags.DIV) as HTMLDivElement
            symContainer.id = HtmlIds.ALLOCATIONS_CONTAINER
            container.appendChild(symContainer)

            val symInput = document.createElement(HtmlTags.INPUT) as HTMLInputElement
            symInput.id = HtmlIds.NEW_SYMBOL_INPUT
            symInput.value = Asset.LTC
            container.appendChild(symInput)

            document.body!!.appendChild(container)

            try {
                registerSettingsGlobals()
                addAssetRow()
                val row = symContainer.querySelector(".${CssClass.Form.AllocationEditRow}") as HTMLElement
                val targetInput = row.querySelector(CssClass.Query.TARGET_INPUTS) as HTMLInputElement

                targetInput.value = "25.0"
                val inputEvent = document.createEvent(HtmlEvents.EVENT)
                inputEvent.initEvent(type = HtmlEvents.INPUT, bubbles = true, cancelable = true)
                targetInput.dispatchEvent(inputEvent)
                totalDisplay.textContent shouldBe "Total: 25.00%"

                val removeBtn = row.querySelector(CssClass.Button.Danger.querySelector) as HTMLButtonElement
                removeBtn.click()
                symContainer.querySelectorAll(".${CssClass.Form.AllocationEditRow}").length shouldBe 0
                totalDisplay.textContent shouldBe "Total: 0.00%"
            } finally {
                document.body!!.removeChild(container)
            }
        }

        "registerSettingsGlobals exposes settings actions" {
            registerSettingsGlobals()
            (window.asDynamic().updateAllocationTotal != null) shouldBe true
            (window.asDynamic().addAssetRow != null) shouldBe true
        }

        "initSettings registers globals and updates totals" {
            val container = document.createElement(HtmlTags.DIV)
            container.innerHTML = TestDomBuilders.settingsDom()
            document.body!!.appendChild(container)
            try {
                initSettings()
                (window.asDynamic().addAssetRow != null) shouldBe true
            } finally {
                document.body!!.removeChild(container)
            }
        }

        "syncModePlateFromSafetyToggles reflects checkbox state with simulation > dryRun > live precedence" {
            val container = document.createElement(HtmlTags.DIV) as HTMLDivElement

            val plate = document.createElement(HtmlTags.SPAN) as HTMLSpanElement
            plate.id = HtmlIds.MODE_PLATE
            val label = document.createElement(HtmlTags.SPAN) as HTMLSpanElement
            label.id = HtmlIds.MODE_PLATE_LABEL
            plate.appendChild(label)
            container.appendChild(plate)

            val simulation = document.createElement(HtmlTags.INPUT) as HTMLInputElement
            simulation.type = "checkbox"
            simulation.name = FormFields.SIMULATION
            container.appendChild(simulation)

            val dryRun = document.createElement(HtmlTags.INPUT) as HTMLInputElement
            dryRun.type = "checkbox"
            dryRun.name = FormFields.DRY_RUN
            container.appendChild(dryRun)

            document.body!!.appendChild(container)
            try {
                syncModePlateFromSafetyToggles()
                plate.className shouldBe CssClass.Mode.Live.toString()
                label.textContent shouldBe ViewText.MODE_LIVE

                dryRun.checked = true
                syncModePlateFromSafetyToggles()
                plate.className shouldBe CssClass.Mode.DryRun.toString()
                label.textContent shouldBe ViewText.MODE_DRY_RUN

                simulation.checked = true
                syncModePlateFromSafetyToggles()
                plate.className shouldBe CssClass.Mode.Simulation.toString()
                label.textContent shouldBe ViewText.MODE_SIMULATION
            } finally {
                document.body!!.removeChild(container)
            }
        }

        "initSettings wires safety toggle change events to the mode plate" {
            val container = document.createElement(HtmlTags.DIV) as HTMLDivElement

            val plate = document.createElement(HtmlTags.SPAN) as HTMLSpanElement
            plate.id = HtmlIds.MODE_PLATE
            val label = document.createElement(HtmlTags.SPAN) as HTMLSpanElement
            label.id = HtmlIds.MODE_PLATE_LABEL
            plate.appendChild(label)
            container.appendChild(plate)

            val simulation = document.createElement(HtmlTags.INPUT) as HTMLInputElement
            simulation.type = "checkbox"
            simulation.name = FormFields.SIMULATION
            container.appendChild(simulation)

            document.body!!.appendChild(container)
            try {
                initSettings()
                plate.className shouldBe CssClass.Mode.Live.toString()

                simulation.checked = true
                val changeEvent = document.createEvent(HtmlEvents.EVENT)
                changeEvent.initEvent(type = HtmlEvents.CHANGE, bubbles = true, cancelable = true)
                simulation.dispatchEvent(changeEvent)

                plate.className shouldBe CssClass.Mode.Simulation.toString()
                label.textContent shouldBe ViewText.MODE_SIMULATION
            } finally {
                document.body!!.removeChild(container)
            }
        }

        "settings helpers tolerate missing or incomplete fields" {
            updateAllocationTotal()
            addAssetRow()

            val container = document.createElement(HtmlTags.DIV)
            container.innerHTML = "${TestDomBuilders.settingsDom()}\n${TestDomBuilders.assetEditDom(" ")}"
            document.body!!.appendChild(container)
            try {
                updateAllocationTotal()
                (document.getElementById(HtmlIds.SAVE_BUTTON) as HTMLButtonElement).disabled.shouldBeTrue()
                addAssetRow()
                (document.getElementById(HtmlIds.ALLOCATIONS_CONTAINER) as HTMLElement).children.length shouldBe 0
            } finally {
                document.body!!.removeChild(container)
            }
        }

        "addAssetRow handles edge cases" {
            val container = document.createElement(HtmlTags.DIV)
            container.innerHTML = TestDomBuilders.assetEditDom(Asset.BTC)
            document.body!!.appendChild(container)
            try {
                val symbolInput = document.getElementById(HtmlIds.NEW_SYMBOL_INPUT) as HTMLInputElement
                symbolInput.value = Asset.BTC
                val allocContainer = document.getElementById(HtmlIds.ALLOCATIONS_CONTAINER) as HTMLElement
                val existingRow = document.createElement(HtmlTags.DIV)
                existingRow.className = CssClass.Form.AllocationEditRow.toString()
                existingRow.innerHTML =
                    """
                    <input type="hidden" name="${FormFields.SYMBOLS}" value="${Asset.BTC}">
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
            val container = document.createElement(HtmlTags.DIV)
            container.innerHTML = TestDomBuilders.settingsAndSyncDom()
            document.body!!.appendChild(container)
            try {
                val nonInputTarget = document.createElement(HtmlTags.DIV)
                nonInputTarget.setAttribute("name", FormFields.TARGETS)
                container.appendChild(nonInputTarget)

                val invalidInputTarget = document.createElement(HtmlTags.INPUT) as HTMLInputElement
                invalidInputTarget.name = FormFields.TARGETS
                invalidInputTarget.value = "invalid-double"
                container.appendChild(invalidInputTarget)

                val nonInputSymbol = document.createElement(HtmlTags.DIV)
                nonInputSymbol.setAttribute("name", FormFields.SYMBOLS)
                container.appendChild(nonInputSymbol)

                updateAllocationTotal()
            } finally {
                document.body!!.removeChild(container)
            }
        }
    }
}
