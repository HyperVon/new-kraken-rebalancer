package com.gemini.krakenbot.view.css

import com.gemini.krakenbot.view.util.CssClass
import com.gemini.krakenbot.view.util.HtmlIds
import kotlinx.css.Align
import kotlinx.css.BackgroundAttachment
import kotlinx.css.BorderStyle
import kotlinx.css.BoxSizing
import kotlinx.css.CssBuilder
import kotlinx.css.Display
import kotlinx.css.FlexDirection
import kotlinx.css.FlexWrap
import kotlinx.css.FontWeight
import kotlinx.css.GridTemplateColumns
import kotlinx.css.Margin
import kotlinx.css.Padding
import kotlinx.css.alignItems
import kotlinx.css.background
import kotlinx.css.backgroundAttachment
import kotlinx.css.backgroundColor
import kotlinx.css.body
import kotlinx.css.borderBottomColor
import kotlinx.css.borderBottomStyle
import kotlinx.css.borderBottomWidth
import kotlinx.css.borderColor
import kotlinx.css.borderRadius
import kotlinx.css.borderStyle
import kotlinx.css.borderWidth
import kotlinx.css.boxSizing
import kotlinx.css.color
import kotlinx.css.display
import kotlinx.css.flexDirection
import kotlinx.css.flexShrink
import kotlinx.css.flexWrap
import kotlinx.css.fontFamily
import kotlinx.css.fontSize
import kotlinx.css.fontWeight
import kotlinx.css.gap
import kotlinx.css.gridTemplateColumns
import kotlinx.css.header
import kotlinx.css.height
import kotlinx.css.margin
import kotlinx.css.marginBottom
import kotlinx.css.marginTop
import kotlinx.css.maxWidth
import kotlinx.css.minHeight
import kotlinx.css.minWidth
import kotlinx.css.padding
import kotlinx.css.paddingBottom
import kotlinx.css.pct
import kotlinx.css.px
import kotlinx.css.rem
import kotlinx.css.rowGap
import kotlinx.css.vh
import kotlinx.css.width

object LayoutStyles {
    fun CssBuilder.applyLayoutStyles() {
        "*" {
            boxSizing = BoxSizing.borderBox
            margin = Margin(0.px)
            padding = Padding(0.px)
        }

        body {
            backgroundColor = CssTheme.colorBg
            color = CssTheme.colorTextPrimary
            fontFamily = CssTheme.fontSans
            webkitFontSmoothingRaw("antialiased")
            mozOsxFontSmoothingRaw("grayscale")
            minHeight = 100.vh
            lineHeightRaw("1.5")
            put(
                "background-image",
                "radial-gradient(ellipse 90% 55% at 12% 0%, " +
                    "${CssTheme.colorBgGlowBlue.value} 0%, transparent 55%), " +
                    "radial-gradient(ellipse 70% 45% at 92% 8%, " +
                    "${CssTheme.colorBgGlowPurple.value} 0%, transparent 50%), " +
                    "radial-gradient(ellipse 60% 40% at 70% 95%, " +
                    "${CssTheme.colorBgGlowGreen.value} 0%, transparent 50%)",
            )
            backgroundAttachment = BackgroundAttachment.fixed
        }

        ".${CssClass.Layout.Container}" {
            maxWidth = 80.rem
            marginTop = 0.px
            marginBottom = 0.px
            marginLeftRaw("auto")
            marginRightRaw("auto")
            padding = Padding(1.rem, 1.rem, 3.rem, 1.rem)
        }

        // DASH-2: every page keeps identity/status/actions on top and navigation below.
        header {
            display = Display.flex
            flexDirection = FlexDirection.column
            alignItems = Align.stretch
            rowGap = 0.75.rem
            minHeightRaw("3rem")
            paddingBottom = 1.25.rem
            borderBottomWidth = 1.px
            borderBottomStyle = BorderStyle.solid
            borderBottomColor = CssTheme.colorBorderMuted
            marginBottom = 1.5.rem
        }

        ".${CssClass.Layout.HeaderTitleSection}" {
            display = Display.flex
            alignItems = Align.center
            gap = 0.75.rem
            flexWrap = FlexWrap.wrap
            width = 100.pct
        }

        ".${CssClass.Layout.HeaderTitleSection} h1, .${CssClass.Layout.BrandMark}" {
            fontFamily = CssTheme.fontHeading
            fontSize = 1.75.rem
            fontWeight = FontWeight.w800
            letterSpacingRaw("-0.03em")
            lineHeightRaw("1.1")
            margin = Margin(0.px)
        }

        ".${CssClass.Layout.BrandPrimary}" {
            color = CssTheme.colorTextPrimary
        }

        ".${CssClass.Layout.BrandAccent}" {
            color = CssTheme.colorBlueAccent
        }

        ".${CssClass.Layout.HeaderActions}" {
            display = Display.flex
            alignItems = Align.center
            gap = 1.rem
            flexWrap = FlexWrap.wrap
            justifyContentRaw("flex-start")
            width = 100.pct
            marginBottom = 0.rem
        }

        ".${CssClass.Layout.HeaderActions} > #${HtmlIds.SAVE_BUTTON}" {
            marginLeftRaw("auto")
        }

        ".${CssClass.Layout.LoopControl}" {
            display = Display.inlineFlex
            alignItems = Align.center
            gap = 0.5.rem
            flexWrap = FlexWrap.nowrap
            flexShrink = 0.0
        }

        ".${CssClass.Layout.LoopState}" {
            display = Display.inlineFlex
            alignItems = Align.center
            gap = 0.375.rem
            padding = Padding(0.375.rem, 0.625.rem)
            borderRadius = CssTheme.radiusPill
            fontSize = 0.6875.rem
            fontWeight = FontWeight.w700
            letterSpacingRaw("0.08em")
            borderWidth = 1.px
            borderStyle = BorderStyle.solid
            whiteSpaceRaw("nowrap")
        }

        ".${CssClass.Layout.LoopRunning}" {
            color = CssTheme.colorSuccess
            backgroundColor = CssTheme.colorSuccessMuted
            borderColor = CssTheme.colorSuccessBorder
        }

        ".${CssClass.Layout.LoopPaused}" {
            color = CssTheme.colorWarning
            backgroundColor = CssTheme.colorWarningMuted
            borderColor = CssTheme.colorWarningBorder
        }

        ".${CssClass.Layout.LoopDot}" {
            width = 0.4375.rem
            height = 0.4375.rem
            borderRadius = 50.pct
            backgroundColorRaw("currentColor")
            flexShrink = 0.0
        }

        ".${CssClass.Layout.LoopControl} > .btn" {
            width = 2.25.rem
            height = 2.25.rem
            justifyContentRaw("center")
            padding = Padding(0.px)
        }

        ".${CssClass.Layout.LoopControl} > .btn svg" {
            display = Display.block
            width = 0.875.rem
            height = 0.875.rem
        }

        ".${CssClass.DataAge.Value}" {
            fontFamily = CssTheme.fontMono
            fontSize = 0.875.rem
            fontWeight = FontWeight.w700
            color = CssTheme.colorTextBright
            lineHeightRaw("1.35")
        }

        ".${CssClass.DataAge.Value}.${CssClass.Utility.Stale}" {
            color = CssTheme.colorWarning
        }

        ".${CssClass.DataAge.Time}" {
            fontSize = 0.75.rem
            color = CssTheme.colorTextMuted
            lineHeightRaw("1.35")
        }

        // GLOB-1/DASH-2: persistent trading-mode plate + compact single-line stream status.
        ".${CssClass.Mode.Plate.value}" {
            display = Display.inlineFlex
            alignItems = Align.center
            gap = 0.4.rem
            padding = Padding(0.3125.rem, 0.75.rem)
            borderRadius = CssTheme.radiusPill
            fontSize = 0.6875.rem
            fontWeight = FontWeight.w700
            letterSpacingRaw("0.08em")
            borderWidth = 1.px
            borderStyle = BorderStyle.solid
            textTransformRaw("uppercase")
            whiteSpaceRaw("nowrap")
        }

        ".${CssClass.Mode.Plate.value} svg" {
            width = 0.875.rem
            height = 0.875.rem
        }

        ".${CssClass.Mode.Dot.value}" {
            width = 0.4375.rem
            height = 0.4375.rem
            borderRadius = 50.pct
            backgroundColorRaw("currentColor")
            flexShrink = 0.0
        }

        ".mode-simulation" {
            color = CssTheme.colorBlueAccent
            backgroundColor = CssTheme.colorBlueGlassBg
            borderColor = CssTheme.colorBlueGlassBorderHover
            boxShadowRaw("${CssTheme.glowBlueStrong}, ${CssTheme.insetTopHighlight}")
        }

        ".mode-dry-run" {
            color = CssTheme.colorWarning
            backgroundColor = CssTheme.colorWarningMuted
            borderColor = CssTheme.colorWarningBorder
            boxShadowRaw("${CssTheme.glowAmberSoft}, ${CssTheme.insetTopHighlight}")
        }

        ".mode-live" {
            color = CssTheme.colorDanger
            backgroundColor = CssTheme.colorDangerMuted
            borderColor = CssTheme.colorDangerBorder
            boxShadowRaw("${CssTheme.glowRedSoft}, ${CssTheme.insetTopHighlight}")
        }

        ".${CssClass.Layout.HeaderStatus.value}" {
            display = Display.inlineFlex
            alignItems = Align.center
            gap = 0.5.rem
            padding = Padding(0.375.rem, 0.75.rem)
            borderRadius = CssTheme.radiusPill
            background = CssTheme.colorSurface2.value
            solidBorder(CssTheme.colorGlassBorder)
            put(
                "box-shadow",
                "0 4px 12px rgba(0,0,0,0.4), inset 0 1px 0 rgba(186,220,255,0.1)",
            )
            fontSize = 0.75.rem
            color = CssTheme.colorTextSecondary
            whiteSpaceRaw("nowrap")
            fontFamily = CssTheme.fontMono
        }

        // Hero grid (DASH-1): 2fr total + 1fr stacked compact tiles.
        ".${CssClass.Layout.HeroGrid.value}" {
            display = Display.grid
            gridTemplateColumns = GridTemplateColumns("1fr")
            gap = 1.rem
            marginBottom = 1.25.rem
            alignItems = Align.stretch
        }

        ".${CssClass.Layout.HeroGrid.value} > *" {
            minWidth = 0.px
        }

        ".${CssClass.Layout.HeroSide.value}" {
            display = Display.grid
            gridTemplateColumns = GridTemplateColumns("1fr")
            gap = 1.rem
        }

        ".${CssClass.Layout.GlassPanel}" {
            // Raised glass: cool blue sheen + light blur + cyan rim (not matte, not milky).
            background = CssTheme.glassSurfaceGradient
            backdropFilterRaw("blur(16px)")
            webkitBackdropFilterRaw("blur(16px)")
            solidBorder(CssTheme.colorSurface1Border)
            boxShadowRaw(CssTheme.shadowSurface1)
            borderRadius = CssTheme.radiusXl
            padding = Padding(1.5.rem)
            minWidth = 0.px
            transitionRaw("border-color 0.25s ease, box-shadow 0.25s ease")
        }

        ".${CssClass.Layout.GlassPanel}:hover" {
            borderColor = CssTheme.colorGlassBorderHover
            boxShadowRaw(CssTheme.shadowSurface2)
        }

        ".${CssClass.Layout.Container} > .${CssClass.Layout.GlassPanel}" {
            marginBottom = 1.25.rem
        }

        ".${CssClass.Utility.GlassPanelTitle}" {
            fontSize = 0.875.rem
            fontWeight = FontWeight.w600
            color = CssTheme.colorTextSecondary
            textTransformRaw("uppercase")
            letterSpacingRaw("0.05em")
            marginBottom = 0.75.rem
            display = Display.flex
            alignItems = Align.center
            gap = 0.5.rem
        }

        ".${CssClass.Utility.GlassPanelTitle} svg" {
            color = CssTheme.colorBlueAccent
        }

        ".${CssClass.Layout.DetailGrid}" {
            display = Display.grid
            gridTemplateColumns = GridTemplateColumns("1fr")
            gap = 1.25.rem
            marginBottom = 1.25.rem
        }

        ".${CssClass.Layout.DetailGrid} > *" {
            minWidth = 0.px
        }
    }
}
