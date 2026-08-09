package com.gemini.krakenbot.frontend

import com.gemini.krakenbot.model.TimeRange
import com.gemini.krakenbot.view.util.HtmlIds
import com.gemini.krakenbot.view.util.ViewText
import kotlinx.browser.document
import kotlinx.browser.sessionStorage
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.HTMLSelectElement
import kotlin.js.Json
import kotlin.js.json

@JsName("Object")
private external object SessionPrefsObject {
    fun keys(obj: dynamic): Array<String>
}

data class HistorySessionData(
    val range: String,
    val showDryRun: Boolean,
    val visibility: Map<String, Map<String, Boolean>>,
    val selectedViewId: String,
    val hasUserInteracted: Boolean,
)

object HistorySessionState {

    fun save() {
        try {
            val range = try {
                historyCurrentRange()
            } catch (_: Throwable) {
                TimeRange.THIRTY_DAYS.key
            }
            val showDryRun =
                (document.getElementById(HtmlIds.SHOW_DRY_RUN_CHECKBOX) as? HTMLInputElement)?.checked ?: true
            val selectedViewId =
                (document.getElementById(HtmlIds.HISTORY_VIEWS_SELECT) as? HTMLSelectElement)?.value ?: ""
            val hasUserInteracted = HistoryViewPrefs.hasUserInteracted()
            val visibility = captureCurrentVisibility()
            val payload = json(
                "range" to range,
                "showDryRun" to showDryRun,
                "visibility" to visibilityToJson(visibility),
                "selectedViewId" to selectedViewId,
                "hasUserInteracted" to hasUserInteracted,
            )
            sessionStorage.setItem(ViewText.HISTORY_SESSION_STORAGE_KEY, JSON.stringify(payload))
        } catch (_: Throwable) {
        }
    }

    fun load(): HistorySessionData? {
        try {
            val raw = sessionStorage.getItem(ViewText.HISTORY_SESSION_STORAGE_KEY) ?: return null

            @Suppress("UNCHECKED_CAST_TO_EXTERNAL_INTERFACE")
            val parsed = JSON.parse<dynamic>(raw)
            val range = parsed.range as? String ?: return null
            if (TimeRange.entries.none { it.key == range }) return null
            val showDryRun = parsed.showDryRun as? Boolean ?: true
            val selectedViewId = parsed.selectedViewId as? String ?: ""
            val hasUserInteracted = parsed.hasUserInteracted as? Boolean ?: false
            val visibility = parseVisibility(parsed.visibility)
            return HistorySessionData(range, showDryRun, visibility, selectedViewId, hasUserInteracted)
        } catch (_: Throwable) {
            return null
        }
    }

    fun clear() {
        try {
            sessionStorage.removeItem(ViewText.HISTORY_SESSION_STORAGE_KEY)
        } catch (_: Throwable) {
        }
    }

    fun restoreIfNeeded(): Boolean {
        val data = load() ?: return false
        try {
            // Checkbox must be set before chart building reads it.
            (document.getElementById(HtmlIds.SHOW_DRY_RUN_CHECKBOX) as? HTMLInputElement)?.let {
                it.checked = data.showDryRun
            }
            // Visibility: seed visibilityStates before loadAll so createOrUpdate applies it.
            if (data.visibility.isNotEmpty()) {
                historyApplyVisibility(data.visibility)
            }
            // Range buttons
            syncTimeRangeButtons(data.range)
            // Views selector: reflect saved selection, or unsaved if diverged.
            val store = HistoryViewPrefs.loadStore()
            val hasValidSelection = data.selectedViewId.isNotBlank() &&
                store.views.any { it.id == data.selectedViewId }
            if (hasValidSelection) {
                HistoryViewPrefs.refreshSelect(store, selectedId = data.selectedViewId)
                store.views.firstOrNull { it.id == data.selectedViewId }?.let {
                    val deleteBtn = document.getElementById(
                        HtmlIds.HISTORY_DELETE_VIEW_BTN,
                    ) as? HTMLButtonElement
                    if (deleteBtn != null) {
                        deleteBtn.disabled = it.builtIn
                    }
                    val defaultBtn = document.getElementById(
                        HtmlIds.HISTORY_SET_DEFAULT_BTN,
                    ) as? HTMLButtonElement
                    if (defaultBtn != null) defaultBtn.disabled = false
                }
                HistoryViewPrefs.setHasUserInteracted(true)
            } else {
                if ((document.getElementById(HtmlIds.HISTORY_VIEWS_SELECT) as? HTMLSelectElement)?.options?.length ==
                    0
                ) {
                    HistoryViewPrefs.refreshSelect(store, selectedId = store.defaultId)
                }
                HistoryViewPrefs.markCurrentViewModified()
            }
        } catch (_: Throwable) {
            return false
        }
        return true
    }

    internal fun captureCurrentVisibility(): Map<String, Map<String, Boolean>> {
        // For session persistence we store the logical visibilityStates as-is.
        // Live chart captures are already handled by createOrUpdate (which snapshots
        // legend toggles before destroying the old chart) and by the custom legend
        // onClick handler, so we do not need to re-capture from live charts here.
        // Capturing live here would overwrite a preset's "*" default with an
        // explicit per-label map, breaking the expected stored form.
        return visibilityStates.mapValues { it.value.toMap() }
    }

    private fun parseVisibility(raw: dynamic): Map<String, Map<String, Boolean>> {
        if (raw == null || raw == undefined) return emptyMap()
        val outer = mutableMapOf<String, Map<String, Boolean>>()
        try {
            val canvasIds = SessionPrefsObject.keys(raw)
            for (canvasId in canvasIds) {
                val innerRaw = raw[canvasId]
                val inner = mutableMapOf<String, Boolean>()
                if (innerRaw != null && innerRaw != undefined) {
                    val labels = SessionPrefsObject.keys(innerRaw)
                    for (label in labels) {
                        val v = innerRaw[label]
                        if (v is Boolean) {
                            inner[label] = v
                        }
                    }
                }
                outer[canvasId] = inner
            }
        } catch (_: Throwable) {
        }
        return outer
    }

    private fun visibilityToJson(visibility: Map<String, Map<String, Boolean>>): Json {
        val result: dynamic = js("{}")
        for ((canvasId, labels) in visibility) {
            val inner: dynamic = js("{}")
            for ((label, visible) in labels) {
                inner[label] = visible
            }
            result[canvasId] = inner
        }
        return result.unsafeCast<Json>()
    }
}
