package com.gemini.krakenbot.frontend

import com.gemini.krakenbot.model.TimeRange
import com.gemini.krakenbot.view.util.ChartProps
import com.gemini.krakenbot.view.util.HistoryViewIds
import com.gemini.krakenbot.view.util.HtmlEvents
import com.gemini.krakenbot.view.util.HtmlIds
import com.gemini.krakenbot.view.util.HtmlTags
import com.gemini.krakenbot.view.util.ViewText
import kotlinx.browser.document
import kotlinx.browser.localStorage
import kotlinx.browser.window
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.HTMLOptionElement
import org.w3c.dom.HTMLSelectElement
import kotlin.js.Date
import kotlin.js.Json
import kotlin.js.Promise
import kotlin.js.json

@JsName("Object")
private external object PrefsObject {
    fun keys(obj: dynamic): Array<String>
}

/** One named History view: range + dry-run + per-chart series visibility. */
data class HistoryViewDef(
    val id: String,
    val name: String,
    val builtIn: Boolean,
    val range: String,
    val showDryRun: Boolean,
    /** canvasId → (label → visible). May include [ChartProps.DATASET_VISIBILITY_DEFAULT]. */
    val visibility: Map<String, Map<String, Boolean>>,
)

data class HistoryViewsStore(val defaultId: String, val views: List<HistoryViewDef>)

/**
 * Browser-local History view presets (built-ins + user saves).
 * Persistence key: [ViewText.HISTORY_VIEWS_STORAGE_KEY].
 */
object HistoryViewPrefs {
    /** JSON payload keys for [HistoryViewsStore] persistence. Shared by writer/reader. */
    internal object StoreKeys {
        const val DEFAULT_ID = "defaultId"
        const val VIEWS = "views"
        const val ID = "id"
        const val NAME = "name"
        const val BUILT_IN = "builtIn"
        const val RANGE = "range"
        const val SHOW_DRY_RUN = "showDryRun"
        const val VISIBILITY = "visibility"
    }

    // Old ids persisted in localStorage by earlier builds; migrated on load so saved presets
    // and default selections survive the rename to the net-cash-flow chart.
    private const val LEGACY_MONTH_PNL_ID = "month-pnl"
    private const val LEGACY_CUMULATIVE_PL_CHART = "cumulative-pl-chart"

    private var userInteracted = false

    internal fun hasUserInteracted(): Boolean = userInteracted

    internal fun resetInteractionState() {
        userInteracted = false
    }

    internal fun setHasUserInteracted(value: Boolean) {
        userInteracted = value
    }

    /**
     * Marks the current controls as diverged from a named preset. The temporary
     * option prevents the selector from claiming that a preset is still active.
     */
    internal fun markCurrentViewModified() {
        userInteracted = true
        val select = document.getElementById(HtmlIds.HISTORY_VIEWS_SELECT) as? HTMLSelectElement
        if (select != null) {
            val existing = select.querySelector("${HtmlTags.OPTION}[value='']") as? HTMLOptionElement
            if (existing != null) {
                existing.selected = true
            } else {
                val option = document.createElement(HtmlTags.OPTION) as HTMLOptionElement
                option.value = ""
                option.textContent = ViewText.HISTORY_VIEW_UNSAVED
                option.selected = true
                select.insertBefore(option, select.firstChild)
            }
        }
        (document.getElementById(HtmlIds.HISTORY_SET_DEFAULT_BTN) as? HTMLButtonElement)?.disabled = true
        (document.getElementById(HtmlIds.HISTORY_DELETE_VIEW_BTN) as? HTMLButtonElement)?.disabled = true
        try {
            HistorySessionState.save()
        } catch (_: Throwable) {
        }
    }

    fun builtInViews(): List<HistoryViewDef> = listOf(
        HistoryViewDef(
            id = HistoryViewIds.OVERVIEW,
            name = ViewText.HISTORY_VIEW_OVERVIEW,
            builtIn = true,
            range = TimeRange.THIRTY_DAYS.key,
            showDryRun = true,
            visibility = emptyMap(),
        ),
        HistoryViewDef(
            id = HistoryViewIds.DAY_TOTAL,
            name = ViewText.HISTORY_VIEW_DAY_TOTAL,
            builtIn = true,
            range = TimeRange.TWENTY_FOUR_HOURS.key,
            showDryRun = true,
            visibility =
            mapOf(
                HtmlIds.PORTFOLIO_VALUE_CHART to
                    mapOf(
                        ChartProps.DATASET_VISIBILITY_DEFAULT to false,
                        ViewText.TOTAL_PORTFOLIO to true,
                    ),
            ),
        ),
        HistoryViewDef(
            id = HistoryViewIds.WEEK_ALLOCATION,
            name = ViewText.HISTORY_VIEW_WEEK_ALLOCATION,
            builtIn = true,
            range = TimeRange.SEVEN_DAYS.key,
            showDryRun = true,
            visibility = emptyMap(),
        ),
        HistoryViewDef(
            id = HistoryViewIds.MONTH_NET_CASH_FLOW,
            name = ViewText.HISTORY_VIEW_MONTH_NET_CASH_FLOW,
            builtIn = true,
            range = TimeRange.THIRTY_DAYS.key,
            showDryRun = false,
            visibility = emptyMap(),
        ),
    )

    fun defaultStore(): HistoryViewsStore =
        HistoryViewsStore(defaultId = HistoryViewIds.OVERVIEW, views = builtInViews())

    fun mergeBuiltIns(store: HistoryViewsStore): HistoryViewsStore {
        val builtIns = builtInViews()
        val builtInIds = builtIns.map { it.id }.toSet()
        val userViews = store.views.filter { !it.builtIn && it.id !in builtInIds }
        val migratedDefaultId =
            when (store.defaultId) {
                LEGACY_MONTH_PNL_ID -> HistoryViewIds.MONTH_NET_CASH_FLOW
                else -> store.defaultId
            }
        val resolvedDefault =
            if (userViews.any { it.id == migratedDefaultId } || builtInIds.contains(migratedDefaultId)) {
                migratedDefaultId
            } else {
                HistoryViewIds.OVERVIEW
            }
        return HistoryViewsStore(defaultId = resolvedDefault, views = builtIns + userViews)
    }

    fun loadStore(): HistoryViewsStore {
        val raw = localStorage.getItem(ViewText.HISTORY_VIEWS_STORAGE_KEY) ?: return defaultStore()
        return try {
            mergeBuiltIns(parseStore(JSON.parse(raw)))
        } catch (_: Throwable) {
            defaultStore()
        }
    }

    fun saveStore(store: HistoryViewsStore) {
        localStorage.setItem(ViewText.HISTORY_VIEWS_STORAGE_KEY, JSON.stringify(storeToJson(store)))
    }

    fun parseStore(raw: dynamic): HistoryViewsStore {
        val defaultId = (raw[StoreKeys.DEFAULT_ID] as? String) ?: HistoryViewIds.OVERVIEW
        val viewsArr = raw[StoreKeys.VIEWS]
        val views = mutableListOf<HistoryViewDef>()
        if (viewsArr != null && viewsArr != undefined) {
            val length: Int = (viewsArr.length).unsafeCast<Int>()
            for (i in 0 until length) {
                parseView(viewsArr[i])?.let { views.add(it) }
            }
        }
        return HistoryViewsStore(defaultId = defaultId, views = views)
    }

    private fun parseView(raw: dynamic): HistoryViewDef? {
        if (raw == null || raw == undefined) return null
        val id = raw[StoreKeys.ID] as? String ?: return null
        val name = raw[StoreKeys.NAME] as? String ?: return null
        val builtIn = (raw[StoreKeys.BUILT_IN] as? Boolean) ?: false
        // Views written before range became explicit remain valid; malformed non-null ranges
        // are still rejected so they cannot select an undefined API window.
        val range = when (val rawRange = raw[StoreKeys.RANGE]) {
            null, undefined -> TimeRange.THIRTY_DAYS.key
            !is String -> return null
            else -> TimeRange.entries.firstOrNull { it.key.equals(rawRange, ignoreCase = true) }?.key ?: return null
        }
        val showDryRun = (raw[StoreKeys.SHOW_DRY_RUN] as? Boolean) ?: true
        val visibility = parseVisibility(raw[StoreKeys.VISIBILITY])
        return HistoryViewDef(
            id = id,
            name = name,
            builtIn = builtIn,
            range = range,
            showDryRun = showDryRun,
            visibility = visibility,
        )
    }

    private fun parseVisibility(raw: dynamic): Map<String, Map<String, Boolean>> {
        if (raw == null || raw == undefined) return emptyMap()
        val outer = mutableMapOf<String, Map<String, Boolean>>()
        val canvasIds = PrefsObject.keys(raw)
        for (canvasId in canvasIds) {
            val resolvedCanvasId =
                if (canvasId == LEGACY_CUMULATIVE_PL_CHART) {
                    HtmlIds.CUMULATIVE_NET_CASH_FLOW_CHART
                } else {
                    canvasId
                }
            val innerRaw = raw[canvasId]
            val inner = mutableMapOf<String, Boolean>()
            if (innerRaw != null && innerRaw != undefined) {
                val labels = PrefsObject.keys(innerRaw)
                for (label in labels) {
                    val v = innerRaw[label]
                    if (v is Boolean) {
                        inner[label] = v
                    }
                }
            }
            outer[resolvedCanvasId] = inner
        }
        return outer
    }

    fun storeToJson(store: HistoryViewsStore): Json {
        val views =
            store.views
                .map { view ->
                    json(
                        StoreKeys.ID to view.id,
                        StoreKeys.NAME to view.name,
                        StoreKeys.BUILT_IN to view.builtIn,
                        StoreKeys.RANGE to view.range,
                        StoreKeys.SHOW_DRY_RUN to view.showDryRun,
                        StoreKeys.VISIBILITY to visibilityToJson(view.visibility),
                    )
                }.toTypedArray()
        return json(StoreKeys.DEFAULT_ID to store.defaultId, StoreKeys.VIEWS to views)
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

    fun captureCurrentView(name: String, id: String = "user-${Date.now().toLong()}"): HistoryViewDef {
        val showDryRun =
            (document.getElementById(HtmlIds.SHOW_DRY_RUN_CHECKBOX) as? HTMLInputElement)?.checked ?: true
        return HistoryViewDef(
            id = id,
            name = name,
            builtIn = false,
            range = historyCurrentRange(),
            showDryRun = showDryRun,
            visibility = historyCaptureVisibility(),
        )
    }

    fun applyView(id: String): Promise<Unit> {
        val store = loadStore()
        val view = store.views.firstOrNull { it.id == id } ?: return Promise.resolve(Unit)
        historyApplyVisibility(view.visibility)
        val checkbox = document.getElementById(HtmlIds.SHOW_DRY_RUN_CHECKBOX) as? HTMLInputElement
        if (checkbox != null) {
            checkbox.checked = view.showDryRun
        }
        syncTimeRangeButtons(view.range)
        refreshSelect(store, selectedId = view.id)
        updateDeleteEnabled(view)
        try {
            HistorySessionState.save()
        } catch (_: Throwable) {
        }
        return loadAll(view.range)
    }

    fun applyDefaultView(): Promise<Unit> {
        val store = loadStore()
        return applyView(store.defaultId)
    }

    fun initToolbar() {
        resetInteractionState()
        val store = loadStore()
        refreshSelect(store, selectedId = store.defaultId)
        val selected = store.views.firstOrNull { it.id == store.defaultId }
        if (selected != null) updateDeleteEnabled(selected)

        val select = document.getElementById(HtmlIds.HISTORY_VIEWS_SELECT) as? HTMLSelectElement
        select?.addEventListener(HtmlEvents.CHANGE, {
            val id = select.value
            if (id.isNotBlank()) {
                userInteracted = true
                applyView(id)
            }
        })

        (document.getElementById(HtmlIds.HISTORY_SAVE_VIEW_BTN) as? HTMLButtonElement)?.addEventListener(
            HtmlEvents.CLICK,
            {
                val name = window.prompt(ViewText.HISTORY_SAVE_VIEW_PROMPT)?.trim().orEmpty()
                if (name.isEmpty()) return@addEventListener
                val captured = captureCurrentView(name)
                val current = loadStore()
                val updated =
                    current.copy(views = current.views + captured, defaultId = current.defaultId)
                saveStore(updated)
                refreshSelect(updated, selectedId = captured.id)
                updateDeleteEnabled(captured)
                try {
                    HistorySessionState.save()
                } catch (_: Throwable) {
                }
            },
        )

        (document.getElementById(HtmlIds.HISTORY_SET_DEFAULT_BTN) as? HTMLButtonElement)?.addEventListener(
            HtmlEvents.CLICK,
            {
                val selectEl =
                    document.getElementById(HtmlIds.HISTORY_VIEWS_SELECT) as? HTMLSelectElement
                        ?: return@addEventListener
                val id = selectEl.value
                if (id.isBlank()) return@addEventListener
                val current = loadStore()
                if (current.views.none { it.id == id }) return@addEventListener
                val updated = current.copy(defaultId = id)
                saveStore(updated)
                refreshSelect(updated, selectedId = id)
                try {
                    HistorySessionState.save()
                } catch (_: Throwable) {
                }
            },
        )

        (document.getElementById(HtmlIds.HISTORY_DELETE_VIEW_BTN) as? HTMLButtonElement)?.addEventListener(
            HtmlEvents.CLICK,
            {
                val selectEl =
                    document.getElementById(HtmlIds.HISTORY_VIEWS_SELECT) as? HTMLSelectElement
                        ?: return@addEventListener
                val id = selectEl.value
                val current = loadStore()
                val view = current.views.firstOrNull { it.id == id } ?: return@addEventListener
                if (view.builtIn) return@addEventListener
                val remaining = current.views.filter { it.id != id }
                val newDefault =
                    if (current.defaultId == id) HistoryViewIds.OVERVIEW else current.defaultId
                val updated = HistoryViewsStore(defaultId = newDefault, views = remaining)
                saveStore(mergeBuiltIns(updated))
                applyView(newDefault)
            },
        )
    }

    fun refreshSelect(store: HistoryViewsStore, selectedId: String) {
        val select = document.getElementById(HtmlIds.HISTORY_VIEWS_SELECT) as? HTMLSelectElement ?: return
        select.innerHTML = ""
        for ((id, name) in store.views) {
            val option = document.createElement(HtmlTags.OPTION) as HTMLOptionElement
            option.value = id
            option.textContent =
                if (id == store.defaultId) {
                    "$name ★"
                } else {
                    name
                }
            if (id == selectedId) {
                option.selected = true
            }
            select.appendChild(option)
        }
        store.views.firstOrNull { it.id == selectedId }?.let { updateDeleteEnabled(it) }
    }

    private fun updateDeleteEnabled(view: HistoryViewDef) {
        val btn = document.getElementById(HtmlIds.HISTORY_DELETE_VIEW_BTN) as? HTMLButtonElement ?: return
        btn.disabled = view.builtIn
        (document.getElementById(HtmlIds.HISTORY_SET_DEFAULT_BTN) as? HTMLButtonElement)?.disabled = false
    }
}
