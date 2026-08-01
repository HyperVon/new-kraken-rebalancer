package com.gemini.krakenbot.view.css

import com.gemini.krakenbot.view.util.CssClass
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
import kotlinx.css.columnGap
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
            put("-webkit-font-smoothing", "antialiased")
            put("-moz-osx-font-smoothing", "grayscale")
            minHeight = 100.vh
            put("line-height", "1.5")
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
            put("margin-left", "auto")
            put("margin-right", "auto")
            padding = Padding(1.rem, 1.rem, 3.rem, 1.rem)
        }

        // DASH-2: one standardized header row across every page (brand · status · nav · action).
        header {
            display = Display.flex
            flexDirection = FlexDirection.row
            alignItems = Align.center
            put("justify-content", "space-between")
            flexWrap = FlexWrap.wrap
            rowGap = 0.75.rem
            columnGap = 1.rem
            put("min-height", "3rem")
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
        }

        ".${CssClass.Layout.HeaderTitleSection} h1, .${CssClass.Layout.BrandMark}" {
            fontFamily = CssTheme.fontHeading
            fontSize = 1.75.rem
            fontWeight = FontWeight.w800
            put("letter-spacing", "-0.03em")
            put("line-height", "1.1")
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
            put("justify-content", "flex-end")
            marginBottom = 0.rem
        }

        ".${CssClass.DataAge.Value}" {
            fontFamily = CssTheme.fontMono
            fontSize = 0.875.rem
            fontWeight = FontWeight.w700
            color = CssTheme.colorTextBright
            put("line-height", "1.35")
        }

        ".${CssClass.DataAge.Value}.${CssClass.Utility.Stale}" {
            color = CssTheme.colorWarning
        }

        ".${CssClass.DataAge.Time}" {
            fontSize = 0.75.rem
            color = CssTheme.colorTextMuted
            put("line-height", "1.35")
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
            put("letter-spacing", "0.08em")
            borderWidth = 1.px
            borderStyle = BorderStyle.solid
            put("text-transform", "uppercase")
            put("white-space", "nowrap")
        }

        ".${CssClass.Mode.Plate.value} svg" {
            width = 0.875.rem
            height = 0.875.rem
        }

        ".${CssClass.Mode.Dot.value}" {
            width = 0.4375.rem
            height = 0.4375.rem
            borderRadius = 50.pct
            put("background-color", "currentColor")
            flexShrink = 0.0
        }

        ".mode-simulation" {
            color = CssTheme.colorBlueAccent
            backgroundColor = CssTheme.colorBlueGlassBg
            borderColor = CssTheme.colorBlueGlassBorderHover
            put("box-shadow", "0 0 16px rgba(59, 130, 246, 0.25), ${CssTheme.insetTopHighlight}")
        }

        ".mode-dry-run" {
            color = CssTheme.colorWarning
            backgroundColor = CssTheme.colorWarningMuted
            borderColor = CssTheme.colorWarningBorder
            put("box-shadow", "0 0 14px rgba(245, 158, 11, 0.22), ${CssTheme.insetTopHighlight}")
        }

        ".mode-live" {
            color = CssTheme.colorDanger
            backgroundColor = CssTheme.colorDangerMuted
            borderColor = CssTheme.colorDangerBorder
            put("box-shadow", "0 0 14px rgba(239, 68, 68, 0.22), ${CssTheme.insetTopHighlight}")
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
            put("white-space", "nowrap")
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

        ".${CssClass.Layout.HeroSide.value}" {
            display = Display.grid
            gridTemplateColumns = GridTemplateColumns("1fr")
            gap = 1.rem
        }

        ".${CssClass.Layout.GlassPanel}" {
            // Raised glass: cool blue sheen + light blur + cyan rim (not matte, not milky).
            background = CssTheme.glassSurfaceGradient
            put("backdrop-filter", "blur(16px)")
            put("-webkit-backdrop-filter", "blur(16px)")
            solidBorder(CssTheme.colorSurface1Border)
            put("box-shadow", CssTheme.shadowSurface1)
            borderRadius = 0.875.rem
            padding = Padding(1.5.rem)
            put("transition", "border-color 0.25s ease, box-shadow 0.25s ease")
        }

        ".${CssClass.Layout.GlassPanel}:hover" {
            borderColor = CssTheme.colorGlassBorderHover
            put("box-shadow", CssTheme.shadowSurface2)
        }

        ".${CssClass.Layout.Container} > .${CssClass.Layout.GlassPanel}" {
            marginBottom = 1.25.rem
        }

        ".${CssClass.Utility.GlassPanelTitle}" {
            fontSize = 0.875.rem
            fontWeight = FontWeight.w600
            color = CssTheme.colorTextSecondary
            put("text-transform", "uppercase")
            put("letter-spacing", "0.05em")
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
    }
}
