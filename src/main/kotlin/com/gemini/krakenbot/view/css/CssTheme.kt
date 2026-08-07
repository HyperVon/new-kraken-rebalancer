package com.gemini.krakenbot.view.css

import kotlinx.css.Color
import kotlinx.css.CssBuilder
import kotlinx.css.px
import kotlinx.css.rem

object CssTheme {
    const val fontSans = "'Inter', system-ui, -apple-system, sans-serif"
    const val fontHeading = "'Outfit', 'Inter', system-ui, -apple-system, sans-serif"
    const val fontMono = "'Roboto Mono', monospace"

    val radiusPill = 9999.px
    val radiusXs = 0.25.rem
    val radiusSm = 0.375.rem
    val radiusMd = 0.5.rem
    val radiusLg = 0.75.rem
    val radiusXl = 0.875.rem

    val colorBg = Color("#030712")
    val colorTextPrimary = Color("#f8fafc")

    // GLOB-3: lifted for WCAG AA (>=4.5:1) on glass surfaces.
    val colorTextSecondary = Color("#a8b4c8")
    val colorTextMuted = Color("#7e8ba3")
    val colorGlass = Color("rgba(15, 23, 42, 0.72)")
    val colorGlassBorder = Color("rgba(96, 165, 250, 0.22)")
    val colorGlassBorderHover = Color("rgba(125, 185, 255, 0.4)")
    val colorGlassHover = Color("rgba(255, 255, 255, 0.04)")

    // Semi-glass raised cards: cool blue sheen (not white fog), clear lift off page.
    val colorSurface1 = Color("rgba(18, 28, 48, 0.9)")
    val colorSurface2 = Color("rgba(24, 36, 56, 0.94)")
    val colorSurface1Border = Color("rgba(96, 165, 250, 0.26)")
    val colorSurface2Border = Color("rgba(96, 165, 250, 0.3)")
    val colorInsetHighlight = Color("rgba(147, 197, 253, 0.2)")

    // Cool blue glass wash — luminous without milky white glare.
    const val glassSurfaceGradient =
        "linear-gradient(165deg, rgba(96,165,250,0.14) 0%, rgba(59,130,246,0.05) 20%, " +
            "rgba(18,28,48,0.92) 58%, rgba(12,20,36,0.96) 100%)"
    const val glassBarSheen =
        "linear-gradient(180deg, rgba(186,230,255,0.4) 0%, rgba(255,255,255,0.08) 38%, " +
            "rgba(0,0,0,0.16) 100%)"

    // Depth + soft cyan rim light (raised glass, not flat matte).
    const val shadowSurface1 =
        "0 1px 2px rgba(0,0,0,0.45), 0 10px 26px rgba(0,0,0,0.5), 0 0 26px rgba(56,189,248,0.1), " +
            "inset 0 1px 0 rgba(147,197,253,0.22), inset 0 -1px 0 rgba(0,0,0,0.28)"
    const val shadowSurface2 =
        "0 2px 4px rgba(0,0,0,0.5), 0 16px 36px rgba(0,0,0,0.55), 0 0 34px rgba(56,189,248,0.14), " +
            "inset 0 1px 0 rgba(147,197,253,0.28), inset 0 -1px 0 rgba(0,0,0,0.32)"
    const val insetTopHighlight = "inset 0 1px 0 rgba(147, 197, 253, 0.22)"
    const val glowBlueSoft = "0 0 16px rgba(56, 189, 248, 0.3)"
    const val glowGreenSoft = "0 0 14px rgba(16, 185, 129, 0.28)"
    const val glowPurpleSoft = "0 0 14px rgba(167, 139, 250, 0.32)"
    val colorBlueGradientStart = Color("rgba(96, 165, 250, 0.95)")
    const val shadowBtnPrimary =
        "0 0 18px rgba(59, 130, 246, 0.32), 0 6px 14px rgba(37, 99, 235, 0.28), inset 0 1px 0 rgba(255, 255, 255, 0.18)"
    val colorKrakenBlue = Color("#0052ff")
    val colorBluePrimary = Color("#3b82f6")
    val colorBlueHover = Color("#1d4ed8")
    val colorBlueAccent = Color("#60a5fa")
    val colorGreenAccent = Color("#34d399")
    val colorSuccess = Color("#10b981")
    val colorDanger = Color("#ef4444")
    val colorWarning = Color("#f59e0b")
    val colorTextBright = Color("#e2e8f0")
    val colorDangerLight = Color("#fecaca")
    val colorMutedLight = Color("#cbd5e1")
    val colorBlueDeep = Color("#2563eb")
    val colorBorderMuted = Color("rgba(51, 65, 85, 0.5)")
    val colorBorderSubtle = Color("rgba(51, 65, 85, 0.3)")
    val colorBorderFaint = Color("rgba(51, 65, 85, 0.2)")
    val colorBorderInput = Color("rgba(71, 85, 105, 0.5)")
    val colorBorderInputStrong = Color("rgba(71, 85, 105, 0.6)")
    val colorBorderInputHover = Color("rgba(148, 163, 184, 0.5)")
    val colorBlueGlassBg = Color("rgba(59, 130, 246, 0.1)")
    val colorBlueGlassBorder = Color("rgba(59, 130, 246, 0.2)")
    val colorBlueGlassBgHover = Color("rgba(59, 130, 246, 0.15)")
    val colorBlueGlassBorderHover = Color("rgba(59, 130, 246, 0.25)")
    val colorSuccessMuted = Color("rgba(16, 185, 129, 0.15)")
    val colorSuccessBorder = Color("rgba(16, 185, 129, 0.3)")
    val colorSuccessBgSubtle = Color("rgba(16, 185, 129, 0.1)")
    val colorSuccessBorderSubtle = Color("rgba(16, 185, 129, 0.2)")
    val colorDangerMuted = Color("rgba(239, 68, 68, 0.15)")
    val colorDangerBorder = Color("rgba(239, 68, 68, 0.3)")
    val colorDangerBgSubtle = Color("rgba(239, 68, 68, 0.1)")
    val colorDangerBorderSubtle = Color("rgba(239, 68, 68, 0.2)")
    val colorDangerBgHover = Color("rgba(239, 68, 68, 0.2)")
    val colorWarningMuted = Color("rgba(245, 158, 11, 0.15)")
    val colorWarningBorder = Color("rgba(245, 158, 11, 0.3)")
    val colorSlateMuted = Color("rgba(100, 116, 139, 0.15)")
    val colorSlateBorder = Color("rgba(100, 116, 139, 0.3)")
    val colorWhiteSubtle = Color("rgba(255, 255, 255, 0.02)")
    val colorWhiteMuted = Color("rgba(255, 255, 255, 0.05)")
    val colorWhiteBorder = Color("rgba(255, 255, 255, 0.1)")
    val colorWhiteFaint = Color("rgba(255, 255, 255, 0.06)")
    val colorIconFaint = Color("rgba(255, 255, 255, 0.1)")
    val colorBgGlowBlue = Color("rgba(56, 189, 248, 0.1)")
    val colorBgGlowGreen = Color("rgba(16, 185, 129, 0.055)")
    val colorBgGlowPurple = Color("rgba(139, 92, 246, 0.08)")
    val colorPurpleAccent = Color("#a78bfa")
    val colorPurpleMuted = Color("rgba(167, 139, 250, 0.18)")
    val colorPurpleBorder = Color("rgba(167, 139, 250, 0.35)")

    // Focus-visible rings shared across interactive controls (WCAG-visible affordance).
    const val focusRingStrong = "0 0 0 3px rgba(59, 130, 246, 0.45)"
    const val focusRingCompact = "0 0 0 2px rgba(59, 130, 246, 0.45)"
    const val focusRingSubtle = "0 0 0 3px rgba(59, 130, 246, 0.2)"

    // Translucent glass surfaces for secondary buttons / inputs.
    val colorGlassSurfaceSubtle = Color("rgba(30, 41, 59, 0.5)")
    val colorGlassSurfaceHover = Color("rgba(30, 41, 59, 0.8)")
    val colorGlassSurfaceInput = Color("rgba(15, 23, 42, 0.4)")
    val colorGlassSurfaceFaint = Color("rgba(15, 23, 42, 0.2)")

    // Reusable shadow scrims (kept out of bespoke composite shadows below).
    const val shadowScrim = "rgba(0,0,0,0.5)"
    const val shadowScrimSoft = "rgba(0,0,0,0.4)"

    // Trading-mode plate glows (combined with insetTopHighlight at call sites).
    const val glowBlueStrong = "0 0 16px rgba(59, 130, 246, 0.25)"
    const val glowAmberSoft = "0 0 14px rgba(245, 158, 11, 0.22)"
    const val glowRedSoft = "0 0 14px rgba(239, 68, 68, 0.22)"

    // Tokenized component shadows/filters (extracted from ComponentStyles raw literals — CI-29-U01 + CI-28-U01)
    const val barTrackGradient = "linear-gradient(180deg, rgba(255,255,255,0.04), rgba(255,255,255,0.08))"
    const val barFillShadow = "inset 0 1px 0 rgba(255,255,255,0.35), 0 0 12px rgba(255,255,255,0.12)"
    const val shimmerGradient = "linear-gradient(90deg, transparent, rgba(186,230,255,0.48), transparent)"
    const val shimmerGradientAlt = "linear-gradient(90deg, transparent, rgba(186,230,255,0.45), transparent)"
    const val insetShadowDark = "inset 0 1px 2px rgba(0,0,0,0.35)"
    const val shadowBadge = "0 4px 6px -1px rgba(0, 0, 0, 0.1)"
    const val shadowHeroCard =
        "0 2px 4px rgba(0,0,0,0.5), 0 16px 36px rgba(0,0,0,0.52), 0 0 32px rgba(56,189,248,0.12), " +
            "inset 0 1px 0 rgba(147,197,253,0.26), inset 0 -1px 0 rgba(0,0,0,0.28)"
    const val filterHeroIcon = "drop-shadow(0 0 6px rgba(96, 165, 250, 0.55))"
    const val shadowDeltaDown = "0 0 16px rgba(239, 68, 68, 0.3)"
    const val filterHeroDelta = "drop-shadow(0 0 8px rgba(59, 130, 246, 0.45))"

    fun CssBuilder.applyRootVariables() {
        ":root" {
            put("--font-sans", fontSans)
            put("--font-heading", fontHeading)
            put("--font-mono", fontMono)
            put("--color-bg", colorBg.value)
            put("--color-text-primary", colorTextPrimary.value)
            put("--color-text-secondary", colorTextSecondary.value)
            put("--color-text-muted", colorTextMuted.value)
            put("--color-glass", colorGlass.value)
            put("--color-glass-border", colorGlassBorder.value)
            put("--color-glass-border-hover", colorGlassBorderHover.value)
            put("--color-glass-hover", colorGlassHover.value)
            put("--color-surface-1", colorSurface1.value)
            put("--color-surface-2", colorSurface2.value)
            put("--color-surface-1-border", colorSurface1Border.value)
            put("--color-surface-2-border", colorSurface2Border.value)
            put("--color-inset-highlight", colorInsetHighlight.value)
            put("--glass-surface-gradient", glassSurfaceGradient)
            put("--glass-bar-sheen", glassBarSheen)
            put("--shadow-surface-1", shadowSurface1)
            put("--shadow-surface-2", shadowSurface2)
            put("--inset-top-highlight", insetTopHighlight)
            put("--glow-blue-soft", glowBlueSoft)
            put("--glow-green-soft", glowGreenSoft)
            put("--glow-purple-soft", glowPurpleSoft)
            put("--color-blue-gradient-start", colorBlueGradientStart.value)
            put("--shadow-btn-primary", shadowBtnPrimary)
            put("--color-kraken-blue", colorKrakenBlue.value)
            put("--color-blue-primary", colorBluePrimary.value)
            put("--color-blue-hover", colorBlueHover.value)
            put("--color-blue-accent", colorBlueAccent.value)
            put("--color-green-accent", colorGreenAccent.value)
            put("--color-success", colorSuccess.value)
            put("--color-danger", colorDanger.value)
            put("--color-warning", colorWarning.value)
            put("--color-text-bright", colorTextBright.value)
            put("--color-danger-light", colorDangerLight.value)
            put("--color-muted-light", colorMutedLight.value)
            put("--color-blue-deep", colorBlueDeep.value)
            put("--color-border-muted", colorBorderMuted.value)
            put("--color-border-subtle", colorBorderSubtle.value)
            put("--color-border-faint", colorBorderFaint.value)
            put("--color-border-input", colorBorderInput.value)
            put("--color-border-input-strong", colorBorderInputStrong.value)
            put("--color-border-input-hover", colorBorderInputHover.value)
            put("--color-blue-glass-bg", colorBlueGlassBg.value)
            put("--color-blue-glass-border", colorBlueGlassBorder.value)
            put("--color-blue-glass-bg-hover", colorBlueGlassBgHover.value)
            put("--color-blue-glass-border-hover", colorBlueGlassBorderHover.value)
            put("--color-success-muted", colorSuccessMuted.value)
            put("--color-success-border", colorSuccessBorder.value)
            put("--color-success-bg-subtle", colorSuccessBgSubtle.value)
            put("--color-success-border-subtle", colorSuccessBorderSubtle.value)
            put("--color-danger-muted", colorDangerMuted.value)
            put("--color-danger-border", colorDangerBorder.value)
            put("--color-danger-bg-subtle", colorDangerBgSubtle.value)
            put("--color-danger-border-subtle", colorDangerBorderSubtle.value)
            put("--color-danger-bg-hover", colorDangerBgHover.value)
            put("--color-warning-muted", colorWarningMuted.value)
            put("--color-warning-border", colorWarningBorder.value)
            put("--color-slate-muted", colorSlateMuted.value)
            put("--color-slate-border", colorSlateBorder.value)
            put("--color-white-subtle", colorWhiteSubtle.value)
            put("--color-white-muted", colorWhiteMuted.value)
            put("--color-white-border", colorWhiteBorder.value)
            put("--color-white-faint", colorWhiteFaint.value)
            put("--color-icon-faint", colorIconFaint.value)
            put("--color-bg-glow-blue", colorBgGlowBlue.value)
            put("--color-bg-glow-green", colorBgGlowGreen.value)
            put("--color-bg-glow-purple", colorBgGlowPurple.value)
            put("--color-purple-accent", colorPurpleAccent.value)
            put("--color-purple-muted", colorPurpleMuted.value)
            put("--color-purple-border", colorPurpleBorder.value)
            put("--focus-ring-strong", focusRingStrong)
            put("--focus-ring-compact", focusRingCompact)
            put("--focus-ring-subtle", focusRingSubtle)
            put("--color-glass-surface-subtle", colorGlassSurfaceSubtle.value)
            put("--color-glass-surface-hover", colorGlassSurfaceHover.value)
            put("--color-glass-surface-input", colorGlassSurfaceInput.value)
            put("--color-glass-surface-faint", colorGlassSurfaceFaint.value)
            put("--shadow-scrim", shadowScrim)
            put("--shadow-scrim-soft", shadowScrimSoft)
            put("--glow-blue-strong", glowBlueStrong)
            put("--glow-amber-soft", glowAmberSoft)
            put("--glow-red-soft", glowRedSoft)
            put("--bar-track-gradient", barTrackGradient)
            put("--bar-fill-shadow", barFillShadow)
            put("--shimmer-gradient", shimmerGradient)
            put("--shimmer-gradient-alt", shimmerGradientAlt)
            put("--inset-shadow-dark", insetShadowDark)
            put("--shadow-badge", shadowBadge)
            put("--shadow-hero-card", shadowHeroCard)
            put("--filter-hero-icon", filterHeroIcon)
            put("--shadow-delta-down", shadowDeltaDown)
            put("--filter-hero-delta", filterHeroDelta)
            put("--radius-xs", radiusXs.value)
            put("--radius-sm", radiusSm.value)
            put("--radius-md", radiusMd.value)
            put("--radius-lg", radiusLg.value)
            put("--radius-xl", radiusXl.value)
            put("--radius-pill", radiusPill.value)
        }
    }
}
