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

    private val cssVars: List<Pair<String, String>> = listOf(
        "--font-sans" to CssThemeVars.fontSans,
        "--font-heading" to CssThemeVars.fontHeading,
        "--font-mono" to CssThemeVars.fontMono,
        "--color-bg" to CssThemeVars.colorBg,
        "--color-text-primary" to CssThemeVars.colorTextPrimary,
        "--color-text-secondary" to CssThemeVars.colorTextSecondary,
        "--color-text-muted" to CssThemeVars.colorTextMuted,
        "--color-glass" to CssThemeVars.colorGlass,
        "--color-glass-border" to CssThemeVars.colorGlassBorder,
        "--color-glass-border-hover" to CssThemeVars.colorGlassBorderHover,
        "--color-glass-hover" to CssThemeVars.colorGlassHover,
        "--color-surface-1" to CssThemeVars.colorSurface1,
        "--color-surface-2" to CssThemeVars.colorSurface2,
        "--color-surface-1-border" to CssThemeVars.colorSurface1Border,
        "--color-surface-2-border" to CssThemeVars.colorSurface2Border,
        "--color-inset-highlight" to CssThemeVars.colorInsetHighlight,
        "--glass-surface-gradient" to CssThemeVars.glassSurfaceGradient,
        "--glass-bar-sheen" to CssThemeVars.glassBarSheen,
        "--shadow-surface-1" to CssThemeVars.shadowSurface1,
        "--shadow-surface-2" to CssThemeVars.shadowSurface2,
        "--inset-top-highlight" to CssThemeVars.insetTopHighlight,
        "--glow-blue-soft" to CssThemeVars.glowBlueSoft,
        "--glow-green-soft" to CssThemeVars.glowGreenSoft,
        "--glow-purple-soft" to CssThemeVars.glowPurpleSoft,
        "--color-blue-gradient-start" to CssThemeVars.colorBlueGradientStart,
        "--shadow-btn-primary" to CssThemeVars.shadowBtnPrimary,
        "--color-kraken-blue" to CssThemeVars.colorKrakenBlue,
        "--color-blue-primary" to CssThemeVars.colorBluePrimary,
        "--color-blue-hover" to CssThemeVars.colorBlueHover,
        "--color-blue-accent" to CssThemeVars.colorBlueAccent,
        "--color-green-accent" to CssThemeVars.colorGreenAccent,
        "--color-success" to CssThemeVars.colorSuccess,
        "--color-danger" to CssThemeVars.colorDanger,
        "--color-warning" to CssThemeVars.colorWarning,
        "--color-text-bright" to CssThemeVars.colorTextBright,
        "--color-danger-light" to CssThemeVars.colorDangerLight,
        "--color-muted-light" to CssThemeVars.colorMutedLight,
        "--color-blue-deep" to CssThemeVars.colorBlueDeep,
        "--color-border-muted" to CssThemeVars.colorBorderMuted,
        "--color-border-subtle" to CssThemeVars.colorBorderSubtle,
        "--color-border-faint" to CssThemeVars.colorBorderFaint,
        "--color-border-input" to CssThemeVars.colorBorderInput,
        "--color-border-input-strong" to CssThemeVars.colorBorderInputStrong,
        "--color-border-input-hover" to CssThemeVars.colorBorderInputHover,
        "--color-blue-glass-bg" to CssThemeVars.colorBlueGlassBg,
        "--color-blue-glass-border" to CssThemeVars.colorBlueGlassBorder,
        "--color-blue-glass-bg-hover" to CssThemeVars.colorBlueGlassBgHover,
        "--color-blue-glass-border-hover" to CssThemeVars.colorBlueGlassBorderHover,
        "--color-success-muted" to CssThemeVars.colorSuccessMuted,
        "--color-success-border" to CssThemeVars.colorSuccessBorder,
        "--color-success-bg-subtle" to CssThemeVars.colorSuccessBgSubtle,
        "--color-success-border-subtle" to CssThemeVars.colorSuccessBorderSubtle,
        "--color-danger-muted" to CssThemeVars.colorDangerMuted,
        "--color-danger-border" to CssThemeVars.colorDangerBorder,
        "--color-danger-bg-subtle" to CssThemeVars.colorDangerBgSubtle,
        "--color-danger-border-subtle" to CssThemeVars.colorDangerBorderSubtle,
        "--color-danger-bg-hover" to CssThemeVars.colorDangerBgHover,
        "--color-warning-muted" to CssThemeVars.colorWarningMuted,
        "--color-warning-border" to CssThemeVars.colorWarningBorder,
        "--color-slate-muted" to CssThemeVars.colorSlateMuted,
        "--color-slate-border" to CssThemeVars.colorSlateBorder,
        "--color-white-subtle" to CssThemeVars.colorWhiteSubtle,
        "--color-white-muted" to CssThemeVars.colorWhiteMuted,
        "--color-white-border" to CssThemeVars.colorWhiteBorder,
        "--color-white-faint" to CssThemeVars.colorWhiteFaint,
        "--color-icon-faint" to CssThemeVars.colorIconFaint,
        "--color-bg-glow-blue" to CssThemeVars.colorBgGlowBlue,
        "--color-bg-glow-green" to CssThemeVars.colorBgGlowGreen,
        "--color-bg-glow-purple" to CssThemeVars.colorBgGlowPurple,
        "--color-purple-accent" to CssThemeVars.colorPurpleAccent,
        "--color-purple-muted" to CssThemeVars.colorPurpleMuted,
        "--color-purple-border" to CssThemeVars.colorPurpleBorder,
        "--focus-ring-strong" to CssThemeVars.focusRingStrong,
        "--focus-ring-compact" to CssThemeVars.focusRingCompact,
        "--focus-ring-subtle" to CssThemeVars.focusRingSubtle,
        "--color-glass-surface-subtle" to CssThemeVars.colorGlassSurfaceSubtle,
        "--color-glass-surface-hover" to CssThemeVars.colorGlassSurfaceHover,
        "--color-glass-surface-input" to CssThemeVars.colorGlassSurfaceInput,
        "--color-glass-surface-faint" to CssThemeVars.colorGlassSurfaceFaint,
        "--shadow-scrim" to CssThemeVars.shadowScrim,
        "--shadow-scrim-soft" to CssThemeVars.shadowScrimSoft,
        "--glow-blue-strong" to CssThemeVars.glowBlueStrong,
        "--glow-amber-soft" to CssThemeVars.glowAmberSoft,
        "--glow-red-soft" to CssThemeVars.glowRedSoft,
        "--bar-track-gradient" to CssThemeVars.barTrackGradient,
        "--bar-fill-shadow" to CssThemeVars.barFillShadow,
        "--shimmer-gradient" to CssThemeVars.shimmerGradient,
        "--shimmer-gradient-alt" to CssThemeVars.shimmerGradientAlt,
        "--inset-shadow-dark" to CssThemeVars.insetShadowDark,
        "--shadow-badge" to CssThemeVars.shadowBadge,
        "--shadow-hero-card" to CssThemeVars.shadowHeroCard,
        "--filter-hero-icon" to CssThemeVars.filterHeroIcon,
        "--shadow-delta-down" to CssThemeVars.shadowDeltaDown,
        "--filter-hero-delta" to CssThemeVars.filterHeroDelta,
        "--radius-xs" to CssThemeVars.radiusXs,
        "--radius-sm" to CssThemeVars.radiusSm,
        "--radius-md" to CssThemeVars.radiusMd,
        "--radius-lg" to CssThemeVars.radiusLg,
        "--radius-xl" to CssThemeVars.radiusXl,
        "--radius-pill" to CssThemeVars.radiusPill,
    )

    fun CssBuilder.applyRootVariables() {
        ":root" {
            cssVars.forEach { (k, v) -> put(k, v) }
        }
    }
}
