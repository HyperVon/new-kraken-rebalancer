package com.gemini.krakenbot.frontend

import com.gemini.krakenbot.model.Asset
import com.gemini.krakenbot.util.PrecisionConstants
import com.gemini.krakenbot.view.util.AllocationEditor
import com.gemini.krakenbot.view.util.ChartProps
import com.gemini.krakenbot.view.util.CssClass
import com.gemini.krakenbot.view.util.FormFields
import com.gemini.krakenbot.view.util.HtmlEvents
import com.gemini.krakenbot.view.util.HtmlIds
import com.gemini.krakenbot.view.util.SettingsState
import com.gemini.krakenbot.view.util.ViewText
import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.*
import org.w3c.dom.events.Event
import kotlin.js.js
import kotlin.math.abs
import com.gemini.krakenbot.view.util.HtmlQueries.SYMBOL_INPUTS as SYMBOL_INPUTS_QUERY
import com.gemini.krakenbot.view.util.HtmlQueries.TARGET_INPUTS as TARGET_INPUTS_QUERY

private val SETTINGS_FIELD_NAMES = setOf(
    FormFields.LOOP_DELAY_SECONDS,
    FormFields.DEVIATION_TRIGGER_PERCENT,
    FormFields.MINIMUM_ORDER_SIZE_USD,
    FormFields.SIMULATION,
    FormFields.DRY_RUN,
    FormFields.SYMBOLS,
    FormFields.COLORS,
    FormFields.SCORES,
    FormFields.TARGETS,
    FormFields.SCORE_EMPHASIS,
    FormFields.SCORE_SLEEVE_PERCENT,
)

private var settingsResponseListenerInstalled = false
private var beforeUnloadListenerInstalled = false
private var submittedSettingsSnapshot: String? = null
private val beforeUnloadListener: (Event) -> Unit = { event ->
    event.preventDefault()
    event.asDynamic().returnValue = ""
}

fun initSettings() {
    registerSettingsGlobals()
    initializeSettingsForm()
    updateAllocationTotal()
}

fun registerSettingsGlobals() {
    window.asDynamic().updateAllocationTotal = { updateAllocationTotal() }
    window.asDynamic().addAssetRow = { addAssetRow() }
    if (!settingsResponseListenerInstalled) {
        document.addEventListener(HtmlEvents.HTMX_BEFORE_SWAP, ::handleSettingsSaveResponse)
        document.addEventListener(HtmlEvents.HTMX_BEFORE_REQUEST, ::captureSubmittedSettingsSnapshot)
        settingsResponseListenerInstalled = true
    }
}

/**
 * Remembers the settings the operator submitted. A response acknowledges this snapshot only, so
 * edits made while the request was in flight stay unsaved.
 */
private fun captureSubmittedSettingsSnapshot(event: Event) {
    val target = event.asDynamic().target as? Element ?: return
    val form = document.getElementById(SettingsState.FORM_ID) as? HTMLFormElement ?: return
    if (target.id != SettingsState.FORM_ID && !form.contains(target)) return
    submittedSettingsSnapshot = settingsSnapshot(form)
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
    updateSettingsState()
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
        HtmlEvents.INSERT_ADJACENT_BEFORE_END,
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
    val inputs = document.querySelectorAll(CssClass.Form.AllocationColorInput.querySelector)
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

private fun initializeSettingsForm() {
    val form = document.getElementById(SettingsState.FORM_ID) as? HTMLFormElement
    if (form == null) {
        updateLeaveConfirmation(false)
        return
    }

    if (form.asDynamic().settingsStateListeners != true) {
        form.addEventListener("input", { updateSettingsState() })
        form.addEventListener("change", { updateSettingsState() })
        form.asDynamic().settingsStateListeners = true
    }
    updateSettingsState()
}

private fun updateSettingsState() {
    val form = document.getElementById(SettingsState.FORM_ID) as? HTMLFormElement
    val baseline = form?.getAttribute(SettingsState.BASELINE_ATTRIBUTE)
    if (form == null || baseline == null) {
        updateLeaveConfirmation(false)
        return
    }

    val dirty = settingsSnapshot(form) != baseline
    updateLeaveConfirmation(dirty)

    val status = document.getElementById(SettingsState.SAVE_STATUS_ID) as? HTMLElement ?: return
    if (dirty) {
        status.textContent = SettingsState.UNSAVED_CHANGES
        status.removeAttribute("datetime")
        status.classList.remove(CssClass.Utility.Hidden.value)
    } else if (status.textContent == SettingsState.UNSAVED_CHANGES) {
        status.textContent = ""
        status.removeAttribute("datetime")
        status.classList.add(CssClass.Utility.Hidden.value)
    }
}

private fun settingsSnapshot(form: HTMLFormElement): String = buildString {
    val inputs = form.querySelectorAll("input")
    repeat(inputs.length) { index ->
        val input = inputs.item(index) as? HTMLInputElement ?: return@repeat
        if (input.name !in SETTINGS_FIELD_NAMES) return@repeat

        val value = if (input.type == "checkbox") input.checked.toString() else input.value
        append(input.name.length).append(':').append(input.name)
        append(value.length).append(':').append(value)
    }
}

private fun updateLeaveConfirmation(dirty: Boolean) {
    if (beforeUnloadListenerInstalled == dirty) return
    if (dirty) {
        window.addEventListener("beforeunload", beforeUnloadListener)
    } else {
        window.removeEventListener("beforeunload", beforeUnloadListener)
    }
    beforeUnloadListenerInstalled = dirty
}

private fun handleSettingsSaveResponse(event: Event) {
    val form = document.getElementById(SettingsState.FORM_ID) as? HTMLFormElement ?: return
    val detail = event.asDynamic().detail ?: return
    val xhr = detail.xhr ?: return
    val contentType = xhr.getResponseHeader("Content-Type") as? String ?: return
    if (!contentType.substringBefore(';').trim().equals("application/json", ignoreCase = true)) return

    detail.shouldSwap = false
    if ((xhr.status as? Int)?.let { it in 200..299 } != true) return

    val responseText = xhr.responseText as? String ?: return
    val response = try {
        js("JSON.parse(responseText)")
    } catch (_: Throwable) {
        return
    }
    val configPublicationPending = response.configPublicationPending as? Boolean ?: return
    val savedAt = response.savedAt as? String ?: return

    val submitted = submittedSettingsSnapshot
    submittedSettingsSnapshot = null
    form.setAttribute(SettingsState.BASELINE_ATTRIBUTE, submitted ?: settingsSnapshot(form))
    val status = document.getElementById(SettingsState.SAVE_STATUS_ID) as? HTMLElement ?: return
    status.textContent = if (configPublicationPending) {
        SettingsState.SAVED_APPLIES_AFTER_CURRENT_CYCLE
    } else {
        SettingsState.SAVED_AND_ACTIVE
    }
    status.setAttribute("datetime", savedAt)
    status.classList.remove(CssClass.Utility.Hidden.value)
    // Edits made while the request was in flight are not covered by the acknowledgement.
    updateSettingsState()
}

private fun NodeList.forEachInput(action: (HTMLInputElement) -> Unit) {
    repeat(length) { index ->
        (item(index) as? HTMLInputElement)?.let(action)
    }
}

private val SYMBOL_REGEX = Regex(Asset.SYMBOL_PATTERN_STRING)

fun Double.toFixed(digits: Int): String = this.asDynamic().toFixed(digits).toString()
