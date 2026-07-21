package com.gemini.krakenbot.frontend

import com.gemini.krakenbot.model.Asset
import com.gemini.krakenbot.view.util.CssClass
import com.gemini.krakenbot.view.util.FormFields
import com.gemini.krakenbot.view.util.HtmlIds
import com.gemini.krakenbot.view.util.ViewText
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
    val inputs = document.querySelectorAll(TARGET_INPUTS_QUERY)
    var total = 0.0
    for (i in 0 until inputs.length) {
        val input = inputs.item(i) as? HTMLInputElement
        if (input != null) {
            total += input.value.toDoubleOrNull() ?: 0.0
        }
    }

    val totalDisplay = document.getElementById(HtmlIds.TOTAL_ALLOCATED_DISPLAY) ?: return
    totalDisplay.textContent = "Total: ${total.toFixed(2)}%"

    val saveButton = document.getElementById(HtmlIds.SAVE_BUTTON) as? HTMLButtonElement ?: return
    val isValid = abs(total - 100.0) <= 0.01

    val symbolInputs = document.querySelectorAll(SYMBOL_INPUTS_QUERY)
    val symbols = mutableListOf<String>()
    for (i in 0 until symbolInputs.length) {
        val input = symbolInputs.item(i) as? HTMLInputElement
        if (input != null) {
            symbols.add(input.value.uppercase())
        }
    }
    val hasUsd = symbols.contains(Asset.USD)

    val isSuccess = isValid && hasUsd
    totalDisplay.classList.toggle(CssClass.Utility.Live.value, isSuccess)
    totalDisplay.classList.toggle(CssClass.Utility.Delayed.value, !isSuccess)
    saveButton.disabled = !isSuccess
}

fun addAssetRow() {
    val symbolInput = document.getElementById(HtmlIds.NEW_SYMBOL_INPUT) as? HTMLInputElement ?: return
    val symbol = symbolInput.value.trim().uppercase()
    if (symbol.isEmpty()) return
    if (!SYMBOL_REGEX.matches(symbol)) {
        window.alert(ViewText.INVALID_SYMBOL_ALERT)
        return
    }

    val symbolInputs = document.querySelectorAll(SYMBOL_INPUTS_QUERY)
    val existingSymbols = mutableListOf<String>()
    for (i in 0 until symbolInputs.length) {
        val input = symbolInputs.item(i) as? HTMLInputElement
        if (input != null) {
            existingSymbols.add(input.value.uppercase())
        }
    }

    if (existingSymbols.contains(symbol)) {
        window.alert(ViewText.SYMBOL_EXISTS_ALERT)
        return
    }

    val container = document.getElementById(HtmlIds.ALLOCATIONS_CONTAINER) ?: return
    val row = document.createElement("div") as HTMLDivElement
    row.className = CssClass.Form.AllocationEditRow.value

    row.innerHTML = """
        <div class="${CssClass.Form.AllocationEditSymbol.value} symbol-label">$symbol</div>
        <input type="hidden" name="${FormFields.SYMBOLS}" value="$symbol">
        <div class="${CssClass.Form.AllocationEditInputWrapper.value}">
            <input type="number" step="0.1" name="${FormFields.TARGETS}" class="${CssClass.Form.InputGlass.value}" value="0.0" oninput="updateAllocationTotal()">
            <span class="${CssClass.Form.PercentSuffix.value}">%</span>
        </div>
        <button type="button" class="${CssClass.Button.Danger.value}" onclick="this.closest('.${CssClass.Form.AllocationEditRow.value}').remove(); updateAllocationTotal();">${ViewText.REMOVE}</button>
    """.trimIndent()

    container.appendChild(row)
    symbolInput.value = ""
    updateAllocationTotal()
}

private const val TARGET_INPUTS_QUERY = "input[name=\"${FormFields.TARGETS}\"]"
private const val SYMBOL_INPUTS_QUERY = "input[name=\"${FormFields.SYMBOLS}\"]"
private val SYMBOL_REGEX = Regex("^[A-Z0-9]{1,16}$")

fun Double.toFixed(digits: Int): String {
    return this.asDynamic().toFixed(digits).toString()
}
