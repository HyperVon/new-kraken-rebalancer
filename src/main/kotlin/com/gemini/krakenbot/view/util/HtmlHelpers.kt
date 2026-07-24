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
    link(rel = "preconnect", href = "https://fonts.googleapis.com")
    link(rel = "preconnect", href = "https://fonts.gstatic.com") {
        attributes[HtmlAttrs.CROSSORIGIN] = ""
    }
    link(
        rel = "stylesheet",
        href = "https://fonts.googleapis.com/css2?family=Inter:wght@300;400;500;600;700;800&family=Outfit:wght@400;500;600;700;800&family=Roboto+Mono:wght@400;500;700&display=swap",
    )
    // CSS responses are cached for 24 hours. A content-derived version keeps
    // that cache useful while forcing clients to fetch changed rules on deploy.
    val stylesheetVersion = CssStyles.stylesheet.toString().hashCode()
    link(rel = "stylesheet", href = "${Routes.STATIC_STYLE_CSS}?v=$stylesheetVersion")
}
