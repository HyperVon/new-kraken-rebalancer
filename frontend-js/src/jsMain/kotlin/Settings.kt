package com.gemini.krakenbot.frontend

import com.gemini.krakenbot.model.Asset
import com.gemini.krakenbot.util.PrecisionConstants
import com.gemini.krakenbot.view.util.ChartProps
import com.gemini.krakenbot.view.util.CssClass
import com.gemini.krakenbot.view.util.FormFields
import com.gemini.krakenbot.view.util.HtmlAttrs
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
    // Mark each input as bound on first attachment so subsequent htmx:afterSwap callbacks
    // (which can re-run this when the toggles survive a partial fragment swap) do not stack
    // duplicate change listeners. The marker lives on the element itself, so detached inputs
    // are GC'd normally without retaining references from a module-level set.
    val onChange: (Event) -> Unit = { syncModePlateFromSafetyToggles() }
    if (simulation != null && !simulation.dataset.isBound()) {
        simulation.markBound()
        simulation.addEventListener("change", onChange)
    }
    if (dryRun != null && !dryRun.dataset.isBound()) {
        dryRun.markBound()
        dryRun.addEventListener("change", onChange)
    }
    syncModePlateFromSafetyToggles()
}

private const val BOUND_MARKER = "modeToggleBound"

private fun org.w3c.dom.DOMStringMap.isBound(): Boolean = this[BOUND_MARKER] == "1"

private fun HTMLInputElement.markBound() {
    this.dataset[BOUND_MARKER] = "1"
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
    repeat(inputs.length) { i ->
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

    // Mirror the server-side alias canonicalization (BTC↔XBT, DOGE↔XDG) so an alias of an existing
    // allocation is rejected in the form instead of only at save time.
    val canonical = Asset.canonicalSymbol(symbol)
    if (currentAllocationSymbols().contains(canonical)) {
        window.alert(ViewText.SYMBOL_EXISTS_ALERT)
        return
    }

    val container = document.getElementById(HtmlIds.ALLOCATIONS_CONTAINER) ?: return
    val row = document.createDiv()
    row.className = CssClass.Form.AllocationEditRow.toString()

    val symbolDiv = document.createDiv()
    symbolDiv.className = CssClass.Form.AllocationEditSymbol.toString()
    symbolDiv.textContent = canonical

    val symbolHidden = document.createInput()
    symbolHidden.type = "hidden"
    symbolHidden.name = FormFields.SYMBOLS
    symbolHidden.value = canonical

    val colorHidden = document.createInput()
    colorHidden.type = "hidden"
    colorHidden.name = FormFields.COLORS
    colorHidden.className = CssClass.Form.AllocationColorInput.toString()

    val colorLabel = document.createLabel()
    val colorPicker = document.createInput()
    colorPicker.type = "color"
    colorPicker.className = CssClass.Form.AllocationColorSwatch.toString()
    colorPicker.value = pickColorForNewAsset()
    colorHidden.value = colorPicker.value
    colorPicker.oninput = { colorHidden.value = colorPicker.value }
    colorLabel.appendChild(colorPicker)

    val inputWrapper = document.createDiv()
    inputWrapper.className = CssClass.Form.AllocationEditInputWrapper.toString()

    val numberInput = document.createInput()
    numberInput.type = "number"
    numberInput.step = PrecisionConstants.ALLOCATION_STEP_PERCENT.toString()
    numberInput.min = PrecisionConstants.ALLOCATION_MIN_PERCENT.toString()
    numberInput.max = PrecisionConstants.TOTAL_ALLOCATION_PERCENTAGE.toString()
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
    row.appendChild(symbolHidden)
    row.appendChild(colorHidden)
    row.appendChild(colorLabel)
    row.appendChild(inputWrapper)
    row.appendChild(removeBtn)

    container.appendChild(row)
    symbolInput.value = ""
    updateAllocationTotal()
}

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
    repeat(inputs.length) { i ->
        val input = inputs.item(i) as? HTMLInputElement
        if (input != null && input.value.isNotEmpty()) {
            colors.add(input.value)
        }
    }
    return colors
}

/** Canonical symbols from all allocation symbol inputs currently in the DOM. */
private fun currentAllocationSymbols(): List<String> {
    val symbolInputs = document.querySelectorAll(SYMBOL_INPUTS_QUERY)
    val symbols = mutableListOf<String>()
    repeat(symbolInputs.length) { i ->
        val input = symbolInputs.item(i) as? HTMLInputElement
        if (input != null) {
            symbols.add(Asset.canonicalSymbol(input.value.uppercase()))
        }
    }
    return symbols
}

private val SYMBOL_REGEX = Regex(Asset.SYMBOL_PATTERN_STRING)

fun Double.toFixed(digits: Int): String = this.asDynamic().toFixed(digits).toString()
