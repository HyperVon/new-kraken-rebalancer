package com.gemini.krakenbot.view.util

import com.gemini.krakenbot.model.TimeRange

fun String.withRange(range: TimeRange): String = withRange(range.key)
fun String.withRange(rangeKey: String): String = withQuery(QueryParamKeys.RANGE, rangeKey)
fun String.withQuery(key: String, value: Any): String = "$this?$key=$value"
