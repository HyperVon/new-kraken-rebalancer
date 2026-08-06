package com.gemini.krakenbot.frontend

import com.gemini.krakenbot.model.Asset
import com.gemini.krakenbot.util.PrecisionConstants
import com.gemini.krakenbot.view.util.AllocationEditor
import com.gemini.krakenbot.view.util.ChartProps
import com.gemini.krakenbot.view.util.CssClass
import com.gemini.krakenbot.view.util.FormFields
import com.gemini.krakenbot.view.util.HtmlIds
import com.gemini.krakenbot.view.util.ViewText
import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.*
import kotlin.math.abs
import com.gemini.krakenbot.view.util.HtmlQueries.SYMBOL_INPUTS as SYMBOL_INPUTS_QUERY
import com.gemini.krakenbot.view.util.HtmlQueries.TARGET_INPUTS as TARGET_INPUTS_QUERY

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
    inputs.forEachInput { input ->
        total += input.value.toDoubleOrNull() ?: 0.0
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

    // Mirror the server-side alias canonicalization (BTC↔XBT, DOGE↔XDG) so an alias of an existing
    // allocation is rejected in the form instead of only at save time.
    val canonical = Asset.canonicalSymbol(symbol)
    if (currentAllocationSymbols().contains(canonical)) {
        window.alert(ViewText.SYMBOL_EXISTS_ALERT)
        return
    }

    val container = document.getElementById(HtmlIds.ALLOCATIONS_CONTAINER) ?: return
    container.insertAdjacentHTML(
        "beforeend",
        AllocationEditor.editRow(canonical, pickColorForNewAsset(), DEFAULT_NEW_ALLOCATION_TARGET),
    )
    symbolInput.value = ""
    updateAllocationTotal()
}

private const val DEFAULT_NEW_ALLOCATION_TARGET = "0.0"

private val COLOR_PALETTE_CANDIDATES = arrayOf(
    ChartProps.SOLID_BLUE,
    ChartProps.SOLID_EMERALD,
    ChartProps.SOLID_RED,
    ChartProps.SOLID_TEAL,
    ChartProps.SOLID_ORANGE,
    ChartProps.SOLID_FUCHSIA,
    ChartProps.SOLID_YELLOW,
    ChartProps.SOLID_SKY,
)

private fun pickColorForNewAsset(): String {
    val used = currentAllocationColors().toSet()
    val free = COLOR_PALETTE_CANDIDATES.filterNot { it in used }
    if (free.isNotEmpty()) return free.first()
    return COLOR_PALETTE_CANDIDATES[used.size % COLOR_PALETTE_CANDIDATES.size]
}

private fun currentAllocationColors(): List<String> {
    val inputs = document.querySelectorAll(".${CssClass.Form.AllocationColorInput}")
    val colors = mutableListOf<String>()
    inputs.forEachInput { input ->
        if (input.value.isNotEmpty()) {
            colors.add(input.value)
        }
    }
    return colors
}

/** Canonical symbols from all allocation symbol inputs currently in the DOM. */
private fun currentAllocationSymbols(): List<String> {
    val symbolInputs = document.querySelectorAll(SYMBOL_INPUTS_QUERY)
    val symbols = mutableListOf<String>()
    symbolInputs.forEachInput { input ->
        symbols.add(Asset.canonicalSymbol(input.value.uppercase()))
    }
    return symbols
}

private fun NodeList.forEachInput(action: (HTMLInputElement) -> Unit) {
    repeat(length) { index ->
        (item(index) as? HTMLInputElement)?.let(action)
    }
}

private val SYMBOL_REGEX = Regex(Asset.SYMBOL_PATTERN_STRING)

fun Double.toFixed(digits: Int): String = this.asDynamic().toFixed(digits).toString()
