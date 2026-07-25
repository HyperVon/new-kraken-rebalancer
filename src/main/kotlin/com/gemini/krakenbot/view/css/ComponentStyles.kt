package com.gemini.krakenbot.view.css

import com.gemini.krakenbot.view.util.CssClass
import kotlinx.css.*
import kotlinx.css.properties.*

object ComponentStyles {
    fun CssBuilder.applyComponentStyles() {
        // Status Badges & Cards
        ".${CssClass.StatusCard.Badge}" {
            padding = Padding(0.125.rem, 0.625.rem)
            borderRadius = CssTheme.radiusPill
            fontSize = 0.75.rem
            fontWeight = FontWeight.w700
            put("letter-spacing", "0.05em")
            put("box-shadow", "0 4px 6px -1px rgba(0, 0, 0, 0.1)")
        }

        ".${CssClass.StatusCard.Badge}.live" {
            backgroundColor = CssTheme.colorSuccessMuted
            color = CssTheme.colorSuccess
            borderWidth = 1.px
            borderStyle = BorderStyle.solid
            borderColor = CssTheme.colorSuccessBorder
            put("animation", "pulse 2s cubic-bezier(0.4, 0, 0.6, 1) infinite")
        }

        ".${CssClass.StatusCard.Badge}.delayed" {
            backgroundColor = CssTheme.colorWarningMuted
            color = CssTheme.colorWarning
            borderWidth = 1.px
            borderStyle = BorderStyle.solid
            borderColor = CssTheme.colorWarningBorder
        }

        ".${CssClass.StatusCard.Badge}.offline" {
            backgroundColor = CssTheme.colorSlateMuted
            color = CssTheme.colorTextSecondary
            borderWidth = 1.px
            borderStyle = BorderStyle.solid
            borderColor = CssTheme.colorSlateBorder
        }

        "@keyframes pulse" {
            "0%, 100%" {
                opacity = 1
            }
            "50%" {
                opacity = 0.6
            }
        }

        "${CssClass.StatusCard.Default.querySelector}" {
            display = Display.flex
            flexDirection = FlexDirection.column
            gap = 0.375.rem
            padding = Padding(0.75.rem, 0.875.rem)
            borderRadius = 0.875.rem
        }

        ".${CssClass.StatusCard.Header}" {
            display = Display.flex
            put("justify-content", "space-between")
            alignItems = Align.flexStart
            gap = 0.5.rem
        }

        ".${CssClass.StatusCard.Title}" {
            fontSize = 0.8125.rem
            fontWeight = FontWeight.w600
            color = CssTheme.colorTextSecondary
            paddingTop = 0.px
            put("line-height", "1.25")
        }

        ".${CssClass.StatusCard.Icon}" {
            display = Display.flex
            alignItems = Align.center
            put("justify-content", "center")
            width = 1.75.rem
            height = 1.75.rem
            flexShrink = 0.0
            borderRadius = 0.375.rem
            background = CssTheme.colorGlassHover.value
            color = CssTheme.colorTextSecondary
            marginLeft = 0.px
        }

        ".${CssClass.StatusCard.Icon} svg" {
            width = 0.9375.rem
            height = 0.9375.rem
        }

        ".${CssClass.StatusCard.Value}" {
            fontSize = 1.375.rem
            fontWeight = FontWeight.w700
            fontFamily = CssTheme.fontHeading
            put("letter-spacing", "-0.02em")
            put("line-height", "1.15")
        }

        ".status-card.success .${CssClass.StatusCard.Value}" {
            color = CssTheme.colorSuccess
        }

        ".${CssClass.StatusCard.Sub}" {
            paddingTop = 0.px
            fontSize = 0.75.rem
            color = CssTheme.colorTextSecondary
            put("line-height", "1.35")
        }

        // DASH-1: Total Portfolio hero card
        ".hero-card" {
            display = Display.flex
            alignItems = Align.stretch
            gap = 1.5.rem
            padding = Padding(1.5.rem, 1.75.rem)
            // Slightly taller lift than sibling tiles.
            put(
                "box-shadow",
                "0 2px 4px rgba(0,0,0,0.5), 0 16px 36px rgba(0,0,0,0.52), 0 0 32px rgba(56,189,248,0.12), " +
                    "inset 0 1px 0 rgba(147,197,253,0.26), inset 0 -1px 0 rgba(0,0,0,0.28)",
            )
        }

        ".hero-card > .hero-card-text" {
            display = Display.flex
            flexDirection = FlexDirection.column
            put("justify-content", "center")
            gap = 0.5.rem
            flexShrink = 0.0
        }

        ".${CssClass.Hero.Label.value}" {
            fontSize = 0.75.rem
            fontWeight = FontWeight.w600
            color = CssTheme.colorTextSecondary
            put("text-transform", "uppercase")
            put("letter-spacing", "0.08em")
            display = Display.flex
            alignItems = Align.center
            gap = 0.5.rem
        }

        ".${CssClass.Hero.Label.value} svg" {
            width = 1.rem
            height = 1.rem
            color = CssTheme.colorBlueAccent
            put("filter", "drop-shadow(0 0 6px rgba(96, 165, 250, 0.55))")
        }

        ".${CssClass.Hero.Value.value}" {
            fontFamily = CssTheme.fontHeading
            fontSize = 3.rem
            fontWeight = FontWeight.w700
            color = CssTheme.colorTextPrimary
            put("letter-spacing", "-0.03em")
            put("line-height", "1.05")
            put("font-variant-numeric", "tabular-nums")
        }

        ".${CssClass.Hero.DeltaRow.value}" {
            display = Display.flex
            alignItems = Align.center
            gap = 0.625.rem
        }

        ".${CssClass.Hero.Delta.value}" {
            display = Display.inlineFlex
            alignItems = Align.center
            gap = 0.25.rem
            padding = Padding(0.1875.rem, 0.5.rem)
            borderRadius = CssTheme.radiusPill
            fontSize = 0.8125.rem
            fontWeight = FontWeight.w700
            put("font-variant-numeric", "tabular-nums")
            borderWidth = 1.px
            borderStyle = BorderStyle.solid
        }

        ".hero-delta.up" {
            color = CssTheme.colorSuccess
            backgroundColor = CssTheme.colorSuccessBgSubtle
            borderColor = CssTheme.colorSuccessBorderSubtle
            put("box-shadow", CssTheme.glowGreenSoft)
        }

        ".hero-delta.down" {
            color = CssTheme.colorDanger
            backgroundColor = CssTheme.colorDangerBgSubtle
            borderColor = CssTheme.colorDangerBorderSubtle
            put("box-shadow", "0 0 16px rgba(239, 68, 68, 0.3)")
        }

        ".${CssClass.Hero.DeltaWindow.value}" {
            fontSize = 0.75.rem
            color = CssTheme.colorTextMuted
            put("text-transform", "uppercase")
            put("letter-spacing", "0.05em")
        }

        ".${CssClass.Hero.Spark.value}" {
            flexGrow = 1.0
            minWidth = 0.px
            display = Display.flex
            alignItems = Align.center
        }

        ".${CssClass.Hero.Spark.value} svg" {
            width = 100.pct
            height = 5.rem
            display = Display.block
            put("filter", "drop-shadow(0 0 8px rgba(59, 130, 246, 0.45))")
        }

        // Compact Cash / Crypto tiles with progress bars (DASH-1)
        ".hero-tile" {
            display = Display.flex
            flexDirection = FlexDirection.column
            gap = 0.5.rem
            padding = Padding(1.rem, 1.125.rem)
        }

        ".${CssClass.Hero.TileHeader.value}" {
            display = Display.flex
            alignItems = Align.center
            gap = 0.625.rem
        }

        ".${CssClass.Hero.TileHeader.value} svg" {
            width = 1.rem
            height = 1.rem
            padding = Padding(0.45.rem)
            put("box-sizing", "content-box")
            borderRadius = 50.pct
            color = CssTheme.colorTextSecondary
            background = CssTheme.colorWhiteMuted.value
        }

        ".hero-tile-cash .${CssClass.Hero.TileHeader.value} svg" {
            color = CssTheme.colorSuccess
            background = CssTheme.colorSuccessMuted.value
            put("box-shadow", CssTheme.glowGreenSoft)
        }

        ".hero-tile-crypto .${CssClass.Hero.TileHeader.value} svg" {
            color = CssTheme.colorPurpleAccent
            background = CssTheme.colorPurpleMuted.value
            put("box-shadow", CssTheme.glowPurpleSoft)
        }

        ".${CssClass.Hero.TileTitle.value}" {
            fontSize = 0.75.rem
            fontWeight = FontWeight.w600
            color = CssTheme.colorTextSecondary
            put("text-transform", "uppercase")
            put("letter-spacing", "0.05em")
        }

        ".${CssClass.Hero.TileValue.value}" {
            fontFamily = CssTheme.fontHeading
            fontSize = 1.375.rem
            fontWeight = FontWeight.w700
            color = CssTheme.colorTextPrimary
            put("font-variant-numeric", "tabular-nums")
            put("line-height", "1.15")
        }

        ".${CssClass.Hero.TileBarRow.value}" {
            display = Display.flex
            alignItems = Align.center
            gap = 0.625.rem
        }

        ".${CssClass.Hero.TileBarTrack.value}" {
            flexGrow = 1.0
            height = 0.625.rem
            background = "linear-gradient(180deg, rgba(255,255,255,0.04), rgba(255,255,255,0.08))"
            borderRadius = CssTheme.radiusPill
            overflow = Overflow.hidden
            borderWidth = 1.px
            borderStyle = BorderStyle.solid
            borderColor = CssTheme.colorWhiteSubtle
            put("box-shadow", "inset 0 1px 2px rgba(0,0,0,0.35)")
        }

        ".${CssClass.Hero.TileBarFill.value}" {
            position = Position.relative
            height = 100.pct
            borderRadius = CssTheme.radiusPill
            put("background-image", CssTheme.glassBarSheen)
            put("background-blend-mode", "soft-light")
            put("box-shadow", "inset 0 1px 0 rgba(255,255,255,0.35), 0 0 12px rgba(255,255,255,0.10)")
            put("transition", "width 0.8s cubic-bezier(0.4, 0, 0.2, 1)")
        }

        ".${CssClass.Hero.TileBarFill.value}::after" {
            put("content", "\"\"")
            position = Position.absolute
            left = 10.pct
            right = 10.pct
            top = 1.px
            height = 40.pct
            borderRadius = CssTheme.radiusPill
            background = "linear-gradient(90deg, transparent, rgba(186,230,255,0.45), transparent)"
            put("pointer-events", "none")
        }

        ".${CssClass.Hero.TileMeta.value}" {
            fontSize = 0.75.rem
            color = CssTheme.colorTextSecondary
            put("font-variant-numeric", "tabular-nums")
        }

        // Allocation Charts
        ".${CssClass.AllocationChart.Container}" {
            display = Display.flex
            flexDirection = FlexDirection.column
            gap = 0.875.rem
            marginTop = 0.5.rem
        }

        ".${CssClass.AllocationChart.BarRow}" {
            display = Display.flex
            alignItems = Align.center
            gap = 1.rem
            put("transition", "transform 0.2s ease")
        }

        ".${CssClass.AllocationChart.BarRow}:hover" {
            transform { translateX(4.px) }
        }

        ".${CssClass.AllocationChart.BarLabel}" {
            width = 3.5.rem
            fontWeight = FontWeight.w700
            color = CssTheme.colorTextPrimary
            fontSize = 0.875.rem
        }

        ".${CssClass.AllocationChart.BarTrack}" {
            flexGrow = 1.0
            height = 0.85.rem
            background = "linear-gradient(180deg, rgba(255,255,255,0.04), rgba(255,255,255,0.09))"
            borderRadius = CssTheme.radiusPill
            overflow = Overflow.hidden
            borderWidth = 1.px
            borderStyle = BorderStyle.solid
            borderColor = CssTheme.colorWhiteSubtle
            put("box-shadow", "inset 0 1px 2px rgba(0,0,0,0.4)")
        }

        ".${CssClass.AllocationChart.BarFill}" {
            position = Position.relative
            height = 100.pct
            borderRadius = CssTheme.radiusPill
            put("background-image", CssTheme.glassBarSheen)
            put("background-blend-mode", "soft-light")
            put(
                "box-shadow",
                "inset 0 1px 0 rgba(255,255,255,0.35), 0 0 12px rgba(255,255,255,0.12)",
            )
            put("transition", "width 0.8s cubic-bezier(0.4, 0, 0.2, 1)")
        }

        ".${CssClass.AllocationChart.BarFill}::after" {
            put("content", "\"\"")
            position = Position.absolute
            left = 8.pct
            right = 8.pct
            top = 1.px
            height = 38.pct
            borderRadius = CssTheme.radiusPill
            background = "linear-gradient(90deg, transparent, rgba(186,230,255,0.48), transparent)"
            put("pointer-events", "none")
        }

        ".${CssClass.AllocationChart.BarValue}" {
            width = 9.5.rem
            textAlign = TextAlign.right
            fontFamily = CssTheme.fontMono
            fontSize = 0.75.rem
            color = CssTheme.colorTextSecondary
        }

        // Scrollbars, Spinners & Toasts
        ".custom-scrollbar" {
            put("scrollbar-width", "thin")
            put("scrollbar-color", "rgba(148, 163, 184, 0.15) transparent")
        }

        ".custom-scrollbar::-webkit-scrollbar" {
            width = 6.px
            height = 6.px
        }

        ".custom-scrollbar::-webkit-scrollbar-track" {
            background = "transparent"
        }

        ".custom-scrollbar::-webkit-scrollbar-thumb" {
            backgroundColor = CssTheme.colorScrollbarThumb
            borderRadius = CssTheme.radiusPill
        }

        ".custom-scrollbar::-webkit-scrollbar-thumb:hover" {
            backgroundColor = CssTheme.colorScrollbarThumbHover
        }

        ".max-h-100" {
            maxHeight = 25.rem
        }

        ".${CssClass.Loading.SpinnerContainer}" {
            display = Display.flex
            flexDirection = FlexDirection.column
            alignItems = Align.center
            put("justify-content", "center")
            put("min-height", "calc(100vh - 10rem)")
            gap = 1.rem
        }

        ".${CssClass.Loading.Spinner}" {
            width = 3.rem
            height = 3.rem
            borderWidth = 4.px
            borderStyle = BorderStyle.solid
            borderColor = CssTheme.colorKrakenBlue
            put("border-top-color", "transparent")
            borderRadius = 50.pct
            put("animation", "spin 1s linear infinite")
        }

        "@keyframes spin" {
            "to" {
                transform { rotate(360.deg) }
            }
        }

        ".${CssClass.Activity.EmptyHistoryBox}" {
            display = Display.flex
            flexDirection = FlexDirection.column
            alignItems = Align.center
            put("justify-content", "center")
            padding = Padding(4.rem, 1.rem)
            color = CssTheme.colorTextMuted
            textAlign = TextAlign.center
            gap = 0.5.rem
        }

        ".${CssClass.Activity.EmptyHistoryBox} svg" {
            color = CssTheme.colorIconFaint
            marginBottom = 0.5.rem
        }

        ".${CssClass.Activity.EmptyHistoryBox} h3" {
            color = CssTheme.colorTextSecondary
        }

        ".${CssClass.Dashboard.WaitingTitle}" {
            fontSize = 1.25.rem
            fontWeight = FontWeight.w600
            color = CssTheme.colorTextBright
        }

        ".${CssClass.Dashboard.WaitingText}" {
            color = CssTheme.colorTextSecondary
            fontSize = 0.875.rem
            textAlign = TextAlign.center
            maxWidth = 24.rem
        }

        applyActivityFeedStyles()
    }

    // DASH-3: cycle-grouped activity feed replaces the flat log dump.
    private fun CssBuilder.applyActivityFeedStyles() {
        ".${CssClass.Activity.Feed.value}" {
            display = Display.flex
            flexDirection = FlexDirection.column
            gap = 0.75.rem
            marginTop = 0.5.rem
        }

        ".${CssClass.Activity.Cycle.value}" {
            borderRadius = 0.75.rem
            background = CssTheme.glassSurfaceGradient
            borderWidth = 1.px
            borderStyle = BorderStyle.solid
            borderColor = CssTheme.colorSurface2Border
            overflow = Overflow.hidden
            put(
                "box-shadow",
                "0 6px 16px rgba(0,0,0,0.4), 0 0 16px rgba(56,189,248,0.05), " +
                    "inset 0 1px 0 rgba(147,197,253,0.12)",
            )
        }

        ".${CssClass.Activity.CycleHeader.value}" {
            display = Display.flex
            alignItems = Align.center
            gap = 0.75.rem
            padding = Padding(0.625.rem, 0.875.rem)
            put("flex-wrap", "wrap")
        }

        ".${CssClass.Activity.CycleTime.value}" {
            fontSize = 0.75.rem
            color = CssTheme.colorTextMuted
            fontFamily = CssTheme.fontMono
            put("margin-left", "auto")
        }

        ".${CssClass.Activity.CycleMeta.value}" {
            fontSize = 0.75.rem
            color = CssTheme.colorTextSecondary
            fontWeight = FontWeight.w600
        }

        ".${CssClass.Activity.CycleBody.value}" {
            display = Display.flex
            flexDirection = FlexDirection.column
            padding = Padding(0.px, 0.875.rem, 0.5.rem)
        }

        ".${CssClass.Activity.Item.value}" {
            display = Display.flex
            alignItems = Align.center
            gap = 0.625.rem
            padding = Padding(0.375.rem, 0.px)
            borderTopWidth = 1.px
            borderTopStyle = BorderStyle.solid
            borderTopColor = CssTheme.colorBorderFaint
        }

        ".activity-item.trade" {
            paddingLeft = 0.625.rem
            borderLeftWidth = 3.px
            borderLeftStyle = BorderStyle.solid
            borderLeftColor = CssTheme.colorBlueAccent
            put("margin-left", "-0.625rem")
        }

        ".${CssClass.Activity.ItemText.value}" {
            fontSize = 0.8125.rem
            color = CssTheme.colorTextPrimary
            put("line-height", "1.35")
        }

        ".activity-item .${CssClass.Activity.ItemText.value}" {
            color = CssTheme.colorTextSecondary
        }

        ".activity-item.trade .${CssClass.Activity.ItemText.value}" {
            color = CssTheme.colorTextPrimary
            fontWeight = FontWeight.w600
        }

        ".${CssClass.Activity.NoopSummary.value}" {
            fontSize = 0.8125.rem
            color = CssTheme.colorTextMuted
            fontStyle = FontStyle.italic
            padding = Padding(0.375.rem, 0.px)
        }

        ".${CssClass.Activity.FeedFooter.value}" {
            display = Display.flex
            put("justify-content", "center")
            marginTop = 0.75.rem
        }

        ".${CssClass.Activity.ViewAll.value}" {
            display = Display.inlineFlex
            alignItems = Align.center
            gap = 0.375.rem
            fontSize = 0.8125.rem
            fontWeight = FontWeight.w600
            color = CssTheme.colorBlueAccent
            textDecoration = TextDecoration.none
            padding = Padding(0.375.rem, 0.875.rem)
            borderRadius = 0.5.rem
            put("transition", "all 0.2s ease")
        }

        ".${CssClass.Activity.ViewAll.value}:hover" {
            background = CssTheme.colorBlueGlassBg.value
        }
    }
}
