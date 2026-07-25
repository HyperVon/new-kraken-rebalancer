package com.gemini.krakenbot.frontend

import com.gemini.krakenbot.model.Asset
import com.gemini.krakenbot.view.util.CssClass
import com.gemini.krakenbot.view.util.FormFields
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
                totalDisplay.classList.contains(CssClass.Utility.Live).shouldBeTrue()

                input2.value = "60.0"
                updateAllocationTotal()
                totalDisplay.textContent shouldBe "Total: 90.00%"
                saveButton.disabled.shouldBeTrue()
                totalDisplay.classList.contains(CssClass.Utility.Delayed).shouldBeTrue()

                input2.value = "70.0"
                sym2.value = Asset.ETH
                updateAllocationTotal()
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
            } finally {
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
    }
}
