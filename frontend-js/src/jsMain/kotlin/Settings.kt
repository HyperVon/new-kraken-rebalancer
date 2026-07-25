package com.gemini.krakenbot.frontend

import com.gemini.krakenbot.model.Asset
import com.gemini.krakenbot.util.PrecisionConstants
import com.gemini.krakenbot.view.util.CssClass
import com.gemini.krakenbot.view.util.FormFields
import com.gemini.krakenbot.view.util.HtmlIds
import com.gemini.krakenbot.view.util.ViewText
import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.*
import org.w3c.dom.events.Event
import kotlin.math.abs
import com.gemini.krakenbot.view.util.CssClass.Query.SYMBOL_INPUTS as SYMBOL_INPUTS_QUERY
import com.gemini.krakenbot.view.util.CssClass.Query.TARGET_INPUTS as TARGET_INPUTS_QUERY

fun initSettings() {
    registerSettingsGlobals()
    updateAllocationTotal()
    wireModePlateSync()
}

fun registerSettingsGlobals() {
    window.asDynamic().updateAllocationTotal = { updateAllocationTotal() }
    window.asDynamic().addAssetRow = { addAssetRow() }
}

/** Keep the header mode plate in sync with live safety toggles before Save. */
private fun wireModePlateSync() {
    val simulation =
        document.querySelector("input[name=\"${FormFields.SIMULATION}\"]") as? HTMLInputElement
    val dryRun =
        document.querySelector("input[name=\"${FormFields.DRY_RUN}\"]") as? HTMLInputElement
    if (simulation == null && dryRun == null) return
    val onChange: (Event) -> Unit = { syncModePlateFromSafetyToggles() }
    simulation?.addEventListener("change", onChange)
    dryRun?.addEventListener("change", onChange)
    syncModePlateFromSafetyToggles()
}

internal fun syncModePlateFromSafetyToggles() {
    val plate = document.getElementById(HtmlIds.MODE_PLATE) as? HTMLElement ?: return
    val labelEl = document.getElementById(HtmlIds.MODE_PLATE_LABEL) as? HTMLElement ?: return
    val simulation =
        (document.querySelector("input[name=\"${FormFields.SIMULATION}\"]") as? HTMLInputElement)
            ?.checked == true
    val dryRun =
        (document.querySelector("input[name=\"${FormFields.DRY_RUN}\"]") as? HTMLInputElement)
            ?.checked == true
    when {
        simulation -> {
            plate.className = CssClass.Mode.Simulation.toString()
            labelEl.textContent = ViewText.MODE_SIMULATION
            plate.title = ViewText.MODE_SIMULATION_TITLE
        }
        dryRun -> {
            plate.className = CssClass.Mode.DryRun.toString()
            labelEl.textContent = ViewText.MODE_DRY_RUN
            plate.title = ViewText.MODE_DRY_RUN_TITLE
        }
        else -> {
            plate.className = CssClass.Mode.Live.toString()
            labelEl.textContent = ViewText.MODE_LIVE
            plate.title = ViewText.MODE_LIVE_TITLE
        }
    }
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
    totalDisplay.textContent = "${ViewText.TOTAL_PREFIX}${total.toFixed(PrecisionConstants.SCALE_USD)}%"

    val saveButton = document.getElementById(HtmlIds.SAVE_BUTTON) as? HTMLButtonElement ?: return
    val isValid =
        abs(total - PrecisionConstants.TOTAL_ALLOCATION_PERCENTAGE) <= PrecisionConstants.ALLOCATION_TOLERANCE_DELTA

    val hasUsd = currentAllocationSymbols().contains(Asset.USD)

    val isSuccess = isValid && hasUsd
    totalDisplay.classList.toggle(CssClass.Form.AllocationTotalOk, isSuccess)
    totalDisplay.classList.toggle(CssClass.Form.AllocationTotalBad, !isSuccess)
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

    if (currentAllocationSymbols().contains(symbol)) {
        window.alert(ViewText.SYMBOL_EXISTS_ALERT)
        return
    }

    val container = document.getElementById(HtmlIds.ALLOCATIONS_CONTAINER) ?: return
    val row = document.createDiv()
    row.className = CssClass.Form.AllocationEditRow.toString()

    val symbolDiv = document.createDiv()
    symbolDiv.className = CssClass.Form.AllocationEditSymbol.toString()
    symbolDiv.textContent = symbol

    val hiddenInput = document.createInput()
    hiddenInput.type = "hidden"
    hiddenInput.name = FormFields.SYMBOLS
    hiddenInput.value = symbol

    val inputWrapper = document.createDiv()
    inputWrapper.className = CssClass.Form.AllocationEditInputWrapper.toString()

    val numberInput = document.createInput()
    numberInput.type = "number"
    numberInput.step = "0.1"
    numberInput.min = "0"
    numberInput.max = "100"
    numberInput.name = FormFields.TARGETS
    numberInput.className = CssClass.Form.InputGlass.toString()
    numberInput.value = "0.0"
    numberInput.oninput = { updateAllocationTotal() }

    val percentSpan = document.createSpan()
    percentSpan.className = CssClass.Form.PercentSuffix.toString()
    percentSpan.textContent = "%"

    inputWrapper.appendChild(numberInput)
    inputWrapper.appendChild(percentSpan)

    val removeBtn = document.createButton()
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

/** Uppercased symbols from all allocation symbol inputs currently in the DOM. */
private fun currentAllocationSymbols(): List<String> {
    val symbolInputs = document.querySelectorAll(SYMBOL_INPUTS_QUERY)
    val symbols = mutableListOf<String>()
    for (i in 0 until symbolInputs.length) {
        val input = symbolInputs.item(i) as? HTMLInputElement
        if (input != null) {
            symbols.add(input.value.uppercase())
        }
    }
    return symbols
}

private val SYMBOL_REGEX = Regex("^[A-Z0-9]{1,16}$")

fun Double.toFixed(digits: Int): String = this.asDynamic().toFixed(digits).toString()
