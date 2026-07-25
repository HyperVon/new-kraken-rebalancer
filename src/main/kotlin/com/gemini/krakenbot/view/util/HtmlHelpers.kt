package com.gemini.krakenbot.view.util

import com.gemini.krakenbot.view.css.CssStyles
import kotlinx.html.HEAD
import kotlinx.html.link
import kotlinx.html.meta

/**
 * Renders the common viewport, charset, fonts, and stylesheet link tags
 * used across all pages of the application.
 */
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
    // CSS responses are cached for 24 hours. A content-derived version keeps
    // that cache useful while forcing clients to fetch changed rules on deploy.
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
            ?: System.currentTimeMillis().toInt()
    return "${Routes.STATIC_REBALANCER_JS}?v=$version"
}
