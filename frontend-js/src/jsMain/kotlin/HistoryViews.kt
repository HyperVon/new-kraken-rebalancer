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

internal data class SavedHistoryView(val name: String, val query: String)

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

internal fun parseHistoryViews(raw: String?): List<SavedHistoryView> {
    if (raw.isNullOrBlank()) return emptyList()
    return try {
        val parsed: dynamic = JSON.parse<dynamic>(raw)
        if (!(js("Array.isArray(parsed)") as Boolean)) return emptyList()
        val views = mutableListOf<SavedHistoryView>()
        (parsed as Array<dynamic>).forEach { entry ->
            val name = (entry?.name as? String)?.trim()
            val query = entry?.query as? String
            if (!name.isNullOrEmpty() && query != null) {
                views.removeAll { it.name == name }
                views += SavedHistoryView(name, historyViewQuery(query))
            }
        }
        views
    } catch (_: Throwable) {
        emptyList()
    }
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
    val delete = document.getElementById(HtmlIds.HISTORY_DELETE_VIEW) as? HTMLButtonElement ?: return
    root.asDynamic().historyViewsInitialized = true
    val views = try {
        parseHistoryViews(read()).toMutableList()
    } catch (_: Throwable) {
        mutableListOf()
    }
    fun render(selected: String = "") {
        select.innerHTML = ""
        val placeholder = document.createElement("option") as HTMLOptionElement
        placeholder.value = ""
        placeholder.textContent = ViewText.HISTORY_CURRENT_VIEW
        select.appendChild(placeholder)
        views.forEach { view ->
            val option = document.createElement("option") as HTMLOptionElement
            option.value = view.name
            option.textContent = view.name
            select.appendChild(option)
        }
        select.value = selected
        delete.disabled = selected.isEmpty()
    }
    fun persist() {
        try {
            write(JSON.stringify(views.map { json("name" to it.name, "query" to it.query) }.toTypedArray()))
        } catch (_: Throwable) {
            // Keep this visit's saved views usable even when browser storage is blocked or full.
        }
    }
    val currentViewQuery = historyViewQuery(currentQuery())
    render(views.firstOrNull { it.query == currentViewQuery }?.name.orEmpty())
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
        val view = views.firstOrNull { it.name == select.value }
        delete.disabled = view == null
        if (view != null) {
            val query = historyViewQuery(view.query)
            navigate(Routes.HISTORY + if (query.isEmpty()) "" else "?$query")
        }
    })
    delete.addEventListener("click", {
        views.removeAll { it.name == select.value }
        persist()
        render()
    })
    root.hidden = false
}
