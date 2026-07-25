package com.gemini.krakenbot.view.util

import com.gemini.krakenbot.config.Settings
import com.gemini.krakenbot.view.util.Icons.icon
import kotlinx.html.*

fun FlowContent.glassPanel(title: String, iconSvg: String? = null, block: DIV.() -> Unit) {
    div(CssClass.Layout.GlassPanel) {
        h2(CssClass.Utility.GlassPanelTitle) {
            if (iconSvg != null) {
                icon(iconSvg)
            }
            +title
        }
        block()
    }
}

enum class ActiveNav {
    DASHBOARD,
    HISTORY,
    SETTINGS,
}

fun FlowContent.brandMark() {
    h1(CssClass.Layout.BrandMark) {
        span(CssClass.Layout.BrandPrimary) { +ViewText.APP_BRAND_PRIMARY }
        +" "
        span(CssClass.Layout.BrandAccent) { +ViewText.APP_BRAND_ACCENT }
    }
}

/**
 * GLOB-1/DASH-2: persistent trading-mode plate shown next to the brand on every
 * page. Simulation and dry-run read as safe (blue/amber); live trading reads as
 * high-consequence (red) so the current mode is identifiable within ~1s.
 */
fun FlowContent.modePlate(settings: Settings) {
    val cssClass: CssClass
    val label: String
    val plateTitle: String
    when {
        settings.simulation -> {
            cssClass = CssClass.Mode.Simulation
            label = ViewText.MODE_SIMULATION
            plateTitle = ViewText.MODE_SIMULATION_TITLE
        }
        settings.dryRun -> {
            cssClass = CssClass.Mode.DryRun
            label = ViewText.MODE_DRY_RUN
            plateTitle = ViewText.MODE_DRY_RUN_TITLE
        }
        else -> {
            cssClass = CssClass.Mode.Live
            label = ViewText.MODE_LIVE
            plateTitle = ViewText.MODE_LIVE_TITLE
        }
    }
    span(cssClass) {
        id = HtmlIds.MODE_PLATE
        attributes[HtmlAttrs.TITLE] = plateTitle
        span(CssClass.Mode.Dot) {}
        span {
            id = HtmlIds.MODE_PLATE_LABEL
            +label
        }
    }
}

/**
 * Dashboard-only placeholder for the STREAM/STALE chip. Lives in the shell so
 * the header cluster is complete before the HTMX fragment arrives; the fragment
 * refreshes this node via `hx-swap-oob`.
 */
fun FlowContent.streamStatusPlaceholder() {
    div(CssClass.Layout.HeaderStatus) {
        id = HtmlIds.HEADER_STATUS
        div(CssClass.StatusCard.Live) { +ViewText.STREAM }
        span(CssClass.DataAge.Value) { +"…" }
        span(CssClass.DataAge.Time) {}
    }
}

/**
 * Standard header brand + mode plate group used across all pages (DASH-2).
 * Pass [includeStreamSlot] on the Dashboard shell so STREAM sits beside the
 * mode plate (not on a separate right-aligned row).
 */
fun FlowContent.brandWithMode(settings: Settings, includeStreamSlot: Boolean = false) {
    div(CssClass.Layout.HeaderTitleSection) {
        brandMark()
        modePlate(settings)
        if (includeStreamSlot) {
            streamStatusPlaceholder()
        }
    }
}

fun FlowContent.primaryNav(active: ActiveNav) {
    nav(CssClass.Navigation.Bar) {
        a(
            cssClass = if (active == ActiveNav.DASHBOARD) CssClass.Navigation.LinkActive else CssClass.Navigation.Link,
            href = Routes.ROOT,
        ) {
            +ViewText.NAV_DASHBOARD
        }
        a(
            cssClass = if (active == ActiveNav.HISTORY) CssClass.Navigation.LinkActive else CssClass.Navigation.Link,
            href = Routes.HISTORY,
        ) {
            +ViewText.NAV_HISTORY
        }
        a(
            cssClass = if (active == ActiveNav.SETTINGS) CssClass.Navigation.LinkActive else CssClass.Navigation.Link,
            href = Routes.SETTINGS,
        ) {
            +ViewText.NAV_SETTINGS
        }
    }
}

fun FlowContent.statusCard(
    title: String,
    iconSvg: String,
    value: String,
    isSuccess: Boolean = false,
    valueId: String? = null,
    titleId: String? = null,
    block: (DIV.() -> Unit)? = null,
) {
    val cardClass = if (isSuccess) CssClass.StatusCard.Success else CssClass.StatusCard.Default
    div(cardClass) {
        div(CssClass.StatusCard.Header) {
            span(CssClass.StatusCard.Title) {
                if (titleId != null) id = titleId
                +title
            }
            div(CssClass.StatusCard.Icon) { icon(iconSvg) }
        }
        div(CssClass.StatusCard.Value) {
            if (valueId != null) {
                id = valueId
            }
            +value
        }
        if (block != null) {
            div(CssClass.StatusCard.Sub) { block() }
        }
    }
}

fun DIV.formSection(title: String, iconSvg: String, block: DIV.() -> Unit) {
    div(CssClass.Form.Section) {
        h3(CssClass.Form.SectionTitle) {
            icon(iconSvg)
            +title
        }
        block()
    }
}

fun DIV.formGroup(label: String, block: DIV.() -> Unit) {
    div(CssClass.Form.Group) {
        label(CssClass.Form.Label) { +label }
        block()
    }
}
