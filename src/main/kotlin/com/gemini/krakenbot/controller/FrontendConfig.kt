package com.gemini.krakenbot.controller

import com.gemini.krakenbot.config.Allocation
import com.gemini.krakenbot.config.Settings

data class FrontendConfig(
    val settings: Settings,
    val allocations: List<Allocation>
)
