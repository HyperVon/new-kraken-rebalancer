package com.gemini.krakenbot.frontend

import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.*
import kotlin.math.abs

fun initSettings() {
    registerSettingsGlobals()
    updateAllocationTotal()
}

fun registerSettingsGlobals() {
    window.asDynamic().updateAllocationTotal = { updateAllocationTotal() }
    window.asDynamic().addAssetRow = { addAssetRow() }
}

fun updateAllocationTotal() {
    val inputs = document.querySelectorAll("input[name=\"targets\"]")
    var total = 0.0
    for (i in 0 until inputs.length) {
        val input = inputs.item(i) as? HTMLInputElement
        if (input != null) {
            total += input.value.toDoubleOrNull() ?: 0.0
        }
    }

    val totalDisplay = document.getElementById("total-allocated-display") ?: return
    totalDisplay.textContent = "Total: ${total.toFixed(2)}%"

    val saveButton = document.getElementById("save-button") as? HTMLButtonElement ?: return
    val isValid = abs(total - 100.0) <= 0.01

    val symbolInputs = document.querySelectorAll("input[name=\"symbols\"]")
    val symbols = mutableListOf<String>()
    for (i in 0 until symbolInputs.length) {
        val input = symbolInputs.item(i) as? HTMLInputElement
        if (input != null) {
            symbols.add(input.value.uppercase())
        }
    }
    val hasUsd = symbols.contains("USD")

    val isSuccess = isValid && hasUsd
    totalDisplay.classList.toggle("live", isSuccess)
    totalDisplay.classList.toggle("delayed", !isSuccess)
    saveButton.disabled = !isSuccess
}

fun addAssetRow() {
    val symbolInput = document.getElementById("new-symbol-input") as? HTMLInputElement ?: return
    val symbol = symbolInput.value.trim().uppercase()
    if (symbol.isEmpty()) return

    val symbolInputs = document.querySelectorAll("input[name=\"symbols\"]")
    val existingSymbols = mutableListOf<String>()
    for (i in 0 until symbolInputs.length) {
        val input = symbolInputs.item(i) as? HTMLInputElement
        if (input != null) {
            existingSymbols.add(input.value.uppercase())
        }
    }

    if (existingSymbols.contains(symbol)) {
        window.alert("Symbol already exists")
        return
    }

    val container = document.getElementById("allocations-container") ?: return
    val row = document.createElement("div") as HTMLDivElement
    row.className = "allocation-edit-row"

    row.innerHTML = """
        <div class="allocation-edit-symbol symbol-label">$symbol</div>
        <input type="hidden" name="symbols" value="$symbol">
        <div class="allocation-edit-input-wrapper">
            <input type="number" step="0.1" name="targets" class="input-glass" value="0.0" oninput="updateAllocationTotal()">
            <span class="percent-suffix">%</span>
        </div>
        <button type="button" class="btn btn-danger" onclick="this.closest('.allocation-edit-row').remove(); updateAllocationTotal();">Remove</button>
    """.trimIndent()

    container.appendChild(row)
    symbolInput.value = ""
    updateAllocationTotal()
}

fun Double.toFixed(digits: Int): String {
    return this.asDynamic().toFixed(digits).toString()
}
