package com.gemini.krakenbot.view.util

import kotlin.test.Test
import kotlin.test.assertEquals

class RoutesTest {
    @Test
    fun encodesReservedAndUtf8QueryValues() {
        assertEquals(
            "/api?search=caf%C3%A9%20%26%20tea%2Fcoffee",
            "/api".withQuery("search", "café & tea/coffee"),
        )
    }

    @Test
    fun appendsToExistingQueryWithoutChangingStructure() {
        assertEquals(
            "/api?existing=1&next=a%3Db%3Fc",
            "/api?existing=1".withQuery("next", "a=b?c"),
        )
    }
}
