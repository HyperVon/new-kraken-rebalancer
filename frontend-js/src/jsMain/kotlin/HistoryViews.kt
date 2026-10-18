package com.gemini.krakenbot.frontend

import com.gemini.krakenbot.view.util.HtmlIds
import com.gemini.krakenbot.view.util.Routes
import com.gemini.krakenbot.view.util.ViewText
import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLOptionElement
import org.w3c.dom.HTMLSelectElement
import kotlin.js.json

private const val HISTORY_VIEWS_STORAGE_KEY = "kraken-history-views-v1"
private const val PRESET_OPTION_PREFIX = "preset:"

internal data class SavedHistoryView(val name: String, val query: String)

/** Read-only built-in view: display filters plus the section it lands on. */
internal data class HistoryViewPreset(val name: String, val query: String, val anchor: String)

internal data class HistoryViewStore(val views: List<SavedHistoryView>, val defaultOption: String?)

internal fun historyViewPresets(): List<HistoryViewPreset> = listOf(
    HistoryViewPreset(ViewText.HISTORY_VIEW_OVERVIEW, "range=30d", HtmlIds.HISTORY_PORTFOLIO_SECTION),
    HistoryViewPreset(ViewText.HISTORY_VIEW_DAY_TOTAL, "range=24h", HtmlIds.HISTORY_PORTFOLIO_SECTION),
    HistoryViewPreset(ViewText.HISTORY_VIEW_WEEK_ALLOCATION, "range=7d", HtmlIds.HISTORY_ALLOCATION_SECTION),
    HistoryViewPreset(ViewText.HISTORY_VIEW_MONTH_NET_CASH_FLOW, "range=30d", HtmlIds.HISTORY_CASH_FLOW_SECTION),
)

/** Only History's display filters are persisted; pagination and destinations never enter a saved view. */
internal fun historyViewQuery(query: String): String {
    val source: dynamic = js("new URLSearchParams(query)")
    val target: dynamic = js("new URLSearchParams()")
    listOf("range", "asset", "pair", "type").forEach { key ->
        val value = source.get(key) as? String
        if (!value.isNullOrBlank()) target.set(key, value)
    }
    return target.toString()
}

internal fun parseHistoryViewStore(raw: String?): HistoryViewStore {
    if (raw.isNullOrBlank()) return HistoryViewStore(emptyList(), null)
    return try {
        val parsed: dynamic = JSON.parse<dynamic>(raw)
        if (parsed == null || parsed == undefined) return HistoryViewStore(emptyList(), null)
        val nested = parsed.views
        val source: dynamic = if (nested != null && nested != undefined) nested else parsed
        val defaultOption = (parsed.default as? String)?.takeIf { it.isNotBlank() }
        val views = mutableListOf<SavedHistoryView>()
        dynamicList(source).forEach { entry ->
            val name = (entry?.name as? String)?.trim()
            val query = entry?.query as? String
            if (!name.isNullOrEmpty() && query != null) {
                views.removeAll { it.name == name }
                views += SavedHistoryView(name, historyViewQuery(query))
            }
        }
        HistoryViewStore(views, defaultOption)
    } catch (_: Throwable) {
        HistoryViewStore(emptyList(), null)
    }
}

internal fun parseHistoryViews(raw: String?): List<SavedHistoryView> = parseHistoryViewStore(raw).views

private fun dynamicList(value: dynamic): List<dynamic> {
    if (value == null || value == undefined) return emptyList()
    val length = (value.length as? Number)?.toInt() ?: return emptyList()
    return (0 until length).map { index -> value[index] }
}

private fun presetOptionValue(name: String): String = PRESET_OPTION_PREFIX + name

private fun HistoryViewPreset.destination(): String {
    val target = if (query.isEmpty()) "" else "?$query"
    return Routes.HISTORY + target + if (anchor.isEmpty()) "" else "#$anchor"
}

private fun SavedHistoryView.destination(): String {
    val query = historyViewQuery(query)
    return Routes.HISTORY + if (query.isEmpty()) "" else "?$query"
}

/** Browser storage is optional: controls still work for this visit when persistence is unavailable. */
internal fun initHistoryViews(
    read: () -> String? = { window.localStorage.getItem(HISTORY_VIEWS_STORAGE_KEY) },
    write: (String) -> Unit = { window.localStorage.setItem(HISTORY_VIEWS_STORAGE_KEY, it) },
    prompt: () -> String? = { window.prompt(ViewText.HISTORY_NAME_VIEW) },
    navigate: (String) -> Unit = { window.location.assign(it) },
    currentQuery: () -> String = { window.location.search },
) {
    val root = document.getElementById(HtmlIds.HISTORY_SAVED_VIEWS) as? HTMLElement ?: return
    if (root.asDynamic().historyViewsInitialized == true) return
    val select = document.getElementById(HtmlIds.HISTORY_SAVED_VIEW) as? HTMLSelectElement ?: return
    val save = document.getElementById(HtmlIds.HISTORY_SAVE_VIEW) as? HTMLButtonElement ?: return
    val setDefault = document.getElementById(HtmlIds.HISTORY_SET_DEFAULT_VIEW) as? HTMLButtonElement ?: return
    val delete = document.getElementById(HtmlIds.HISTORY_DELETE_VIEW) as? HTMLButtonElement ?: return
    root.asDynamic().historyViewsInitialized = true
    val presets = historyViewPresets()
    val store = try {
        parseHistoryViewStore(read())
    } catch (_: Throwable) {
        HistoryViewStore(emptyList(), null)
    }
    val views = store.views.toMutableList()
    var defaultOption = store.defaultOption
    fun destinationFor(option: String): String? = when {
        option.startsWith(PRESET_OPTION_PREFIX) ->
            presets.firstOrNull { it.name == option.removePrefix(PRESET_OPTION_PREFIX) }?.destination()

        option.isNotEmpty() -> views.firstOrNull { it.name == option }?.destination()

        else -> null
    }
    fun render(selected: String = "") {
        select.innerHTML = ""
        val placeholder = document.createElement("option") as HTMLOptionElement
        placeholder.value = ""
        placeholder.textContent = ViewText.HISTORY_CURRENT_VIEW
        select.appendChild(placeholder)
        presets.forEach { preset ->
            val option = document.createElement("option") as HTMLOptionElement
            option.value = presetOptionValue(preset.name)
            option.textContent = preset.name
            select.appendChild(option)
        }
        views.forEach { view ->
            val option = document.createElement("option") as HTMLOptionElement
            option.value = view.name
            option.textContent = view.name
            select.appendChild(option)
        }
        select.value = selected
        delete.disabled = selected.isEmpty() || selected.startsWith(PRESET_OPTION_PREFIX)
        setDefault.disabled = selected.isEmpty()
    }
    fun persist() {
        try {
            write(
                JSON.stringify(
                    json(
                        "views" to views.map { json("name" to it.name, "query" to it.query) }.toTypedArray(),
                        "default" to defaultOption,
                    ),
                ),
            )
        } catch (_: Throwable) {
            // Keep this visit's saved views usable even when browser storage is blocked or full.
        }
    }
    val currentViewQuery = historyViewQuery(currentQuery())
    if (currentViewQuery.isEmpty()) {
        val landing = defaultOption?.let(::destinationFor)
        if (landing != null && landing != Routes.HISTORY) {
            navigate(landing)
            return
        }
    }
    render(
        views.firstOrNull { it.query == currentViewQuery }?.name
            ?: presets.firstOrNull { it.query == currentViewQuery }?.let { presetOptionValue(it.name) }.orEmpty(),
    )
    save.addEventListener("click", {
        val name = prompt()?.trim()
        if (!name.isNullOrEmpty()) {
            views.removeAll { it.name == name }
            views += SavedHistoryView(name, historyViewQuery(currentQuery()))
            persist()
            render(name)
        }
    })
    select.addEventListener("change", {
        render(select.value)
        destinationFor(select.value)?.let(navigate)
    })
    setDefault.addEventListener("click", {
        if (select.value.isEmpty()) return@addEventListener
        defaultOption = select.value
        persist()
    })
    delete.addEventListener("click", {
        val removed = select.value
        views.removeAll { it.name == removed }
        if (defaultOption == removed) defaultOption = null
        persist()
        render()
    })
    root.hidden = false
}
