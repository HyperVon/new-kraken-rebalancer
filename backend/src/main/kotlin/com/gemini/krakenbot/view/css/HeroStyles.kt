package com.gemini.krakenbot.view.css

import com.gemini.krakenbot.view.util.ChartProps
import com.gemini.krakenbot.view.util.CssClass
import kotlinx.css.Align
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
import kotlinx.css.borderRadius
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
        CssClass.Hero.Card.querySelector {
            display = Display.flex
            flexDirection = FlexDirection.column
            alignItems = Align.stretch
            gap = 1.5.rem
            padding = Padding(1.5.rem, 2.rem)
            // Slightly taller lift than sibling tiles.
            boxShadowRaw(CssTheme.shadowHeroCard)
        }

        "${CssClass.Hero.Card.querySelector} > ${CssClass.Hero.CardText.querySelector}" {
            display = Display.flex
            flexDirection = FlexDirection.column
            justifyContentRaw("center")
            gap = 0.5.rem
            flexShrink = 0.0
        }

        CssClass.Hero.Trend.querySelector {
            display = Display.flex
            flexDirection = FlexDirection.column
            flexGrow = 1.0
            minWidth = 0.px
            gap = 0.25.rem
        }

        CssClass.Hero.TrendHeader.querySelector {
            display = Display.flex
            alignItems = Align.center
            justifyContentRaw("space-between")
            gap = 0.75.rem
            minWidth = 0.px
        }

        CssClass.Hero.TrendTitle.querySelector {
            fontSize = 0.6875.rem
            fontWeight = FontWeight.w600
            color = CssTheme.colorTextSecondary
            textTransformRaw("uppercase")
            letterSpacingRaw("0.06em")
        }

        CssClass.Hero.TrendSummary.querySelector {
            fontFamily = CssTheme.fontMono
            fontSize = 0.6875.rem
            color = CssTheme.colorTextMuted
            whiteSpaceRaw("nowrap")
        }

        CssClass.Hero.TrendChart.querySelector {
            width = 100.pct
            height = 10.5.rem
            minWidth = 0.px
            background = ChartProps.COLOR_BLUE_BG
            borderRadius = CssTheme.radiusMd
        }

        CssClass.Hero.TrendSvg.querySelector {
            display = Display.block
            width = 100.pct
            height = 100.pct
            overflow = Overflow.visible
        }

        CssClass.Hero.TrendDeltaRow.querySelector {
            display = Display.flex
            alignItems = Align.center
            gap = 0.5.rem
            marginTop = 0.5.rem
        }

        "${CssClass.Hero.TrendDeltaPositive.querySelector}, ${CssClass.Hero.TrendDeltaNegative.querySelector}" {
            padding = Padding(0.125.rem, 0.625.rem)
            borderRadius = CssTheme.radiusPill
            fontFamily = CssTheme.fontMono
            fontSize = 0.75.rem
            fontWeight = FontWeight.w700
        }

        CssClass.Hero.TrendDeltaPositive.querySelector {
            color = CssTheme.colorSuccess
            background = CssTheme.colorSuccessMuted.value
        }

        CssClass.Hero.TrendDeltaNegative.querySelector {
            color = CssTheme.colorDanger
            background = CssTheme.colorDangerMuted.value
        }

        CssClass.Hero.TrendDeltaLabel.querySelector {
            fontFamily = CssTheme.fontMono
            fontSize = 0.6875.rem
            color = CssTheme.colorTextMuted
        }

        CssClass.Hero.TrendLine.querySelector {
            put("fill", "none")
            put("stroke", ChartProps.COLOR_BLUE)
            put("stroke-width", "3.5")
            put("stroke-linejoin", "round")
            put("stroke-linecap", "round")
            put("filter", CssTheme.filterHeroTrendLine)
        }

        CssClass.Hero.TrendPoint.querySelector {
            put("fill", ChartProps.COLOR_BLUE)
            put("stroke", CssTheme.colorSurface1.value)
            put("stroke-width", "2.5")
        }

        CssClass.Hero.TrendEmpty.querySelector {
            display = Display.flex
            alignItems = Align.center
            height = 9.5.rem
            fontSize = 0.8125.rem
            color = CssTheme.colorTextMuted
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
