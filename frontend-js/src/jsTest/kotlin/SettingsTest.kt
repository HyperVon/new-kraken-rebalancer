package com.gemini.krakenbot.frontend

import kotlinx.browser.document
import org.w3c.dom.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class SettingsTest {
    @Test
    fun testToFixed() {
        val num1 = 12.3456
        assertEquals("12.35", num1.toFixed(2))

        val num2 = 0.0
        assertEquals("0.00", num2.toFixed(2))

        val num3 = 1.00000003
        assertEquals("1.00000003", num3.toFixed(8))
    }

    @Test
    fun testUpdateAllocationTotal() {
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
        sym1.value = "BTC"
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
            assertEquals("Total: 100.00%", totalDisplay.textContent)
            assertFalse(saveButton.disabled)
            assertTrue(totalDisplay.classList.contains("live"))
            assertFalse(totalDisplay.classList.contains("delayed"))

            // Make sum invalid (90.0)
            input2.value = "60.0"
            updateAllocationTotal()
            assertEquals("Total: 90.00%", totalDisplay.textContent)
            assertTrue(saveButton.disabled)
            assertTrue(totalDisplay.classList.contains("delayed"))
            assertFalse(totalDisplay.classList.contains("live"))

            // Make sum valid but missing USD
            input2.value = "70.0"
            sym2.value = "ETH"
            updateAllocationTotal()
            assertEquals("Total: 100.00%", totalDisplay.textContent)
            assertTrue(saveButton.disabled)
            assertTrue(totalDisplay.classList.contains("delayed"))
        } finally {
            document.body!!.removeChild(container)
        }
    }

    @Test
    fun testAddAssetRow() {
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
        symInput.value = "LTC"
        container.appendChild(symInput)

        val existingSym = document.createElement("input") as HTMLInputElement
        existingSym.name = "symbols"
        existingSym.value = "BTC"
        container.appendChild(existingSym)

        document.body!!.appendChild(container)

        try {
            addAssetRow()
            
            // Check that LTC input was cleared
            assertEquals("", symInput.value)
            
            // Check that LTC row was added
            val rows = symContainer.querySelectorAll(".allocation-edit-row")
            assertEquals(1, rows.length)
            
            val row = rows.item(0) as HTMLElement
            assertTrue(row.textContent!!.contains("LTC"))

            val hiddenSymInput = row.querySelector("input[name=\"symbols\"]") as HTMLInputElement
            assertEquals("LTC", hiddenSymInput.value)
        } finally {
            document.body!!.removeChild(container)
        }
    }
}
