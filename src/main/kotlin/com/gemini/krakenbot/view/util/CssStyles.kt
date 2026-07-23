package com.gemini.krakenbot.view.util

import kotlinx.css.*
import kotlinx.css.properties.*

object CssStyles {
    // Theme Constants (Compile-time verified)
    private const val fontSans = "'Inter', system-ui, -apple-system, sans-serif"
    private const val fontHeading = "'Outfit', 'Inter', system-ui, -apple-system, sans-serif"
    private const val fontMono = "'Roboto Mono', monospace"

    private val colorBg = Color("#030712")
    private val colorTextPrimary = Color("#f8fafc")
    private val colorTextSecondary = Color("#94a3b8")
    private val colorTextMuted = Color("#64748b")
    private val colorGlass = Color("rgba(15, 23, 42, 0.6)")
    private val colorGlassBorder = Color("rgba(255, 255, 255, 0.08)")
    private val colorGlassBorderHover = Color("rgba(255, 255, 255, 0.18)")
    private val colorKrakenBlue = Color("#0052ff")
    private val colorSuccess = Color("#10b981")
    private val colorDanger = Color("#ef4444")
    private val colorWarning = Color("#f59e0b")

    val stylesheet = CssBuilder().apply {
        // 2. CSS variables for client-side / fallback consumption
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

        // 3. Global resets
        "*" {
            boxSizing = BoxSizing.borderBox
            margin = Margin(0.px)
            padding = Padding(0.px)
        }

        body {
            backgroundColor = colorBg
            color = colorTextPrimary
            fontFamily = fontSans
            put("-webkit-font-smoothing", "antialiased")
            put("-moz-osx-font-smoothing", "grayscale")
            minHeight = 100.vh
            put("line-height", "1.5")
            put("background-image", "radial-gradient(circle at 15% 50%, rgba(56, 189, 248, 0.06) 0%, transparent 50%), radial-gradient(circle at 85% 30%, rgba(16, 185, 129, 0.06) 0%, transparent 50%)")
            backgroundAttachment = BackgroundAttachment.fixed
        }

        // 4. Containers & Layout
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
            borderBottomColor = Color("rgba(51, 65, 85, 0.5)")
            marginBottom = 1.25.rem
        }

        ".${CssClass.Layout.HeaderTitleSection}" {
            display = Display.flex
            alignItems = Align.center
            gap = 0.75.rem
        }

        ".${CssClass.Layout.HeaderTitleSection} h1" {
            fontFamily = fontHeading
            fontSize = 1.5.rem
            fontWeight = FontWeight.w800
            background = "linear-gradient(90deg, #60a5fa, #34d399)"
            put("-webkit-background-clip", "text")
            put("background-clip", "text")
            put("-webkit-text-fill-color", "transparent")
            put("letter-spacing", "-0.025em")
        }

        // 5. Status Badges & Animations
        ".status-badge" {
            padding = Padding(0.125.rem, 0.625.rem)
            borderRadius = 9999.px
            fontSize = 0.75.rem
            fontWeight = FontWeight.w700
            put("letter-spacing", "0.05em")
            put("box-shadow", "0 4px 6px -1px rgba(0, 0, 0, 0.1)")
        }

        ".status-badge.live" {
            backgroundColor = Color("rgba(16, 185, 129, 0.15)")
            color = colorSuccess
            borderWidth = 1.px
            borderStyle = BorderStyle.solid
            borderColor = Color("rgba(16, 185, 129, 0.3)")
            put("animation", "pulse 2s cubic-bezier(0.4, 0, 0.6, 1) infinite")
        }

        ".status-badge.delayed" {
            backgroundColor = Color("rgba(245, 158, 11, 0.15)")
            color = colorWarning
            borderWidth = 1.px
            borderStyle = BorderStyle.solid
            borderColor = Color("rgba(245, 158, 11, 0.3)")
        }

        ".status-badge.offline" {
            backgroundColor = Color("rgba(100, 116, 139, 0.15)")
            color = colorTextSecondary
            borderWidth = 1.px
            borderStyle = BorderStyle.solid
            borderColor = Color("rgba(100, 116, 139, 0.3)")
        }

        "@keyframes pulse" {
            "0%, 100%" {
                put("opacity", "1")
            }
            "50%" {
                put("opacity", ".6")
            }
        }

        // 6. Header Actions & Age Tracking
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
            color = colorTextMuted
            put("text-transform", "uppercase")
            put("letter-spacing", "0.05em")
            fontWeight = FontWeight.w600
        }

        ".${CssClass.DataAge.Value}" {
            fontFamily = fontMono
            fontSize = 0.875.rem
            fontWeight = FontWeight.w700
            color = Color("#e2e8f0")
        }

        ".${CssClass.DataAge.Value}.stale" {
            color = colorWarning
        }

        ".${CssClass.DataAge.Time}" {
            fontSize = 0.75.rem
            color = colorTextMuted
        }

        // 7. Glass Panel Styling
        ".${CssClass.Layout.GlassPanel}" {
            background = colorGlass.value
            put("backdrop-filter", "blur(20px)")
            put("-webkit-backdrop-filter", "blur(20px)")
            borderWidth = 1.px
            borderStyle = BorderStyle.solid
            borderColor = colorGlassBorder
            put("box-shadow", "0 25px 50px -12px rgba(0, 0, 0, 0.5)")
            borderRadius = 1.25.rem
            padding = Padding(1.5.rem)
            put("transition", "all 0.3s cubic-bezier(0.4, 0, 0.2, 1)")
        }

        ".${CssClass.Layout.GlassPanel}:hover" {
            borderColor = colorGlassBorderHover
            put("box-shadow", "0 0 30px rgba(56, 189, 248, 0.08), 0 25px 50px -12px rgba(0, 0, 0, 0.5)")
        }

        ".${CssClass.Layout.Container} > .${CssClass.Layout.GlassPanel}" {
            marginBottom = 1.25.rem
        }

        ".${CssClass.Utility.GlassPanelTitle}" {
            fontSize = 0.875.rem
            fontWeight = FontWeight.w600
            color = colorTextSecondary
            put("text-transform", "uppercase")
            put("letter-spacing", "0.05em")
            marginBottom = 0.75.rem
            display = Display.flex
            alignItems = Align.center
            gap = 0.5.rem
        }

        ".${CssClass.Utility.GlassPanelTitle} svg" {
            color = Color("#60a5fa")
        }

        // 8. Grid Layouts
        ".${CssClass.Layout.OverviewGrid}" {
            display = Display.grid
            put("grid-template-columns", "1fr")
            gap = 1.rem
            marginBottom = 1.25.rem
        }

        ".status-card" {
            display = Display.flex
            flexDirection = FlexDirection.column
            gap = 0.5.rem
        }

        ".${CssClass.StatusCard.Header}" {
            display = Display.flex
            put("justify-content", "space-between")
            alignItems = Align.center
        }

        ".${CssClass.StatusCard.Title}" {
            fontSize = 0.875.rem
            fontWeight = FontWeight.w600
            color = colorTextSecondary
        }

        ".${CssClass.StatusCard.Icon}" {
            display = Display.flex
            alignItems = Align.center
            put("justify-content", "center")
            width = 2.rem
            height = 2.rem
            borderRadius = 0.5.rem
            background = "rgba(255, 255, 255, 0.04)"
            color = colorTextSecondary
        }

        ".${CssClass.StatusCard.Value}" {
            fontSize = 1.75.rem
            fontWeight = FontWeight.w700
            fontFamily = fontHeading
            put("letter-spacing", "-0.02em")
        }

        ".status-card.success .${CssClass.StatusCard.Value}" {
            color = colorSuccess
        }

        ".${CssClass.StatusCard.Sub}" {
            put("margin-top", "auto")
            fontSize = 0.75.rem
            color = colorTextSecondary
        }

        ".${CssClass.Layout.DetailGrid}" {
            display = Display.grid
            put("grid-template-columns", "1fr")
            gap = 1.25.rem
            marginBottom = 1.25.rem
        }

        // 9. Allocation Charts
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
            color = colorTextPrimary
            fontSize = 0.875.rem
        }

        ".${CssClass.AllocationChart.BarTrack}" {
            flexGrow = 1.0
            height = 0.75.rem
            background = "rgba(255, 255, 255, 0.05)"
            borderRadius = 9999.px
            overflow = Overflow.hidden
            borderWidth = 1.px
            borderStyle = BorderStyle.solid
            borderColor = Color("rgba(255, 255, 255, 0.02)")
        }

        ".${CssClass.AllocationChart.BarFill}" {
            height = 100.pct
            borderRadius = 9999.px
            background = "linear-gradient(90deg, #3b82f6, #10b981)"
            put("transition", "width 0.8s cubic-bezier(0.4, 0, 0.2, 1)")
        }

        ".${CssClass.AllocationChart.BarValue}" {
            width = 9.5.rem
            textAlign = TextAlign.right
            fontFamily = fontMono
            fontSize = 0.75.rem
            color = colorTextSecondary
        }

        // 10. Tables
        ".${CssClass.Table.Wrapper}" {
            overflowX = Overflow.auto
            marginTop = 0.px
            marginBottom = 0.px
            marginLeft = (-1.5).rem
            marginRight = (-1.5).rem
            paddingLeft = 1.5.rem
            paddingRight = 1.5.rem
        }

        table {
            width = 100.pct
            borderCollapse = BorderCollapse.collapse
            textAlign = TextAlign.left
            fontSize = 0.875.rem
        }

        thead {
            borderBottomWidth = 1.px
            borderBottomStyle = BorderStyle.solid
            borderBottomColor = Color("rgba(51, 65, 85, 0.5)")
        }

        th {
            padding = Padding(0.75.rem, 0.5.rem)
            fontWeight = FontWeight.w600
            color = colorTextSecondary
            fontSize = 0.75.rem
            put("text-transform", "uppercase")
            put("letter-spacing", "0.05em")
        }

        td {
            padding = Padding(0.75.rem, 0.5.rem)
            put("vertical-align", "middle")
            borderBottomWidth = 1.px
            borderBottomStyle = BorderStyle.solid
            borderBottomColor = Color("rgba(51, 65, 85, 0.2)")
        }

        "tr:last-child td" {
            borderBottomStyle = BorderStyle.none
        }

        "tr.hoverable:hover" {
            backgroundColor = Color("rgba(255, 255, 255, 0.02)")
        }

        ".${CssClass.Table.SymbolCol}" {
            fontWeight = FontWeight.w700
            color = colorTextPrimary
        }

        ".${CssClass.Table.MonoCol}" {
            fontFamily = fontMono
        }

        // 11. Badges
        ".badge" {
            display = Display.inlineFlex
            alignItems = Align.center
            padding = Padding(0.125.rem, 0.5.rem)
            borderRadius = 0.375.rem
            fontSize = 0.675.rem
            fontWeight = FontWeight.w700
            put("letter-spacing", "0.05em")
            backgroundColor = Color("rgba(255, 255, 255, 0.05)")
            borderWidth = 1.px
            borderStyle = BorderStyle.solid
            borderColor = Color("rgba(255, 255, 255, 0.1)")
            color = colorTextSecondary
        }

        ".badge.badge-buy" {
            backgroundColor = Color("rgba(16, 185, 129, 0.1)")
            borderColor = Color("rgba(16, 185, 129, 0.2)")
            color = colorSuccess
        }

        ".badge.badge-sell" {
            backgroundColor = Color("rgba(239, 68, 68, 0.1)")
            borderColor = Color("rgba(239, 68, 68, 0.2)")
            color = colorDanger
        }

        ".badge.badge-info" {
            backgroundColor = Color("rgba(59, 130, 246, 0.1)")
            borderColor = Color("rgba(59, 130, 246, 0.2)")
            color = Color("#60a5fa")
        }

        // 12. Buttons
        ".btn" {
            display = Display.inlineFlex
            alignItems = Align.center
            gap = 0.5.rem
            padding = Padding(0.5.rem, 1.rem)
            borderRadius = 0.5.rem
            fontSize = 0.875.rem
            fontWeight = FontWeight.w600
            cursor = Cursor.pointer
            borderStyle = BorderStyle.none
            put("transition", "all 0.2s ease")
            textDecoration = TextDecoration.none
            color = Color.inherit
        }

        ".btn-primary" {
            background = "linear-gradient(135deg, #2563eb, #1d4ed8)"
            color = Color.white
            put("box-shadow", "0 4px 10px rgba(37, 99, 235, 0.2)")
        }

        ".btn-primary:hover" {
            background = "linear-gradient(135deg, #3b82f6, #2563eb)"
            put("box-shadow", "0 4px 15px rgba(37, 99, 235, 0.4)")
        }

        ".btn-secondary" {
            background = "rgba(30, 41, 59, 0.5)"
            borderWidth = 1.px
            borderStyle = BorderStyle.solid
            borderColor = Color("rgba(71, 85, 105, 0.5)")
            color = Color("#cbd5e1")
        }

        ".btn-secondary:hover" {
            background = "rgba(30, 41, 59, 0.8)"
            borderColor = Color("rgba(148, 163, 184, 0.5)")
            color = Color.white
        }

        ".btn-danger" {
            background = "rgba(239, 68, 68, 0.1)"
            borderWidth = 1.px
            borderStyle = BorderStyle.solid
            borderColor = Color("rgba(239, 68, 68, 0.3)")
            color = colorDanger
        }

        ".btn-danger:hover" {
            background = "rgba(239, 68, 68, 0.2)"
            borderColor = colorDanger
        }

        ".btn:active" {
            transform { scale(0.97) }
        }

        ".btn:disabled" {
            put("opacity", "0.5")
            cursor = Cursor.notAllowed
            put("transform", "none")
        }

        // 13. Settings Form Elements
        ".${CssClass.Form.Section}" {
            borderBottomWidth = 1.px
            borderBottomStyle = BorderStyle.solid
            borderBottomColor = Color("rgba(51, 65, 85, 0.3)")
            paddingBottom = 1.25.rem
            marginBottom = 1.25.rem
        }

        ".${CssClass.Form.Section}:last-of-type" {
            borderBottomStyle = BorderStyle.none
            paddingBottom = 0.px
            marginBottom = 0.px
        }

        ".${CssClass.Form.SectionTitle}" {
            fontSize = 1.125.rem
            fontWeight = FontWeight.w600
            color = Color.white
            marginBottom = 1.25.rem
            display = Display.flex
            alignItems = Align.center
            gap = 0.5.rem
        }

        ".${CssClass.Form.Grid2Col}" {
            display = Display.grid
            put("grid-template-columns", "1fr")
            gap = 1.25.rem
        }

        ".${CssClass.Form.Group}" {
            display = Display.flex
            flexDirection = FlexDirection.column
            gap = 0.5.rem
        }

        ".${CssClass.Form.Label}" {
            fontSize = 0.875.rem
            fontWeight = FontWeight.w500
            color = colorTextSecondary
        }

        ".${CssClass.Form.InputGlass}" {
            background = "rgba(15, 23, 42, 0.4)"
            borderWidth = 1.px
            borderStyle = BorderStyle.solid
            borderColor = Color("rgba(71, 85, 105, 0.5)")
            color = Color.white
            padding = Padding(0.5.rem, 0.75.rem)
            borderRadius = 0.5.rem
            fontFamily = "inherit"
            fontSize = 0.875.rem
            put("transition", "all 0.2s ease")
        }

        ".${CssClass.Form.InputGlass}:focus" {
            put("outline", "none")
            borderColor = Color("#3b82f6")
            put("box-shadow", "0 0 0 3px rgba(59, 130, 246, 0.2)")
        }

        ".${CssClass.Form.CheckboxContainer}" {
            display = Display.flex
            alignItems = Align.center
            gap = 0.75.rem
            cursor = Cursor.pointer
            put("user-select", "none")
        }

        ".${CssClass.Form.CheckboxCustom}" {
            position = Position.relative
            width = 1.25.rem
            height = 1.25.rem
            borderWidth = 2.px
            borderStyle = BorderStyle.solid
            borderColor = Color("rgba(71, 85, 105, 0.6)")
            borderRadius = 0.25.rem
            put("transition", "all 0.2s ease")
        }

        "input[type=\"checkbox\"]" {
            display = Display.none
        }

        "input[type=\"checkbox\"]:checked + .${CssClass.Form.CheckboxCustom}" {
            backgroundColor = colorKrakenBlue
            borderColor = colorKrakenBlue
        }

        "input[type=\"checkbox\"]:checked + .${CssClass.Form.CheckboxCustom}::after" {
            content = QuotedString("")
            position = Position.absolute
            left = 0.35.rem
            top = 0.1.rem
            width = 0.25.rem
            height = 0.5.rem
            borderStyle = BorderStyle.solid
            borderColor = Color.white
            put("border-width", "0 2px 2px 0")
            transform { rotate(45.deg) }
        }

        ".${CssClass.Form.AllocationListContainer}" {
            display = Display.grid
            put("grid-template-columns", "1fr")
            gap = 0.75.rem
            marginBottom = 1.25.rem
        }

        ".${CssClass.Form.AllocationEditRow}" {
            display = Display.flex
            alignItems = Align.center
            gap = 0.75.rem
            background = "rgba(15, 23, 42, 0.3)"
            padding = Padding(0.5.rem, 0.75.rem)
            borderRadius = 0.75.rem
            borderWidth = 1.px
            borderStyle = BorderStyle.solid
            borderColor = Color("rgba(51, 65, 85, 0.3)")
        }

        ".${CssClass.Form.AllocationEditSymbol}" {
            width = 3.5.rem
            fontWeight = FontWeight.w700
            fontSize = 0.9375.rem
            color = colorTextPrimary
        }

        ".${CssClass.Form.AllocationEditInputWrapper}" {
            position = Position.relative
            flexGrow = 1.0
        }

        ".${CssClass.Form.AllocationEditInputWrapper} input" {
            width = 100.pct
            paddingRight = 1.75.rem
        }

        ".${CssClass.Form.PercentSuffix}" {
            position = Position.absolute
            right = 0.75.rem
            top = 50.pct
            transform { translateY((-50).pct) }
            color = colorTextMuted
            fontWeight = FontWeight.w500
            fontSize = 0.8125.rem
        }

        ".${CssClass.Form.AddAssetBox}" {
            display = Display.flex
            gap = 1.rem
            padding = Padding(0.75.rem)
            borderRadius = 0.75.rem
            borderWidth = 1.px
            borderStyle = BorderStyle.dashed
            borderColor = Color("rgba(71, 85, 105, 0.5)")
            background = "rgba(15, 23, 42, 0.2)"
        }

        // 14. Scrollbars & Max Heights
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
            backgroundColor = Color("rgba(148, 163, 184, 0.15)")
            borderRadius = 9999.px
        }

        ".custom-scrollbar::-webkit-scrollbar-thumb:hover" {
            backgroundColor = Color("rgba(148, 163, 184, 0.3)")
        }

        ".max-h-100" {
            maxHeight = 25.rem
        }

        // 15. Spinners & Loading States
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
            borderColor = colorKrakenBlue
            put("border-top-color", "transparent")
            borderRadius = 50.pct
            put("animation", "spin 1s linear infinite")
        }

        "@keyframes spin" {
            "to" {
                transform { rotate(360.deg) }
            }
        }

        // 16. Empty State Blocks
        ".${CssClass.Activity.EmptyHistoryBox}, .history-empty" {
            display = Display.flex
            flexDirection = FlexDirection.column
            alignItems = Align.center
            put("justify-content", "center")
            padding = Padding(4.rem, 1.rem)
            color = colorTextMuted
            textAlign = TextAlign.center
            gap = 0.5.rem
        }

        ".${CssClass.Activity.EmptyHistoryBox} svg" {
            color = Color("rgba(255, 255, 255, 0.1)")
            marginBottom = 0.5.rem
        }

        ".${CssClass.Activity.EmptyHistoryBox} h3" {
            color = colorTextSecondary
        }

        // 17. Helpers
        ".text-success" {
            color = colorSuccess
        }

        ".${CssClass.Utility.TextDanger}" {
            color = colorDanger
        }

        // 18. Table Sorting
        "th.sortable" {
            cursor = Cursor.pointer
            put("user-select", "none")
        }

        "th.sortable:hover" {
            color = colorTextPrimary
        }

        "th.sortable::after" {
            content = QuotedString("")
            marginLeft = 0.35.rem
            fontSize = 0.7.rem
            put("opacity", "0.4")
        }

        "th.sortable.asc::after" {
            content = QuotedString("▲")
            put("opacity", "1")
        }

        "th.sortable.desc::after" {
            content = QuotedString("▼")
            put("opacity", "1")
        }

        // 19. Toasts & Notifications
        ".toast" {
            position = Position.fixed
            bottom = 2.rem
            right = 2.rem
            padding = Padding(1.rem, 1.5.rem)
            borderRadius = 0.5.rem
            color = Color.white
            fontWeight = FontWeight.w500
            display = Display.flex
            alignItems = Align.center
            gap = 0.75.rem
            put("box-shadow", "0 10px 15px -3px rgba(0, 0, 0, 0.3)")
            zIndex = 1000
            put("animation", "slideIn 0.3s ease")
        }

        ".toast.success" {
            backgroundColor = colorSuccess
        }

        ".toast.error" {
            backgroundColor = colorDanger
        }

        "@keyframes slideIn" {
            "from" {
                transform { translateY(1.rem) }
                put("opacity", "0")
            }
            "to" {
                transform { translateY(0.rem) }
                put("opacity", "1")
            }
        }

        // 20. Recent Activity Helpers
        ".${CssClass.Activity.EmptyText}" {
            color = colorTextMuted
            fontStyle = FontStyle.italic
            display = Display.flex
            alignItems = Align.center
            gap = 0.5.rem
        }

        ".${CssClass.Activity.DotMarker}" {
            width = 0.375.rem
            height = 0.375.rem
            borderRadius = 50.pct
            backgroundColor = colorTextMuted
        }

        ".${CssClass.Activity.RowContainer}" {
            display = Display.flex
            alignItems = Align.center
            gap = 0.75.rem
        }

        ".${CssClass.Performance.DevContainer}" {
            display = Display.flex
            flexDirection = FlexDirection.column
            put("line-height", "1.1")
        }

        ".${CssClass.Performance.DevUsdLabel}" {
            fontSize = 0.675.rem
            put("opacity", "0.7")
            fontFamily = fontMono
        }

        ".${CssClass.Button.Icon}" {
            padding = Padding(0.5.rem)
        }

        ".${CssClass.Utility.ErrorBanner}" {
            backgroundColor = Color("rgba(239, 68, 68, 0.15)")
            borderWidth = 1.px
            borderStyle = BorderStyle.solid
            borderColor = Color("rgba(239, 68, 68, 0.3)")
            color = Color("#fecaca")
            padding = Padding(1.rem)
            borderRadius = 0.5.rem
            marginBottom = 1.5.rem
            fontWeight = FontWeight.w500
        }

        ".${CssClass.Form.GroupCentered}" {
            put("justify-content", "center")
            paddingTop = 1.rem
        }

        ".${CssClass.Form.SectionHeader}" {
            display = Display.flex
            put("justify-content", "space-between")
            alignItems = Align.center
            marginBottom = 1.25.rem
        }

        ".${CssClass.Form.SectionHeader} h3" {
            fontSize = 1.125.rem
            fontWeight = FontWeight.w600
            color = Color.white
            margin = Margin(0.px)
        }

        ".add-asset-box input" {
            put("text-transform", "uppercase")
            flexGrow = 1.0
        }

        ".${CssClass.Dashboard.WaitingTitle}" {
            fontSize = 1.25.rem
            fontWeight = FontWeight.w600
            color = Color("#e2e8f0")
        }

        ".${CssClass.Dashboard.WaitingText}" {
            color = Color("#94a3b8")
            fontSize = 0.875.rem
            textAlign = TextAlign.center
            maxWidth = 24.rem
        }

        ".${CssClass.Navigation.Bar}" {
            display = Display.flex
            alignItems = Align.center
            gap = 0.25.rem
        }

        ".${CssClass.Navigation.Link}" {
            display = Display.inlineFlex
            alignItems = Align.center
            gap = 0.375.rem
            padding = Padding(0.375.rem, 0.875.rem)
            borderRadius = 0.5.rem
            fontSize = 0.875.rem
            fontWeight = FontWeight.w500
            color = colorTextSecondary
            textDecoration = TextDecoration.none
            put("transition", "all 0.2s ease")
            borderWidth = 1.px
            borderStyle = BorderStyle.solid
            borderColor = Color.transparent
        }

        ".${CssClass.Navigation.Link}:hover" {
            color = colorTextPrimary
            background = "rgba(255, 255, 255, 0.04)"
            borderColor = Color("rgba(255, 255, 255, 0.06)")
        }

        ".${CssClass.Navigation.LinkActive.value.replace(" ", ".")}" {
            color = colorTextPrimary
            background = "rgba(59, 130, 246, 0.1)"
            borderColor = Color("rgba(59, 130, 246, 0.2)")
            fontWeight = FontWeight.w600
        }

        ".${CssClass.History.TimeRangeSelector}" {
            display = Display.flex
            alignItems = Align.center
            gap = 0.375.rem
            marginBottom = 1.25.rem
            padding = Padding(0.25.rem)
            background = colorGlass.value
            borderWidth = 1.px
            borderStyle = BorderStyle.solid
            borderColor = colorGlassBorder
            borderRadius = 0.75.rem
            put("width", "fit-content")
        }

        ".${CssClass.History.TimeRangeBtn}" {
            padding = Padding(0.375.rem, 1.rem)
            borderRadius = 0.5.rem
            fontSize = 0.8125.rem
            fontWeight = FontWeight.w600
            color = colorTextSecondary
            background = "transparent"
            borderWidth = 1.px
            borderStyle = BorderStyle.solid
            borderColor = Color.transparent
            cursor = Cursor.pointer
            put("transition", "all 0.2s ease")
            fontFamily = fontSans
        }

        ".${CssClass.History.TimeRangeBtn}:hover" {
            color = colorTextPrimary
            background = "rgba(255, 255, 255, 0.04)"
        }

        ".${CssClass.History.TimeRangeBtnActive.value.replace(" ", ".")}" {
            color = colorTextPrimary
            background = "rgba(59, 130, 246, 0.15)"
            borderColor = Color("rgba(59, 130, 246, 0.25)")
        }

        ".${CssClass.History.ChartContainer}" {
            position = Position.relative
            height = 20.rem
            marginTop = 0.5.rem
        }

        ".${CssClass.History.StatsGrid}" {
            display = Display.grid
            put("grid-template-columns", "1fr")
            gap = 1.rem
            marginBottom = 1.25.rem
        }


        // ==========================================
        // Grouped Media Queries (To ensure correct cascading order)
        // ==========================================

        "@media (min-width: 640px)" {
            ".${CssClass.Form.AllocationListContainer}" {
                put("grid-template-columns", "repeat(2, 1fr)")
            }
            ".${CssClass.History.StatsGrid}" {
                put("grid-template-columns", "repeat(2, 1fr)")
            }
        }

        "@media (min-width: 768px)" {
        ".${CssClass.Layout.Container}" {
                padding = Padding(1.5.rem, 1.5.rem, 4.rem, 1.5.rem)
            }
            header {
                flexDirection = FlexDirection.row
                put("justify-content", "space-between")
                alignItems = Align.center
            }
        ".${CssClass.Layout.OverviewGrid}" {
                put("grid-template-columns", "repeat(3, 1fr)")
            }
        ".${CssClass.Form.Grid2Col}" {
                put("grid-template-columns", "1fr 1fr")
            }
        }

        "@media (min-width: 1024px)" {
        ".${CssClass.Layout.DetailGrid}" {
                put("grid-template-columns", "1fr 1fr")
            }
            ".${CssClass.Form.AllocationListContainer}" {
                put("grid-template-columns", "repeat(3, 1fr)")
            }
            ".${CssClass.History.StatsGrid}" {
                put("grid-template-columns", "repeat(4, 1fr)")
            }
        }
    }
}
