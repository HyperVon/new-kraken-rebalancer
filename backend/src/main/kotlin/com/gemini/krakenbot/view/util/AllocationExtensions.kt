package com.gemini.krakenbot.view.util

import com.gemini.krakenbot.config.Allocation

internal fun List<Allocation>.symbolColorMap(): Map<String, String> = mapNotNull { allocation ->
    allocation.color?.let { allocation.symbol.value.uppercase() to it }
}.toMap()
