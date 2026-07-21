package com.gemini.krakenbot.frontend

import com.gemini.krakenbot.model.Asset
import com.gemini.krakenbot.view.util.CssClass
import com.gemini.krakenbot.view.util.CssClass.Query.SYMBOL_INPUTS as SYMBOL_INPUTS_QUERY
import com.gemini.krakenbot.view.util.CssClass.Query.TARGET_INPUTS as TARGET_INPUTS_QUERY
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
    totalDisplay.classList.toggle(CssClass.Utility.Live, isSuccess)
    totalDisplay.classList.toggle(CssClass.Utility.Delayed, !isSuccess)
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
    row.className = CssClass.Form.AllocationEditRow.toString()

    val symbolDiv = document.createElement("div") as HTMLDivElement
    symbolDiv.className = "${CssClass.Form.AllocationEditSymbol} symbol-label"
    symbolDiv.textContent = symbol

    val hiddenInput = document.createElement("input") as HTMLInputElement
    hiddenInput.type = "hidden"
    hiddenInput.name = FormFields.SYMBOLS
    hiddenInput.value = symbol

    val inputWrapper = document.createElement("div") as HTMLDivElement
    inputWrapper.className = CssClass.Form.AllocationEditInputWrapper.toString()

    val numberInput = document.createElement("input") as HTMLInputElement
    numberInput.type = "number"
    numberInput.step = "0.1"
    numberInput.name = FormFields.TARGETS
    numberInput.className = CssClass.Form.InputGlass.toString()
    numberInput.value = "0.0"
    numberInput.oninput = { updateAllocationTotal() }

    val percentSpan = document.createElement("span") as HTMLSpanElement
    percentSpan.className = CssClass.Form.PercentSuffix.toString()
    percentSpan.textContent = "%"

    inputWrapper.appendChild(numberInput)
    inputWrapper.appendChild(percentSpan)

    val removeBtn = document.createElement("button") as HTMLButtonElement
    removeBtn.type = "button"
    removeBtn.className = CssClass.Button.Danger.toString()
    removeBtn.textContent = ViewText.REMOVE
    removeBtn.onclick = {
        row.remove()
        updateAllocationTotal()
    }

    row.appendChild(symbolDiv)
    row.appendChild(hiddenInput)
    row.appendChild(inputWrapper)
    row.appendChild(removeBtn)

    container.appendChild(row)
    symbolInput.value = ""
    updateAllocationTotal()
}

private val SYMBOL_REGEX = Regex("^[A-Z0-9]{1,16}$")

fun Double.toFixed(digits: Int): String {
    return this.asDynamic().toFixed(digits).toString()
}
