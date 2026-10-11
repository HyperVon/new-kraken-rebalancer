package com.gemini.krakenbot.frontend

import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.browser.document
import kotlin.js.Date

/**
 * Example Kotest StringSpec for `:frontend-js` — prefer this shape over kotlin.test.
 * Production specs live under `frontend-js/src/jsTest/kotlin/`.
 */
class FrontendJsTest : StringSpec() {
    override fun isolationMode() = IsolationMode.InstancePerTest

    init {
        "fresh dashboard snapshot shows the STREAM chip" {
            val container = document.createElement("div")
            container.innerHTML = """
                <span class="data-age-value"></span>
                <time class="data-age-time" data-epoch="${Date.now()}"></time>
                <div id="header-status"><span class="status-badge">STREAM</span></div>
            """.trimIndent()
            document.body?.appendChild(container)

            try {
                updateAge()
                val status = document.getElementById("header-status")
                status.shouldNotBeNull()
                status.id shouldBe "header-status"
                status.querySelector(".status-badge")?.textContent shouldBe "STREAM"
            } finally {
                document.body?.removeChild(container)
            }
        }
    }
}
