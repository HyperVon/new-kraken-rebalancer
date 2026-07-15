package com.gemini.krakenbot.frontend

import io.kotest.matchers.shouldBe
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.*

@Suppress("unused")
class MainTest : StringSpec() {
    override fun isolationMode() = IsolationMode.InstancePerTest

    init {
        "initOnLoad initializes dashboard content" {
        val container = document.createElement("div")
        container.innerHTML = """
            <span class="data-age-value"></span>
            <span class="data-age-time" data-epoch="${kotlin.js.Date.now()}"></span>
            <table><thead><tr><th class="sortable">Asset</th></tr></thead><tbody></tbody></table>
        """.trimIndent()
        document.body!!.appendChild(container)
        
        try {
            // This should call updateAge and reapplySort
            initOnLoad()
            
            val ageVal = document.querySelector(".data-age-value") as HTMLElement
            ageVal.textContent!!.shouldBe("0s ago")
        } finally {
            document.body!!.removeChild(container)
        }
    }

        "initOnLoad initializes settings content" {
        val container = document.createElement("div")
        container.innerHTML = """
            <div id="total-allocated-display"></div>
            <button id="save-button"></button>
        """.trimIndent()
        document.body!!.appendChild(container)
        
        try {
            initOnLoad()
            // Check if settings globals are registered
            (window.asDynamic().addAssetRow != null) shouldBe true
        } finally {
            document.body!!.removeChild(container)
        }
    }

        "initOnLoad initializes history content" {
        val container = document.createElement("div")
        container.innerHTML = """
            <div id="portfolio-value-chart"></div>
        """.trimIndent()
        document.body!!.appendChild(container)
        
        try {
            // initHistory sets up time range buttons. We can just check if globals are there
            initOnLoad()
            (window.asDynamic().chartDefaults != null) shouldBe true
        } finally {
            document.body!!.removeChild(container)
        }
    }

        "main registers htmx event listener and interval" {
        val oldSetInterval = window.asDynamic().setInterval
        var intervalCb: (() -> Unit)? = null
        window.asDynamic().setInterval = { cb: () -> Unit, _: Int ->
            intervalCb = cb
            0
        }
        
        val container = document.createElement("div")
        container.innerHTML = """
            <span class="data-age-value"></span>
            <span class="data-age-time" data-epoch="${kotlin.js.Date.now()}"></span>
            <span class="status-badge"></span>
            <table><thead><tr><th class="sortable">Asset</th></tr></thead><tbody></tbody></table>
        """.trimIndent()
        document.body!!.appendChild(container)

        try {
            main()
            
            // 1. Verify interval callback is set and can be invoked
            intervalCb?.invoke()
            val ageVal = document.querySelector(".data-age-value") as HTMLElement
            ageVal.textContent!!.shouldBe("0s ago")
            
            // 2. Dispatch htmx:afterSwap event to verify it triggers updateAge and reapplySort
            val event = document.createEvent("Event")
            event.initEvent(type = "htmx:afterSwap", bubbles = true, cancelable = true)
            document.dispatchEvent(event)
        } finally {
            window.asDynamic().setInterval = oldSetInterval
            document.body!!.removeChild(container)
        }
    }

    "main registers DOMContentLoaded when body is null" {
        val oldSetInterval = window.asDynamic().setInterval
        window.asDynamic().setInterval = { _: () -> Unit, _: Int -> 0 }
        
        val container = document.createElement("div")
        container.innerHTML = """
            <span class="data-age-value"></span>
            <span class="data-age-time" data-epoch="${kotlin.js.Date.now()}"></span>
            <span class="status-badge"></span>
            <table><thead><tr><th class="sortable">Asset</th></tr></thead><tbody></tbody></table>
        """.trimIndent()
        document.body!!.appendChild(container)
        
        js("var oldBody = document.body; Object.defineProperty(document, 'body', { get: function() { return null; }, configurable: true });")

        try {
            main()
            
            // Dispatch DOMContentLoaded
            val event = document.createEvent("Event")
            event.initEvent(type = "DOMContentLoaded", bubbles = true, cancelable = true)
            document.dispatchEvent(event)
        } finally {
            js("Object.defineProperty(document, 'body', { get: function() { return oldBody; } });")
            window.asDynamic().setInterval = oldSetInterval
            document.body!!.removeChild(container)
        }
    }
    }
}
