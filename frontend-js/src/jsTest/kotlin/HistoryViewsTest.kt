package com.gemini.krakenbot.frontend

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLSelectElement
import org.w3c.dom.events.Event

class HistoryViewsTest :
    StringSpec({
        "saved queries include only display filters and safely encode their values" {
            val query = "?range=7d&asset=XBT&pair=XBT%2FUSD&type=deposit&tradesOffset=50&ledgersOffset=100"
            historyViewQuery(query) shouldBe
                "range=7d&asset=XBT&pair=XBT%2FUSD&type=deposit"
            historyViewQuery("?redirect=https://example.com&asset=%3Cscript%3E&range=") shouldBe "asset=%3Cscript%3E"
            historyViewQuery("") shouldBe ""
            historyViewQuery("https://example.com/") shouldBe ""
        }

        "corrupt saved data is ignored and repeated names keep only their latest safe query" {
            listOf(null, "", "broken", "{}", "null").forEach { parseHistoryViews(it) shouldBe emptyList() }
            parseHistoryViews(
                """[null,{}, {"name":" ","query":"range=7d"}, {"name":4,"query":""},
                {"name":"Week","query":"range=7d&tradesOffset=80"},
                {"name":"Week","query":"range=30d&url=https://example.com"},
                {"name":"Empty","query":""}]""",
            ) shouldBe listOf(SavedHistoryView("Week", "range=30d"), SavedHistoryView("Empty", ""))
        }

        "save replace select and delete round trip without storing table pagination" {
            val root = historyViewsFixture()
            try {
                var storage: String? = null
                var name: String? = " Week "
                var query = "?range=7d&asset=XBT&tradesOffset=50"
                var destination = ""
                initHistoryViews(
                    read = { storage },
                    write = { storage = it },
                    prompt = { name },
                    navigate = { destination = it },
                    currentQuery = { query },
                )
                val select = root.querySelector("select") as HTMLSelectElement
                val save = root.querySelector("#history-save-view") as HTMLButtonElement
                val delete = root.querySelector("#history-delete-view") as HTMLButtonElement
                delete.disabled shouldBe true
                root.hidden shouldBe false
                save.click()
                parseHistoryViews(storage) shouldBe listOf(SavedHistoryView("Week", "range=7d&asset=XBT"))
                select.value shouldBe "Week"
                delete.disabled shouldBe false
                query = "?range=30d&type=deposit"
                save.click()
                parseHistoryViews(storage) shouldBe listOf(SavedHistoryView("Week", "range=30d&type=deposit"))
                select.options.length shouldBe 6
                select.dispatchEvent(Event("change"))
                destination shouldBe "/history?range=30d&type=deposit"
                select.value = ""
                select.dispatchEvent(Event("change"))
                delete.disabled shouldBe true
                name = null
                save.click()
                name = "  "
                save.click()
                select.options.length shouldBe 6
                select.value = "Week"
                select.dispatchEvent(Event("change"))
                delete.click()
                parseHistoryViews(storage) shouldBe emptyList()
                delete.disabled shouldBe true
                select.value shouldBe ""
                var readAgain = false
                initHistoryViews(read = {
                    readAgain = true
                    null
                })
                readAgain shouldBe false
            } finally {
                root.remove()
            }
        }

        "a matching saved view stays selected after navigation and can be deleted" {
            val root = historyViewsFixture()
            try {
                var storage = """[{"name":"Week","query":"range=7d&asset=XBT"}]"""
                root.hidden shouldBe true
                initHistoryViews(
                    read = { storage },
                    write = { storage = it },
                    currentQuery = { "?asset=XBT&tradesOffset=50&range=7d" },
                )
                root.hidden shouldBe false
                (root.querySelector("select") as HTMLSelectElement).value shouldBe "Week"
                val delete = root.querySelector("#history-delete-view") as HTMLButtonElement
                delete.disabled shouldBe false
                delete.click()
                parseHistoryViews(storage) shouldBe emptyList()
                delete.disabled shouldBe true
            } finally {
                root.remove()
            }
        }

        "blocked storage preserves in-memory views and saved names are rendered as text" {
            val root = historyViewsFixture()
            try {
                var destination = ""
                initHistoryViews(
                    read = { error("blocked") },
                    write = { error("blocked") },
                    prompt = { "<img src=x>" },
                    navigate = { destination = it },
                    currentQuery = { "?redirect=https://example.com" },
                )
                (root.querySelector("#history-save-view") as HTMLButtonElement).click()
                root.querySelector("img") shouldBe null
                val select = root.querySelector("select") as HTMLSelectElement
                select.value shouldBe "<img src=x>"
                select.dispatchEvent(Event("change"))
                destination shouldBe "/history"
                (root.querySelector("#history-delete-view") as HTMLButtonElement).click()
                select.options.length shouldBe 5
            } finally {
                root.remove()
            }
        }

        "default browser storage saves the current filters and restores them on reload" {
            val key = "kraken-history-views-v1"
            val previous = window.localStorage.getItem(key)
            val originalPrompt = window.asDynamic().prompt
            var root = historyViewsFixture()
            try {
                window.localStorage.removeItem(key)
                window.asDynamic().prompt = { _: dynamic -> "Browser view" }
                initHistoryViews()
                (root.querySelector("#history-save-view") as HTMLButtonElement).click()
                parseHistoryViews(window.localStorage.getItem(key)) shouldBe listOf(
                    SavedHistoryView("Browser view", historyViewQuery(window.location.search)),
                )
                root.remove()
                root = historyViewsFixture()
                initHistoryViews()
                (root.querySelector("select") as HTMLSelectElement).value shouldBe "Browser view"
                val delete = root.querySelector("#history-delete-view") as HTMLButtonElement
                delete.disabled shouldBe false
                delete.click()
                parseHistoryViews(window.localStorage.getItem(key)) shouldBe emptyList()
            } finally {
                root.remove()
                window.asDynamic().prompt = originalPrompt
                if (previous ==
                    null
                ) {
                    window.localStorage.removeItem(key)
                } else {
                    window.localStorage.setItem(key, previous)
                }
            }
        }

        "built-in presets are read-only and land on their range and section" {
            val root = historyViewsFixture()
            try {
                var destination = ""
                initHistoryViews(
                    read = { null },
                    write = { },
                    prompt = { null },
                    navigate = { destination = it },
                    currentQuery = { "?range=24h" },
                )
                val select = root.querySelector("select") as HTMLSelectElement
                select.options.length shouldBe 5
                select.value = "preset:Week · Allocation"
                select.dispatchEvent(Event("change"))
                destination shouldBe "/history?range=7d#history-allocation-section"
                (root.querySelector("#history-delete-view") as HTMLButtonElement).disabled shouldBe true
                select.value = "preset:Month · Net Cash Flow"
                select.dispatchEvent(Event("change"))
                destination shouldBe "/history?range=30d#history-cash-flow-section"
            } finally {
                root.remove()
            }
        }

        "set as default applies the chosen view on a bare page load only" {
            var root = historyViewsFixture()
            try {
                var storage: String? = null
                var destination = ""
                initHistoryViews(
                    read = { storage },
                    write = { storage = it },
                    prompt = { "Week" },
                    navigate = { destination = it },
                    currentQuery = { "?range=7d" },
                )
                (root.querySelector("#history-save-view") as HTMLButtonElement).click()
                (root.querySelector("#history-set-default-view") as HTMLButtonElement).click()
                parseHistoryViewStore(storage).defaultOption shouldBe "Week"
                root.remove()
                root = historyViewsFixture()
                initHistoryViews(
                    read = { storage },
                    write = { storage = it },
                    navigate = { destination = it },
                    currentQuery = { "?range=30d" },
                )
                destination shouldBe ""
                root.remove()
                root = historyViewsFixture()
                initHistoryViews(
                    read = { storage },
                    write = { storage = it },
                    navigate = { destination = it },
                    currentQuery = { "" },
                )
                destination shouldBe "/history?range=7d"
            } finally {
                root.remove()
            }
        }

        "initialization tolerates unrelated or partially rendered pages" {
            initHistoryViews(read = { error("must not access storage") })
            val root = document.createElement("div") as HTMLElement
            root.id = "history-saved-views"
            document.body!!.appendChild(root)
            try {
                initHistoryViews(read = { error("must not access storage") })
                root.innerHTML = "<select id='history-saved-view'></select>"
                initHistoryViews(read = { error("must not access storage") })
                root.innerHTML += "<button id='history-save-view'></button>"
                initHistoryViews(read = { error("must not access storage") })
            } finally {
                root.remove()
            }
        }
    })

private fun historyViewsFixture(): HTMLElement {
    val root = document.createElement("div") as HTMLElement
    root.id = "history-saved-views"
    root.hidden = true
    root.innerHTML = """
        <select id="history-saved-view"></select>
        <button id="history-save-view">Save view</button>
        <button id="history-set-default-view">Set as default</button>
        <button id="history-delete-view">Delete view</button>
    """.trimIndent()
    document.body!!.appendChild(root)
    return root
}
