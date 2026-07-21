package com.gemini.krakenbot.frontend

import com.gemini.krakenbot.model.Asset
import io.kotest.matchers.shouldBe
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.string.shouldContain
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.*

@Suppress("unused")
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
        sym2.value = "USD"
        container.appendChild(sym2)

        document.body!!.appendChild(container)

        try {
            updateAllocationTotal()
            totalDisplay.textContent shouldBe "Total: 100.00%"
            saveButton.disabled.shouldBeFalse()
            totalDisplay.classList.contains("live").shouldBeTrue()

            input2.value = "60.0"
            updateAllocationTotal()
            totalDisplay.textContent shouldBe "Total: 90.00%"
            saveButton.disabled.shouldBeTrue()
            totalDisplay.classList.contains("delayed").shouldBeTrue()

            input2.value = "70.0"
            sym2.value = "ETH"
            updateAllocationTotal()
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
            rows.item(0)!!.textContent!!.shouldContain("LTC")

            val firstRow = rows.item(0) as HTMLElement
            val hiddenSymInput = firstRow.querySelector("input[name=\"symbols\"]") as HTMLInputElement
            hiddenSymInput.value shouldBe "LTC"
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
        val container = document.createElement("div")
        container.innerHTML = """
            <span id="total-allocated-display"></span>
            <button id="save-button"></button>
        """.trimIndent()
        document.body!!.appendChild(container)
        try {
            initSettings()
            (window.asDynamic().addAssetRow != null) shouldBe true
        } finally {
            document.body!!.removeChild(container)
        }
    }

        "settings helpers tolerate missing or incomplete fields" {
        updateAllocationTotal()
        addAssetRow()

        val container = document.createElement("div")
        container.innerHTML = """
            <span id="total-allocated-display"></span><button id="save-button"></button>
            <input id="new-symbol-input" value=" "><div id="allocations-container"></div>
        """.trimIndent()
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
    }
}
