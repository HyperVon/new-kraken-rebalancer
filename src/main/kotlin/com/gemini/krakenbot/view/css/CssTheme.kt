package com.gemini.krakenbot.view.css

import kotlinx.css.Color
import kotlinx.css.CssBuilder
import kotlinx.css.px

object CssTheme {
    // Font Stacks
    const val fontSans = "'Inter', system-ui, -apple-system, sans-serif"
    const val fontHeading = "'Outfit', 'Inter', system-ui, -apple-system, sans-serif"
    const val fontMono = "'Roboto Mono', monospace"

    // Radii
    val radiusPill = 9999.px

    // Color Tokens
    val colorBg = Color("#030712")
    val colorTextPrimary = Color("#f8fafc")
    val colorTextSecondary = Color("#94a3b8")
    val colorTextMuted = Color("#64748b")
    val colorGlass = Color("rgba(15, 23, 42, 0.6)")
    val colorGlassBorder = Color("rgba(255, 255, 255, 0.08)")
    val colorGlassBorderHover = Color("rgba(255, 255, 255, 0.18)")
    val colorGlassHover = Color("rgba(255, 255, 255, 0.04)")
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
    val colorScrollbarThumb = Color("rgba(148, 163, 184, 0.15)")
    val colorScrollbarThumbHover = Color("rgba(148, 163, 184, 0.3)")
    val colorIconFaint = Color("rgba(255, 255, 255, 0.1)")
    val colorBgGlowBlue = Color("rgba(56, 189, 248, 0.06)")
    val colorBgGlowGreen = Color("rgba(16, 185, 129, 0.06)")

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
            put("--color-kraken-blue", colorKrakenBlue.value)
            put("--color-success", colorSuccess.value)
            put("--color-danger", colorDanger.value)
            put("--color-warning", colorWarning.value)
        }
    }
}
