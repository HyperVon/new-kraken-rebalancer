package com.gemini.krakenbot.frontend

import com.gemini.krakenbot.model.Asset
import com.gemini.krakenbot.util.PrecisionConstants
import com.gemini.krakenbot.view.util.AllocationEditor
import com.gemini.krakenbot.view.util.ChartProps
import com.gemini.krakenbot.view.util.CssClass
import com.gemini.krakenbot.view.util.FormFields
import com.gemini.krakenbot.view.util.HealthStatusKeys
import com.gemini.krakenbot.view.util.HtmlAttrs
import com.gemini.krakenbot.view.util.HtmlEvents
import com.gemini.krakenbot.view.util.HtmlIds
import com.gemini.krakenbot.view.util.Routes
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
private val submittedSettingsSnapshots = js("new WeakMap()")
private var latestSubmittedSettingsRequestId = 0L
private var activeModePlateRefreshXhr: dynamic = null
private const val MODE_PLATE_REFRESH_INTERVAL_MS = 1000
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
    val detail = event.asDynamic().detail ?: return
    val requestConfig = detail.requestConfig
    val path = (requestConfig?.path as? String)
        ?: target.getAttribute("hx-post")
        ?: target.getAttribute("hx-get")
        ?: return
    if (isModePlateRefreshRequest(target, path, requestConfig)) {
        activeModePlateRefreshXhr = detail.xhr
        return
    }
    if (requestConfig?.verb?.toString()?.equals("post", ignoreCase = true) == false) return
    if (path.substringBefore('?').trimEnd('/') != Routes.SETTINGS.trimEnd('/')) return
    val xhr = detail.xhr ?: return
    submittedSettingsSnapshots.set(
        xhr,
        SubmittedSettingsSnapshot(
            requestId = ++latestSubmittedSettingsRequestId,
            values = settingsSnapshot(form),
            modeLabel = settingsModeLabel(form),
        ),
    )
}

private fun settingsModeLabel(form: HTMLFormElement): String {
    val simulation = form.querySelector("input[name='${FormFields.SIMULATION}']") as? HTMLInputElement
    if (simulation?.checked == true) return ViewText.MODE_SIMULATION
    val dryRun = form.querySelector("input[name='${FormFields.DRY_RUN}']") as? HTMLInputElement
    return if (dryRun?.checked == true) ViewText.MODE_DRY_RUN else ViewText.MODE_LIVE
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
    val submitted = submittedSettingsSnapshots.get(xhr) as? SubmittedSettingsSnapshot ?: return
    submittedSettingsSnapshots.delete(xhr)
    val contentType = xhr.getResponseHeader("Content-Type") as? String ?: return
    if (!contentType.substringBefore(';').trim().equals("application/json", ignoreCase = true)) return

    detail.shouldSwap = false
    if (submitted.requestId != latestSubmittedSettingsRequestId) return
    if ((xhr.status as? Int)?.let { it in 200..299 } != true) return

    // The raw js() expression below refers to this local by name; Kotlin cannot validate that reference.
    val responseText = xhr.responseText as? String ?: return
    val response = try {
        js("JSON.parse(responseText)")
    } catch (_: Throwable) {
        return
    }
    val configPublicationPending = response.configPublicationPending as? Boolean ?: return
    val savedAt = response.savedAt as? String ?: return
    val activeMode = response.activeMode as? String

    form.setAttribute(SettingsState.BASELINE_ATTRIBUTE, submitted.values)
    val status = document.getElementById(SettingsState.SAVE_STATUS_ID) as? HTMLElement ?: return
    status.textContent = if (configPublicationPending) {
        SettingsState.SAVED_APPLIES_AFTER_CURRENT_CYCLE
    } else {
        SettingsState.SAVED_AND_ACTIVE
    }
    status.setAttribute("datetime", savedAt)
    status.classList.remove(CssClass.Utility.Hidden.value)
    cancelActiveModePlateRefresh()
    if (!configPublicationPending) activeMode?.let(::updateModePlate)
    if (configPublicationPending) refreshModePlateAfterSave(submitted)
    // Edits made while the request was in flight are not covered by the acknowledgement.
    updateSettingsState()
}

internal fun updateModePlate(activeMode: String) {
    val plate = document.getElementById(HtmlIds.MODE_PLATE) as? HTMLElement ?: return
    val label = document.getElementById(HtmlIds.MODE_PLATE_LABEL) as? HTMLElement ?: return
    val (className, text, title) = when (activeMode) {
        HealthStatusKeys.MODE_SIMULATION -> Triple(
            CssClass.Mode.Simulation.value,
            ViewText.MODE_SIMULATION,
            ViewText.MODE_SIMULATION_TITLE,
        )

        HealthStatusKeys.MODE_DRY_RUN -> Triple(
            CssClass.Mode.DryRun.value,
            ViewText.MODE_DRY_RUN,
            ViewText.MODE_DRY_RUN_TITLE,
        )

        HealthStatusKeys.MODE_LIVE -> Triple(
            CssClass.Mode.Live.value,
            ViewText.MODE_LIVE,
            ViewText.MODE_LIVE_TITLE,
        )

        else -> return
    }
    plate.setAttribute("class", className)
    plate.setAttribute(HtmlAttrs.TITLE, title)
    label.textContent = text
}

private fun refreshModePlateAfterSave(submitted: SubmittedSettingsSnapshot) {
    if (submitted.requestId != latestSubmittedSettingsRequestId) return
    val htmx = window.asDynamic().htmx ?: return
    val options = js("({ target: 'body', swap: 'none' })")
    options.source = document.getElementById(SettingsState.FORM_ID)
    val request: dynamic = htmx.ajax(
        "GET",
        Routes.FRAGMENT_DASHBOARD,
        options,
    )
    request.then({ _: dynamic ->
        if (modePlateIsOutOfDate(submitted)) scheduleModePlateRefresh(submitted)
        null
    }).catch({ _: dynamic ->
        if (modePlateIsOutOfDate(submitted)) scheduleModePlateRefresh(submitted)
        null
    })
}

private fun isModePlateRefreshRequest(target: Element, path: String, requestConfig: dynamic): Boolean =
    target.id == SettingsState.FORM_ID &&
        requestConfig?.verb?.toString()?.equals("get", ignoreCase = true) == true &&
        path.substringBefore('?').trimEnd('/') == Routes.FRAGMENT_DASHBOARD.trimEnd('/')

private fun cancelActiveModePlateRefresh() {
    val xhr = activeModePlateRefreshXhr ?: return
    activeModePlateRefreshXhr = null
    xhr.abort()
}

private fun modePlateIsOutOfDate(submitted: SubmittedSettingsSnapshot): Boolean {
    if (submitted.requestId != latestSubmittedSettingsRequestId) return false
    return document.getElementById(HtmlIds.MODE_PLATE_LABEL)?.textContent?.trim() != submitted.modeLabel
}

private fun scheduleModePlateRefresh(submitted: SubmittedSettingsSnapshot) {
    window.setTimeout({ refreshModePlateAfterSave(submitted) }, MODE_PLATE_REFRESH_INTERVAL_MS)
}

private data class SubmittedSettingsSnapshot(val requestId: Long, val values: String, val modeLabel: String)

private fun NodeList.forEachInput(action: (HTMLInputElement) -> Unit) {
    repeat(length) { index ->
        (item(index) as? HTMLInputElement)?.let(action)
    }
}

private val SYMBOL_REGEX = Regex(Asset.SYMBOL_PATTERN_STRING)

fun Double.toFixed(digits: Int): String = this.asDynamic().toFixed(digits).toString()
