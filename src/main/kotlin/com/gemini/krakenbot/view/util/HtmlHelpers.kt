package com.gemini.krakenbot.view.util

import com.gemini.krakenbot.view.css.CssStyles
import kotlinx.html.HEAD
import kotlinx.html.link
import kotlinx.html.meta

fun HEAD.commonMetadataAndStyles() {
    meta(charset = "utf-8")
    meta(
        name = "viewport",
        content = "width=device-width, initial-scale=1.0",
    )
    link(rel = "preconnect", href = CdnUrls.GOOGLE_FONTS_PRECONNECT)
    link(rel = "preconnect", href = CdnUrls.GOOGLE_FONTS_GSTATIC_PRECONNECT) {
        attributes[HtmlAttrs.CROSSORIGIN] = ""
    }
    link(rel = "stylesheet", href = CdnUrls.GOOGLE_FONTS_STYLESHEET)
    // CSS is served with a 24h max-age (see configureCaching). The content-derived ?v= keeps that
    // cache useful yet forces a refetch when rules change — stale CSS shows as native white controls.
    val stylesheetVersion = CssStyles.stylesheet.toString().hashCode()
    link(rel = "stylesheet", href = "${Routes.STATIC_STYLE_CSS}?v=$stylesheetVersion")
}

/** Cache-busted `/static/rebalancer.js` URL (content hash when the resource is on the classpath). */
fun rebalancerJsSrc(): String {
    val version =
        object {}
            .javaClass
            .getResourceAsStream("/${Routes.STATIC_RESOURCES_DIR}/rebalancer.js")
            ?.use { it.readBytes().contentHashCode() }
            // Missing classpath resource: wall-clock still forces a unique URL.
            ?: System.currentTimeMillis().toInt()
    return "${Routes.STATIC_REBALANCER_JS}?v=$version"
}
