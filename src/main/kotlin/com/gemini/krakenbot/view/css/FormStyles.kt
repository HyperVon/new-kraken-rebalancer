package com.gemini.krakenbot.view.css

import com.gemini.krakenbot.view.util.CssClass
import kotlinx.css.*
import kotlinx.css.properties.*

object FormStyles {
    fun CssBuilder.applyFormStyles() {
        // Buttons
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
            background = "linear-gradient(135deg, ${CssTheme.colorBlueDeep.value}, ${CssTheme.colorBlueHover.value})"
            color = Color.white
            put("box-shadow", "0 4px 10px rgba(37, 99, 235, 0.2)")
        }

        ".btn-primary:hover" {
            background = "linear-gradient(135deg, ${CssTheme.colorBluePrimary.value}, ${CssTheme.colorBlueHover.value})"
            put("box-shadow", "0 4px 15px rgba(37, 99, 235, 0.4)")
        }

        ".btn-secondary" {
            background = "rgba(30, 41, 59, 0.5)"
            borderWidth = 1.px
            borderStyle = BorderStyle.solid
            borderColor = Color("rgba(71, 85, 105, 0.5)")
            color = CssTheme.colorMutedLight
        }

        ".btn-secondary:hover" {
            background = "rgba(30, 41, 59, 0.8)"
            borderColor = Color("rgba(148, 163, 184, 0.5)")
            color = Color.white
        }

        ".btn-danger" {
            background = Color("rgba(239, 68, 68, 0.1)").value
            borderWidth = 1.px
            borderStyle = BorderStyle.solid
            borderColor = Color("rgba(239, 68, 68, 0.3)")
            color = CssTheme.colorDanger
        }

        ".btn-danger:hover" {
            background = Color("rgba(239, 68, 68, 0.2)").value
            borderColor = CssTheme.colorDanger
        }

        ".btn:active" {
            transform { scale(0.97) }
        }

        ".btn:disabled" {
            opacity = 0.5
            cursor = Cursor.notAllowed
            put("transform", "none")
        }

        ".${CssClass.Button.Icon}" {
            padding = Padding(0.5.rem)
        }

        // Form Sections & Fields
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
            borderColor = CssTheme.colorBluePrimary
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
            color = CssTheme.colorTextPrimary
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
            borderColor = Color("rgba(71, 85, 105, 0.5)")
            background = "rgba(15, 23, 42, 0.2)"
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
    }
}
