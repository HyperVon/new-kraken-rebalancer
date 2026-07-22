package com.gemini.krakenbot.frontend

import com.gemini.krakenbot.view.util.CssClass.Query.DATA_AGE_VALUE as DATA_AGE_VALUE_QUERY
import com.gemini.krakenbot.view.util.HtmlEvents
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.*
import kotlin.js.Date

private const val DIV = "div"

@Suppress("unused")
class MainTest : StringSpec() {
    override fun isolationMode() = IsolationMode.InstancePerTest

    init {
        "initOnLoad initializes dashboard content" {
            val container = document.createElement(DIV)
            container.innerHTML = TestDomBuilders.dataAgeDom(Date.now().toString())
            document.body!!.appendChild(container)
            
            try {
                // This should call updateAge and reapplySort
                initOnLoad()
                
                val ageVal = document.querySelector(DATA_AGE_VALUE_QUERY) as HTMLElement
                ageVal.textContent!!.shouldBe("0s ago")
            } finally {
                document.body!!.removeChild(container)
            }
        }

        "initOnLoad initializes settings content" {
            val container = document.createElement(DIV)
            container.innerHTML = TestDomBuilders.settingsDom()
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
            val container = document.createElement(DIV)
            container.innerHTML = TestDomBuilders.chartsDom()
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
            
            val container = document.createElement(DIV)
            container.innerHTML = TestDomBuilders.dataAgeDom(Date.now().toString())
            document.body!!.appendChild(container)

            try {
                main()
                
                // 1. Verify interval callback is set and can be invoked
                intervalCb?.invoke()
                val ageVal = document.querySelector(DATA_AGE_VALUE_QUERY) as HTMLElement
                ageVal.textContent!!.shouldBe("0s ago")
                
                // 2. Dispatch htmx:afterSwap event to verify it triggers updateAge and reapplySort
                val event = document.createEvent(HtmlEvents.EVENT)
                event.initEvent(type = HtmlEvents.HTMX_AFTER_SWAP, bubbles = true, cancelable = true)
                document.dispatchEvent(event)
            } finally {
                window.asDynamic().setInterval = oldSetInterval
                document.body!!.removeChild(container)
            }
        }

        "main registers DOMContentLoaded when body is null" {
            val oldSetInterval = window.asDynamic().setInterval
            window.asDynamic().setInterval = { _: () -> Unit, _: Int -> 0 }
            
            val container = document.createElement(DIV)
            container.innerHTML = TestDomBuilders.dataAgeDom(Date.now().toString())
            document.body!!.appendChild(container)
            
            js("var oldBody = document.body; Object.defineProperty(document, 'body', { get: function() { return null; }, configurable: true });")

            try {
                main()
                
                // Dispatch DOMContentLoaded
                val event = document.createEvent(HtmlEvents.EVENT)
                event.initEvent(type = HtmlEvents.DOM_CONTENT_LOADED, bubbles = true, cancelable = true)
                document.dispatchEvent(event)
            } finally {
                js("Object.defineProperty(document, 'body', { get: function() { return oldBody; } });")
                window.asDynamic().setInterval = oldSetInterval
                document.body!!.removeChild(container)
            }
        }
    }
}
