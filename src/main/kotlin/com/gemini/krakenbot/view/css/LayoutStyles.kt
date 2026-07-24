package com.gemini.krakenbot.view.css

import com.gemini.krakenbot.view.util.CssClass
import kotlinx.css.*

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
                "radial-gradient(circle at 15% 50%, ${CssTheme.colorBgGlowBlue.value} 0%, transparent 50%), " +
                    "radial-gradient(circle at 85% 30%, ${CssTheme.colorBgGlowGreen.value} 0%, transparent 50%)",
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

        header {
            display = Display.flex
            flexDirection = FlexDirection.column
            gap = 0.75.rem
            paddingBottom = 1.rem
            borderBottomWidth = 1.px
            borderBottomStyle = BorderStyle.solid
            borderBottomColor = CssTheme.colorBorderMuted
            marginBottom = 1.25.rem
        }

        ".${CssClass.Layout.HeaderTitleSection}" {
            display = Display.flex
            alignItems = Align.center
            gap = 0.75.rem
        }

        ".${CssClass.Layout.HeaderTitleSection} h1" {
            fontFamily = CssTheme.fontHeading
            fontSize = 1.5.rem
            fontWeight = FontWeight.w800
            background = "linear-gradient(90deg, ${CssTheme.colorBlueAccent.value}, ${CssTheme.colorGreenAccent.value})"
            put("-webkit-background-clip", "text")
            put("background-clip", "text")
            put("-webkit-text-fill-color", "transparent")
            put("letter-spacing", "-0.025em")
        }

        ".${CssClass.Layout.HeaderActions}" {
            display = Display.flex
            alignItems = Align.center
            gap = 1.25.rem
        }

        ".${CssClass.DataAge.Container}" {
            textAlign = TextAlign.right
        }

        ".${CssClass.DataAge.Label}" {
            fontSize = 0.75.rem
            color = CssTheme.colorTextMuted
            put("text-transform", "uppercase")
            put("letter-spacing", "0.05em")
            fontWeight = FontWeight.w600
        }

        ".${CssClass.DataAge.Value}" {
            fontFamily = CssTheme.fontMono
            fontSize = 0.875.rem
            fontWeight = FontWeight.w700
            color = CssTheme.colorTextBright
        }

        ".${CssClass.DataAge.Value}.stale" {
            color = CssTheme.colorWarning
        }

        ".${CssClass.DataAge.Time}" {
            fontSize = 0.75.rem
            color = CssTheme.colorTextMuted
        }

        ".${CssClass.Layout.GlassPanel}" {
            background = CssTheme.colorGlass.value
            put("backdrop-filter", "blur(20px)")
            put("-webkit-backdrop-filter", "blur(20px)")
            borderWidth = 1.px
            borderStyle = BorderStyle.solid
            borderColor = CssTheme.colorGlassBorder
            put("box-shadow", "0 25px 50px -12px rgba(0, 0, 0, 0.5)")
            borderRadius = 1.25.rem
            padding = Padding(1.5.rem)
            put("transition", "all 0.3s cubic-bezier(0.4, 0, 0.2, 1)")
        }

        ".${CssClass.Layout.GlassPanel}:hover" {
            borderColor = CssTheme.colorGlassBorderHover
            put("box-shadow", "0 0 30px rgba(56, 189, 248, 0.08), 0 25px 50px -12px rgba(0, 0, 0, 0.5)")
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

        ".${CssClass.Layout.OverviewGrid}" {
            display = Display.grid
            gridTemplateColumns = GridTemplateColumns("1fr")
            gap = 1.rem
            marginBottom = 1.25.rem
        }

        ".${CssClass.Layout.DetailGrid}" {
            display = Display.grid
            gridTemplateColumns = GridTemplateColumns("1fr")
            gap = 1.25.rem
            marginBottom = 1.25.rem
        }
    }
}
