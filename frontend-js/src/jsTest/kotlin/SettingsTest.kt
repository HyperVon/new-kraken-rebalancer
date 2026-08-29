package com.gemini.krakenbot.frontend

import com.gemini.krakenbot.model.Asset
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
            val container = document.createElement("div") as HTMLDivElement

            val totalDisplay = document.createElement("span") as HTMLSpanElement
            totalDisplay.id = "total-allocated-display"
            container.appendChild(totalDisplay)

            val saveButton = document.createElement("button") as HTMLButtonElement
            saveButton.id = "save-button"
            container.appendChild(saveButton)

            val input1 = document.createElement("input") as HTMLInputElement
            input1.name = "targets"
            input1.value = "30.0"
            container.appendChild(input1)

            val sym1 = document.createElement("input") as HTMLInputElement
            sym1.name = "symbols"
            sym1.value = Asset.BTC
            container.appendChild(sym1)

            val input2 = document.createElement("input") as HTMLInputElement
            input2.name = "targets"
            input2.value = "70.0"
            container.appendChild(input2)

            val sym2 = document.createElement("input") as HTMLInputElement
            sym2.name = "symbols"
            sym2.value = Asset.USD
            container.appendChild(sym2)

            document.body!!.appendChild(container)

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
            val container = document.createElement("div") as HTMLDivElement

            val totalDisplay = document.createElement("span") as HTMLSpanElement
            totalDisplay.id = "total-allocated-display"
            container.appendChild(totalDisplay)

            val saveButton = document.createElement("button") as HTMLButtonElement
            saveButton.id = "save-button"
            container.appendChild(saveButton)

            val firstTarget = document.createElement("input") as HTMLInputElement
            firstTarget.name = "targets"
            firstTarget.value = "50"
            container.appendChild(firstTarget)
            val firstSymbol = document.createElement("input") as HTMLInputElement
            firstSymbol.name = "symbols"
            firstSymbol.value = Asset.BTC
            container.appendChild(firstSymbol)

            val secondTarget = document.createElement("input") as HTMLInputElement
            secondTarget.name = "targets"
            secondTarget.value = "49.995"
            container.appendChild(secondTarget)
            val secondSymbol = document.createElement("input") as HTMLInputElement
            secondSymbol.name = "symbols"
            secondSymbol.value = Asset.USD
            container.appendChild(secondSymbol)

            document.body!!.appendChild(container)

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
            val container = document.createElement("div") as HTMLDivElement

            val totalDisplay = document.createElement("span") as HTMLSpanElement
            totalDisplay.id = "total-allocated-display"
            container.appendChild(totalDisplay)

            val saveButton = document.createElement("button") as HTMLButtonElement
            saveButton.id = "save-button"
            container.appendChild(saveButton)

            val firstTarget = document.createElement("input") as HTMLInputElement
            firstTarget.name = "targets"
            firstTarget.value = "50"
            container.appendChild(firstTarget)
            val firstSymbol = document.createElement("input") as HTMLInputElement
            firstSymbol.name = "symbols"
            firstSymbol.value = Asset.BTC
            container.appendChild(firstSymbol)

            val secondTarget = document.createElement("input") as HTMLInputElement
            secondTarget.name = "targets"
            secondTarget.value = "50"
            container.appendChild(secondTarget)
            val secondSymbol = document.createElement("input") as HTMLInputElement
            secondSymbol.name = "symbols"
            secondSymbol.value = Asset.USD
            container.appendChild(secondSymbol)

            document.body!!.appendChild(container)

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
            container.innerHTML = TestDomBuilders.settingsAndSyncDom()
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
