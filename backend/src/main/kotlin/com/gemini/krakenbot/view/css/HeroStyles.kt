package com.gemini.krakenbot.view.css

import com.gemini.krakenbot.view.util.CssClass
import kotlinx.css.Align
import kotlinx.css.BorderStyle
import kotlinx.css.CssBuilder
import kotlinx.css.Display
import kotlinx.css.FlexDirection
import kotlinx.css.FontWeight
import kotlinx.css.Overflow
import kotlinx.css.Padding
import kotlinx.css.Position
import kotlinx.css.TextAlign
import kotlinx.css.alignItems
import kotlinx.css.background
import kotlinx.css.backgroundColor
import kotlinx.css.borderColor
import kotlinx.css.borderRadius
import kotlinx.css.borderStyle
import kotlinx.css.borderWidth
import kotlinx.css.color
import kotlinx.css.display
import kotlinx.css.flexDirection
import kotlinx.css.flexGrow
import kotlinx.css.flexShrink
import kotlinx.css.fontFamily
import kotlinx.css.fontSize
import kotlinx.css.fontWeight
import kotlinx.css.gap
import kotlinx.css.height
import kotlinx.css.left
import kotlinx.css.marginTop
import kotlinx.css.minWidth
import kotlinx.css.overflow
import kotlinx.css.padding
import kotlinx.css.pct
import kotlinx.css.position
import kotlinx.css.properties.transform
import kotlinx.css.properties.translateX
import kotlinx.css.px
import kotlinx.css.rem
import kotlinx.css.right
import kotlinx.css.textAlign
import kotlinx.css.top
import kotlinx.css.width

object HeroStyles {
    fun CssBuilder.applyHeroStyles() {
        // DASH-1: Total Portfolio hero card
        ".hero-card" {
            display = Display.flex
            alignItems = Align.stretch
            gap = 1.5.rem
            padding = Padding(1.5.rem, 1.75.rem)
            // Slightly taller lift than sibling tiles.
            boxShadowRaw(CssTheme.shadowHeroCard)
        }

        ".hero-card > .hero-card-text" {
            display = Display.flex
            flexDirection = FlexDirection.column
            justifyContentRaw("center")
            gap = 0.5.rem
            flexShrink = 0.0
        }

        ".${CssClass.Hero.Label.value}" {
            fontSize = 0.75.rem
            fontWeight = FontWeight.w600
            color = CssTheme.colorTextSecondary
            textTransformRaw("uppercase")
            letterSpacingRaw("0.08em")
            display = Display.flex
            alignItems = Align.center
            gap = 0.5.rem
        }

        ".${CssClass.Hero.Label.value} svg" {
            width = 1.rem
            height = 1.rem
            color = CssTheme.colorBlueAccent
            filterRaw(CssTheme.filterHeroIcon)
        }

        ".${CssClass.Hero.Value.value}" {
            fontFamily = CssTheme.fontHeading
            fontSize = 3.rem
            fontWeight = FontWeight.w700
            color = CssTheme.colorTextPrimary
            letterSpacingRaw("-0.03em")
            lineHeightRaw("1.05")
            fontVariantNumericRaw("tabular-nums")
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
            fontVariantNumericRaw("tabular-nums")
            borderWidth = 1.px
            borderStyle = BorderStyle.solid
        }

        ".hero-delta.up" {
            color = CssTheme.colorSuccess
            backgroundColor = CssTheme.colorSuccessBgSubtle
            borderColor = CssTheme.colorSuccessBorderSubtle
            boxShadowRaw(CssTheme.glowGreenSoft)
        }

        ".hero-delta.down" {
            color = CssTheme.colorDanger
            backgroundColor = CssTheme.colorDangerBgSubtle
            borderColor = CssTheme.colorDangerBorderSubtle
            boxShadowRaw(CssTheme.shadowDeltaDown)
        }

        ".hero-delta.flat" {
            color = CssTheme.colorTextMuted
            backgroundColor = CssTheme.colorSlateMuted
            borderColor = CssTheme.colorSlateBorder
        }

        ".${CssClass.Hero.Drawdown.value}" {
            fontSize = 0.75.rem
            color = CssTheme.colorTextMuted
            marginTop = 0.25.rem
        }

        ".${CssClass.Hero.DeltaWindow.value}" {
            fontSize = 0.75.rem
            color = CssTheme.colorTextMuted
            textTransformRaw("uppercase")
            letterSpacingRaw("0.05em")
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
            filterRaw(CssTheme.filterHeroDelta)
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
            boxSizingRaw("content-box")
            borderRadius = 50.pct
            color = CssTheme.colorTextSecondary
            background = CssTheme.colorWhiteMuted.value
        }

        ".hero-tile-cash .${CssClass.Hero.TileHeader.value} svg" {
            color = CssTheme.colorSuccess
            background = CssTheme.colorSuccessMuted.value
            boxShadowRaw(CssTheme.glowGreenSoft)
        }

        ".hero-tile-crypto .${CssClass.Hero.TileHeader.value} svg" {
            color = CssTheme.colorPurpleAccent
            background = CssTheme.colorPurpleMuted.value
            boxShadowRaw(CssTheme.glowPurpleSoft)
        }

        ".${CssClass.Hero.TileTitle.value}" {
            fontSize = 0.75.rem
            fontWeight = FontWeight.w600
            color = CssTheme.colorTextSecondary
            textTransformRaw("uppercase")
            letterSpacingRaw("0.05em")
        }

        ".${CssClass.Hero.TileValue.value}" {
            fontFamily = CssTheme.fontHeading
            fontSize = 1.375.rem
            fontWeight = FontWeight.w700
            color = CssTheme.colorTextPrimary
            fontVariantNumericRaw("tabular-nums")
            lineHeightRaw("1.15")
        }

        ".${CssClass.Hero.TileBarRow.value}" {
            display = Display.flex
            alignItems = Align.center
            gap = 0.625.rem
        }

        // Shared progress-bar styling for hero tiles and the allocation chart;
        // per-variant overrides below keep the computed styles identical.
        // Leading dots are required on every selector part: CssClass.value is
        // the bare class name, and a missing dot emits a type selector that
        // matches nothing.
        val barTracks = ".${CssClass.Hero.TileBarTrack.value}, .${CssClass.AllocationChart.BarTrack.value}"
        barTracks {
            flexGrow = 1.0
            background = CssTheme.barTrackGradient
            borderRadius = CssTheme.radiusPill
            overflow = Overflow.hidden
            solidBorder(CssTheme.colorWhiteSubtle)
        }

        val barFills = ".${CssClass.Hero.TileBarFill.value}, .${CssClass.AllocationChart.BarFill.value}"
        barFills {
            position = Position.relative
            height = 100.pct
            borderRadius = CssTheme.radiusPill
            backgroundImageRaw(CssTheme.glassBarSheen)
            backgroundBlendModeRaw("soft-light")
            boxShadowRaw(CssTheme.barFillShadow)
            transitionRaw("width 0.8s cubic-bezier(0.4, 0, 0.2, 1)")
        }

        val barFillSheens =
            ".${CssClass.Hero.TileBarFill.value}::after, .${CssClass.AllocationChart.BarFill.value}::after"
        barFillSheens {
            contentRaw("\"\"")
            position = Position.absolute
            left = 8.pct
            right = 8.pct
            top = 1.px
            height = 38.pct
            borderRadius = CssTheme.radiusPill
            background = CssTheme.shimmerGradient
            pointerEventsRaw("none")
        }

        // Hero tile bars keep their compact sizing.
        ".${CssClass.Hero.TileBarTrack.value}" {
            height = 0.625.rem
            boxShadowRaw(CssTheme.insetShadowDark)
        }
        ".${CssClass.Hero.TileBarFill.value}" {
            boxShadowRaw(CssTheme.barFillShadow)
        }
        ".${CssClass.Hero.TileBarFill.value}::after" {
            left = 10.pct
            right = 10.pct
            height = 40.pct
            background = CssTheme.shimmerGradientAlt
        }

        // Allocation bars keep the larger sizing and deeper inset shadow.
        ".${CssClass.AllocationChart.BarTrack.value}" {
            height = 0.85.rem
            boxShadowRaw("inset 0 1px 2px " + CssTheme.shadowScrimSoft)
        }

        ".${CssClass.Hero.TileMeta.value}" {
            fontSize = 0.75.rem
            color = CssTheme.colorTextSecondary
            fontVariantNumericRaw("tabular-nums")
        }

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
            transitionRaw("transform 0.2s ease")
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

        ".${CssClass.AllocationChart.BarValue}" {
            width = 9.5.rem
            textAlign = TextAlign.right
            fontFamily = CssTheme.fontMono
            fontSize = 0.75.rem
            color = CssTheme.colorTextSecondary
        }
    }
}
