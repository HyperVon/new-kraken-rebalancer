package com.gemini.krakenbot.view.css

import com.gemini.krakenbot.view.util.CssThemeVars
import kotlinx.css.Color
import kotlinx.css.CssBuilder
import kotlinx.css.px
import kotlinx.css.rem

object CssTheme {
    const val fontSans = CssThemeVars.fontSans
    const val fontHeading = CssThemeVars.fontHeading
    const val fontMono = CssThemeVars.fontMono

    val radiusPill = 9999.px
    val radiusXs = 0.25.rem
    val radiusSm = 0.375.rem
    val radiusMd = 0.5.rem
    val radiusLg = 0.75.rem
    val radiusXl = 0.875.rem

    val colorBg = Color(CssThemeVars.colorBg)
    val colorTextPrimary = Color(CssThemeVars.colorTextPrimary)

    // GLOB-3: lifted for WCAG AA (>=4.5:1) on glass surfaces.
    val colorTextSecondary = Color(CssThemeVars.colorTextSecondary)
    val colorTextMuted = Color(CssThemeVars.colorTextMuted)
    val colorGlass = Color(CssThemeVars.colorGlass)
    val colorGlassBorder = Color(CssThemeVars.colorGlassBorder)
    val colorGlassBorderHover = Color(CssThemeVars.colorGlassBorderHover)
    val colorGlassHover = Color(CssThemeVars.colorGlassHover)

    // Semi-glass raised cards: cool blue sheen (not white fog), clear lift off page.
    val colorSurface1 = Color(CssThemeVars.colorSurface1)
    val colorSurface2 = Color(CssThemeVars.colorSurface2)
    val colorSurface1Border = Color(CssThemeVars.colorSurface1Border)
    val colorSurface2Border = Color(CssThemeVars.colorSurface2Border)
    val colorInsetHighlight = Color(CssThemeVars.colorInsetHighlight)

    // Cool blue glass wash — luminous without milky white glare.
    const val glassSurfaceGradient = CssThemeVars.glassSurfaceGradient
    const val glassBarSheen = CssThemeVars.glassBarSheen

    // Depth + soft cyan rim light (raised glass, not flat matte).
    const val shadowSurface1 = CssThemeVars.shadowSurface1
    const val shadowSurface2 = CssThemeVars.shadowSurface2
    const val insetTopHighlight = CssThemeVars.insetTopHighlight
    const val glowBlueSoft = CssThemeVars.glowBlueSoft
    const val glowGreenSoft = CssThemeVars.glowGreenSoft
    const val glowPurpleSoft = CssThemeVars.glowPurpleSoft
    val colorBlueGradientStart = Color(CssThemeVars.colorBlueGradientStart)
    const val shadowBtnPrimary = CssThemeVars.shadowBtnPrimary
    val colorKrakenBlue = Color(CssThemeVars.colorKrakenBlue)
    val colorBluePrimary = Color(CssThemeVars.colorBluePrimary)
    val colorBlueHover = Color(CssThemeVars.colorBlueHover)
    val colorBlueAccent = Color(CssThemeVars.colorBlueAccent)
    val colorGreenAccent = Color(CssThemeVars.colorGreenAccent)
    val colorSuccess = Color(CssThemeVars.colorSuccess)
    val colorDanger = Color(CssThemeVars.colorDanger)
    val colorWarning = Color(CssThemeVars.colorWarning)
    val colorTextBright = Color(CssThemeVars.colorTextBright)
    val colorDangerLight = Color(CssThemeVars.colorDangerLight)
    val colorMutedLight = Color(CssThemeVars.colorMutedLight)
    val colorBlueDeep = Color(CssThemeVars.colorBlueDeep)
    val colorBorderMuted = Color(CssThemeVars.colorBorderMuted)
    val colorBorderSubtle = Color(CssThemeVars.colorBorderSubtle)
    val colorBorderFaint = Color(CssThemeVars.colorBorderFaint)
    val colorBorderInput = Color(CssThemeVars.colorBorderInput)
    val colorBorderInputStrong = Color(CssThemeVars.colorBorderInputStrong)
    val colorBorderInputHover = Color(CssThemeVars.colorBorderInputHover)
    val colorBlueGlassBg = Color(CssThemeVars.colorBlueGlassBg)
    val colorBlueGlassBorder = Color(CssThemeVars.colorBlueGlassBorder)
    val colorBlueGlassBgHover = Color(CssThemeVars.colorBlueGlassBgHover)
    val colorBlueGlassBorderHover = Color(CssThemeVars.colorBlueGlassBorderHover)
    val colorSuccessMuted = Color(CssThemeVars.colorSuccessMuted)
    val colorSuccessBorder = Color(CssThemeVars.colorSuccessBorder)
    val colorSuccessBgSubtle = Color(CssThemeVars.colorSuccessBgSubtle)
    val colorSuccessBorderSubtle = Color(CssThemeVars.colorSuccessBorderSubtle)
    val colorDangerMuted = Color(CssThemeVars.colorDangerMuted)
    val colorDangerBorder = Color(CssThemeVars.colorDangerBorder)
    val colorDangerBgSubtle = Color(CssThemeVars.colorDangerBgSubtle)
    val colorDangerBorderSubtle = Color(CssThemeVars.colorDangerBorderSubtle)
    val colorDangerBgHover = Color(CssThemeVars.colorDangerBgHover)
    val colorWarningMuted = Color(CssThemeVars.colorWarningMuted)
    val colorWarningBorder = Color(CssThemeVars.colorWarningBorder)
    val colorSlateMuted = Color(CssThemeVars.colorSlateMuted)
    val colorSlateBorder = Color(CssThemeVars.colorSlateBorder)
    val colorWhiteSubtle = Color(CssThemeVars.colorWhiteSubtle)
    val colorWhiteMuted = Color(CssThemeVars.colorWhiteMuted)
    val colorWhiteBorder = Color(CssThemeVars.colorWhiteBorder)
    val colorWhiteFaint = Color(CssThemeVars.colorWhiteFaint)
    val colorIconFaint = Color(CssThemeVars.colorIconFaint)
    val colorBgGlowBlue = Color(CssThemeVars.colorBgGlowBlue)
    val colorBgGlowGreen = Color(CssThemeVars.colorBgGlowGreen)
    val colorBgGlowPurple = Color(CssThemeVars.colorBgGlowPurple)
    val colorPurpleAccent = Color(CssThemeVars.colorPurpleAccent)
    val colorPurpleMuted = Color(CssThemeVars.colorPurpleMuted)
    val colorPurpleBorder = Color(CssThemeVars.colorPurpleBorder)

    // Focus-visible rings shared across interactive controls (WCAG-visible affordance).
    const val focusRingStrong = CssThemeVars.focusRingStrong
    const val focusRingCompact = CssThemeVars.focusRingCompact
    const val focusRingSubtle = CssThemeVars.focusRingSubtle

    // Translucent glass surfaces for secondary buttons / inputs.
    val colorGlassSurfaceSubtle = Color(CssThemeVars.colorGlassSurfaceSubtle)
    val colorGlassSurfaceHover = Color(CssThemeVars.colorGlassSurfaceHover)
    val colorGlassSurfaceInput = Color(CssThemeVars.colorGlassSurfaceInput)
    val colorGlassSurfaceFaint = Color(CssThemeVars.colorGlassSurfaceFaint)

    // Reusable shadow scrims (kept out of bespoke composite shadows below).
    const val shadowScrim = CssThemeVars.shadowScrim
    const val shadowScrimSoft = CssThemeVars.shadowScrimSoft

    // Trading-mode plate glows (combined with insetTopHighlight at call sites).
    const val glowBlueStrong = CssThemeVars.glowBlueStrong
    const val glowAmberSoft = CssThemeVars.glowAmberSoft
    const val glowRedSoft = CssThemeVars.glowRedSoft

    // Tokenized component shadows/filters (extracted from ComponentStyles raw literals — CI-29-U01 + CI-28-U01)
    const val barTrackGradient = CssThemeVars.barTrackGradient
    const val barFillShadow = CssThemeVars.barFillShadow
    const val shimmerGradient = CssThemeVars.shimmerGradient
    const val shimmerGradientAlt = CssThemeVars.shimmerGradientAlt
    const val insetShadowDark = CssThemeVars.insetShadowDark
    const val shadowBadge = CssThemeVars.shadowBadge
    const val shadowHeroCard = CssThemeVars.shadowHeroCard
    const val filterHeroIcon = CssThemeVars.filterHeroIcon
    const val shadowDeltaDown = CssThemeVars.shadowDeltaDown
    const val filterHeroDelta = CssThemeVars.filterHeroDelta

    fun CssBuilder.applyRootVariables() {
        ":root" {
            CssThemeVars.cssVars.forEach { (k, v) -> put(k, v) }
        }
    }
}
