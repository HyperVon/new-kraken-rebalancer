package com.gemini.krakenbot.view.css

import com.gemini.krakenbot.view.util.CssClass
import kotlinx.css.*
import kotlinx.css.properties.*

object FormStyles {
    fun CssBuilder.applyFormStyles() {
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
            background =
                "linear-gradient(135deg, rgba(96,165,250,0.95) 0%, ${CssTheme.colorBlueDeep.value} 45%, " +
                "${CssTheme.colorBlueHover.value} 100%)"
            color = Color.white
            put(
                "box-shadow",
                "0 0 18px rgba(59,130,246,0.32), 0 6px 14px rgba(37,99,235,0.28), " +
                    "inset 0 1px 0 rgba(255,255,255,0.18)",
            )
        }

        ".btn-primary:hover" {
            background =
                "linear-gradient(135deg, ${CssTheme.colorBlueAccent.value} 0%, " +
                "${CssTheme.colorBluePrimary.value} 50%, ${CssTheme.colorBlueHover.value} 100%)"
            put(
                "box-shadow",
                "0 0 22px rgba(59,130,246,0.4), 0 8px 18px rgba(37,99,235,0.35), " +
                    "inset 0 1px 0 rgba(255,255,255,0.22)",
            )
        }

        ".btn-secondary" {
            background = "rgba(30, 41, 59, 0.5)"
            borderWidth = 1.px
            borderStyle = BorderStyle.solid
            borderColor = CssTheme.colorBorderInput
            color = CssTheme.colorMutedLight
        }

        ".btn-secondary:hover" {
            background = "rgba(30, 41, 59, 0.8)"
            borderColor = CssTheme.colorBorderInputHover
            color = Color.white
        }

        ".btn-danger" {
            background = CssTheme.colorDangerBgSubtle.value
            borderWidth = 1.px
            borderStyle = BorderStyle.solid
            borderColor = CssTheme.colorDangerBorder
            color = CssTheme.colorDanger
        }

        ".btn-danger:hover" {
            background = CssTheme.colorDangerBgHover.value
            borderColor = CssTheme.colorDanger
        }

        // HIST-1: quiet ghost destructive action — reads as muted until hover.
        ".btn-danger-ghost" {
            background = "transparent"
            borderWidth = 1.px
            borderStyle = BorderStyle.solid
            borderColor = Color.transparent
            color = CssTheme.colorTextMuted
        }

        ".btn-danger-ghost:hover" {
            background = CssTheme.colorDangerBgSubtle.value
            borderColor = CssTheme.colorDangerBorderSubtle
            color = CssTheme.colorDanger
        }

        ".btn:active" {
            transform { scale(0.97) }
        }

        ".btn:focus-visible" {
            put("outline", "none")
            put("box-shadow", "0 0 0 3px rgba(59, 130, 246, 0.45)")
        }

        ".btn:disabled" {
            opacity = 0.5
            cursor = Cursor.notAllowed
            put("transform", "none")
        }

        ".${CssClass.Button.Icon}" {
            padding = Padding(0.5.rem)
        }

        ".${CssClass.Form.Section}" {
            borderBottomWidth = 1.px
            borderBottomStyle = BorderStyle.solid
            borderBottomColor = CssTheme.colorBorderSubtle
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
            gridTemplateColumns = GridTemplateColumns("1fr")
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
            color = CssTheme.colorTextSecondary
        }

        ".${CssClass.Form.InputGlass}" {
            background = "rgba(15, 23, 42, 0.4)"
            borderWidth = 1.px
            borderStyle = BorderStyle.solid
            borderColor = CssTheme.colorBorderInput
            color = Color.white
            padding = Padding(0.5.rem, 0.75.rem)
            borderRadius = 0.5.rem
            fontFamily = "inherit"
            fontSize = 0.875.rem
            put("transition", "all 0.2s ease")
        }

        ".${CssClass.Form.InputGlass}:focus" {
            put("outline", "none")
            borderColor = CssTheme.colorBluePrimary
            put("box-shadow", "0 0 0 3px rgba(59, 130, 246, 0.2)")
        }

        ".${CssClass.Form.CheckboxContainer}" {
            display = Display.flex
            alignItems = Align.center
            gap = 0.75.rem
            cursor = Cursor.pointer
            put("user-select", "none")
            position = Position.relative
        }

        ".${CssClass.Form.CheckboxCustom}" {
            position = Position.relative
            width = 1.25.rem
            height = 1.25.rem
            borderWidth = 2.px
            borderStyle = BorderStyle.solid
            borderColor = CssTheme.colorBorderInputStrong
            borderRadius = 0.25.rem
            put("transition", "all 0.2s ease")
        }

        "input[type=\"checkbox\"]" {
            // Visually hidden but still focusable (display:none removes from tab order).
            position = Position.absolute
            opacity = 0.0
            width = 1.px
            height = 1.px
            margin = Margin((-1).px)
            padding = Padding(0.px)
            borderWidth = 0.px
            overflow = Overflow.hidden
            put("clip", "rect(0, 0, 0, 0)")
            put("clip-path", "inset(50%)")
            put("white-space", "nowrap")
        }

        "input[type=\"checkbox\"]:checked + .${CssClass.Form.CheckboxCustom}" {
            backgroundColor = CssTheme.colorKrakenBlue
            borderColor = CssTheme.colorKrakenBlue
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
            gridTemplateColumns = GridTemplateColumns("1fr")
            gap = 0.75.rem
            marginBottom = 1.25.rem
        }

        ".${CssClass.Form.AllocationEditRow}" {
            display = Display.flex
            alignItems = Align.center
            gap = 0.75.rem
            background = CssTheme.colorSurface2.value
            padding = Padding(0.5.rem, 0.75.rem)
            borderRadius = 0.75.rem
            borderWidth = 1.px
            borderStyle = BorderStyle.solid
            borderColor = CssTheme.colorSurface2Border
            put(
                "box-shadow",
                "0 4px 12px -4px rgba(0,0,0,0.5), inset 0 1px 0 rgba(186,220,255,0.08)",
            )
        }

        ".${CssClass.Form.AllocationEditSymbol}" {
            width = 3.5.rem
            fontWeight = FontWeight.w700
            fontSize = 0.9375.rem
            color = CssTheme.colorTextPrimary
        }

        ".${CssClass.Form.AllocationColorSwatch}" {
            width = 2.rem
            height = 2.rem
            padding = Padding(0.px)
            borderWidth = 0.px
            borderStyle = BorderStyle.none
            borderRadius = 0.5.rem
            cursor = Cursor.pointer
            backgroundColor = Color.transparent
            display = Display.inlineFlex
            put("flex-shrink", "0")
        }

        ".${CssClass.Form.AllocationColorSwatch}::-webkit-color-swatch-wrapper" {
            padding = Padding(0.px)
        }

        ".${CssClass.Form.AllocationColorSwatch}::-webkit-color-swatch" {
            borderWidth = 0.px
            borderStyle = BorderStyle.none
            borderRadius = 0.5.rem
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
            color = CssTheme.colorTextMuted
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
            borderColor = CssTheme.colorBorderInput
            background = "rgba(15, 23, 42, 0.2)"
        }

        ".${CssClass.Form.GroupCentered}" {
            put("justify-content", "center")
            paddingTop = 1.rem
        }

        ".${CssClass.Form.SectionSubtitle.value}" {
            fontSize = 0.875.rem
            color = CssTheme.colorTextSecondary
            marginTop = (-0.75).rem
            marginBottom = 1.25.rem
            put("line-height", "1.45")
            maxWidth = 44.rem
        }

        ".${CssClass.Form.SafetyGroup}" {
            display = Display.grid
            gridTemplateColumns = GridTemplateColumns("1fr")
            gap = 0.px
        }

        // SETT-1: two large safety toggle cards (icon, title, consequence prose).
        ".${CssClass.Form.SafetyToggles}" {
            display = Display.grid
            gridTemplateColumns = GridTemplateColumns("1fr")
            gap = 1.rem
        }

        // SETT-1: the label is the click target; the inner wrapper is the visible card
        // so the sibling `input:checked` selector can tint it (pure CSS, no JS).
        ".${CssClass.Form.SafetyCard.value}" {
            display = Display.block
            position = Position.relative
            cursor = Cursor.pointer
            put("user-select", "none")
        }

        ".${CssClass.Form.SafetyCardInner.value}" {
            display = Display.flex
            alignItems = Align.flexStart
            gap = 1.rem
            padding = Padding(1.25.rem)
            borderRadius = 0.875.rem
            background = CssTheme.glassSurfaceGradient
            borderWidth = 1.px
            borderStyle = BorderStyle.solid
            borderColor = CssTheme.colorSurface2Border
            put("transition", "all 0.2s ease")
            put("box-shadow", CssTheme.shadowSurface1)
        }

        ".${CssClass.Form.SafetyCard.value}:focus-within .${CssClass.Form.SafetyCardInner.value}" {
            borderColor = CssTheme.colorKrakenBlue
            put("box-shadow", "0 0 0 2px rgba(59, 130, 246, 0.45), ${CssTheme.shadowSurface1}")
        }

        ".${CssClass.Form.SafetyCard.value}:hover .${CssClass.Form.SafetyCardInner.value}" {
            borderColor = CssTheme.colorGlassBorderHover
            put("box-shadow", CssTheme.shadowSurface2)
        }

        // Active (checked) tint — a safety that is ON reads as success.
        "input[type=\"checkbox\"]:checked + .${CssClass.Form.SafetyCardInner.value}" {
            background =
                "linear-gradient(165deg, rgba(16,185,129,0.16) 0%, rgba(16,185,129,0.05) 28%, " +
                "rgba(18,28,48,0.92) 100%)"
            borderColor = CssTheme.colorSuccessBorder
            put(
                "box-shadow",
                "0 8px 22px rgba(0,0,0,0.5), 0 0 22px rgba(16,185,129,0.12), " +
                    "inset 0 1px 0 rgba(147,197,253,0.14)",
            )
        }

        ".${CssClass.Form.SafetyCardIcon.value}" {
            display = Display.flex
            alignItems = Align.center
            put("justify-content", "center")
            flexShrink = 0.0
            width = 2.75.rem
            height = 2.75.rem
            borderRadius = 50.pct
            background = CssTheme.colorGlassHover.value
            color = CssTheme.colorTextSecondary
            put("transition", "all 0.2s ease")
        }

        val checkedIcon =
            "input[type=\"checkbox\"]:checked + .${CssClass.Form.SafetyCardInner.value} " +
                ".${CssClass.Form.SafetyCardIcon.value}"
        checkedIcon {
            background = CssTheme.colorSuccessMuted.value
            color = CssTheme.colorSuccess
            put("box-shadow", CssTheme.glowGreenSoft)
        }

        ".${CssClass.Form.SafetyCardIcon.value} svg" {
            width = 1.375.rem
            height = 1.375.rem
        }

        ".${CssClass.Form.SafetyCardBody.value}" {
            display = Display.flex
            flexDirection = FlexDirection.column
            gap = 0.25.rem
            flexGrow = 1.0
        }

        ".${CssClass.Form.SafetyCardTitleRow.value}" {
            display = Display.flex
            alignItems = Align.center
            put("justify-content", "space-between")
            gap = 0.75.rem
        }

        ".${CssClass.Form.SafetyCardTitle.value}" {
            fontSize = 1.rem
            fontWeight = FontWeight.w700
            color = CssTheme.colorTextPrimary
        }

        ".${CssClass.Form.SafetyCardDesc.value}" {
            fontSize = 0.8125.rem
            color = CssTheme.colorTextSecondary
            put("line-height", "1.45")
        }

        ".${CssClass.Form.SafetyStatePill.value}" {
            flexShrink = 0.0
            padding = Padding(0.125.rem, 0.625.rem)
            borderRadius = CssTheme.radiusPill
            fontSize = 0.6875.rem
            fontWeight = FontWeight.w700
            put("letter-spacing", "0.06em")
            backgroundColor = CssTheme.colorSlateMuted
            color = CssTheme.colorTextSecondary
            borderWidth = 1.px
            borderStyle = BorderStyle.solid
            borderColor = CssTheme.colorSlateBorder
        }

        // ON/OFF live in the DOM; CSS toggles which span is visible with the checkbox.
        ".${CssClass.Form.SafetyStateOn.value}" {
            display = Display.none
        }
        ".${CssClass.Form.SafetyStateOff.value}" {
            display = Display.inline
        }

        val checkedPill =
            "input[type=\"checkbox\"]:checked + .${CssClass.Form.SafetyCardInner.value} " +
                ".${CssClass.Form.SafetyStatePill.value}"
        checkedPill {
            backgroundColor = CssTheme.colorSuccessMuted
            color = CssTheme.colorSuccess
            borderColor = CssTheme.colorSuccessBorder
        }
        "$checkedPill .${CssClass.Form.SafetyStateOn.value}" {
            display = Display.inline
        }
        "$checkedPill .${CssClass.Form.SafetyStateOff.value}" {
            display = Display.none
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
    }
}
