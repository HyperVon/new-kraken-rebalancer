package com.gemini.krakenbot.view.util

import com.gemini.krakenbot.config.Settings
import com.gemini.krakenbot.view.util.Icons.icon
import kotlinx.html.ButtonType
import kotlinx.html.DIV
import kotlinx.html.FlowContent
import kotlinx.html.InputType
import kotlinx.html.id
import kotlinx.html.input

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
 * GLOB-1/DASH-2: authoritative trading-mode plate on every page.
 * Precedence is SIMULATION > DRY RUN > LIVE TRADING (simulation wins even when
 * dryRun is also true). Distinct from the STREAM/STALE SSE chip — do not hide
 * or downgrade this plate.
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
 * Dashboard STREAM/STALE placeholder — SSE freshness only, not trading mode
 * (StatusCard.Live/Delayed colors mean healthy/stale stream). Shell renders it
 * so the header cluster is complete before HTMX; the fragment replaces
 * `#header-status` via `hx-swap-oob`.
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
 * mode plate (not on a separate right-aligned row). [trailingContent] remains
 * in the same top-line cluster for controls such as the loop state/action.
 */
fun FlowContent.brandWithMode(
    settings: Settings,
    includeStreamSlot: Boolean = false,
    trailingContent: FlowContent.() -> Unit = {},
) {
    val titleClass =
        CssClass.Layout.HeaderTitleSection +
            if (includeStreamSlot) CssClass.Layout.HeaderWithStream else CssClass.Layout.HeaderWithoutStream
    div(titleClass) {
        div(CssClass.Layout.HeaderIdentity) {
            brandMark()
            modePlate(settings)
        }
        if (includeStreamSlot) {
            streamStatusPlaceholder()
        }
        trailingContent()
    }
}

fun FlowContent.loopControl(paused: Boolean, csrfToken: String? = null) {
    div(CssClass.Layout.LoopControl) {
        if (csrfToken != null) {
            input(type = InputType.hidden, name = FormFields.CSRF_TOKEN) {
                value = csrfToken
                id = HtmlIds.CSRF_TOKEN
            }
        }
        div(
            CssClass.Layout.LoopState +
                if (paused) CssClass.Layout.LoopPaused else CssClass.Layout.LoopRunning,
        ) {
            span(CssClass.Layout.LoopDot) {}
            +(if (paused) ViewText.LOOP_PAUSED else ViewText.LOOP_RUNNING)
        }
        button(CssClass.Button.Secondary, type = ButtonType.button) {
            id = HtmlIds.LOOP_CONTROL
            attributes[HtmxAttrs.HX_POST] = if (paused) Routes.API_RESUME else Routes.API_PAUSE
            attributes[HtmxAttrs.HX_INCLUDE] = HtmlQueries.CSRF_TOKEN
            attributes[HtmxAttrs.HX_SWAP] = HtmxValues.NONE
            attributes[HtmlAttrs.TITLE] = if (paused) ViewText.LOOP_RESUME_TITLE else ViewText.LOOP_PAUSE_TITLE
            icon(if (paused) Icons.PLAY else Icons.PAUSE)
            span(CssClass.Layout.LoopActionLabel) {
                +(if (paused) ViewText.LOOP_RESUME_ACTION else ViewText.LOOP_PAUSE_ACTION)
            }
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
    valueId: String? = null,
    titleId: String? = null,
) {
    div(CssClass.StatusCard.Default) {
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

fun DIV.formGroup(label: String, inputId: String, block: DIV.() -> Unit) {
    div(CssClass.Form.Group) {
        label(CssClass.Form.Label) {
            htmlFor = inputId
            +label
        }
        block()
    }
}
