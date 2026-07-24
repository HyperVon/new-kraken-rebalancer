package com.gemini.krakenbot.client

import kotlinx.browser.document
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class FrontendJsTest {

    @Test
    fun shouldRenderStatusBadgeElement() {
        val badge = document.createElement("span")
        badge.id = "status-badge"
        badge.textContent = "ACTIVE"
        document.body?.appendChild(badge)

        val foundElement = document.getElementById("status-badge")
        assertNotNull(foundElement)
        assertEquals("ACTIVE", foundElement.textContent)

        document.body?.removeChild(badge)
    }
}
