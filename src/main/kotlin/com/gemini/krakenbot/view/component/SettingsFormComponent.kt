package com.gemini.krakenbot.view.component

import com.gemini.krakenbot.config.AppConfig
import com.gemini.krakenbot.view.util.CssClasses.ADD_ASSET_BOX
import com.gemini.krakenbot.view.util.CssClasses.ALLOCATION_EDIT_INPUT_WRAPPER
import com.gemini.krakenbot.view.util.CssClasses.ALLOCATION_EDIT_ROW
import com.gemini.krakenbot.view.util.CssClasses.ALLOCATION_EDIT_SYMBOL
import com.gemini.krakenbot.view.util.CssClasses.ALLOCATION_LIST_CONTAINER
import com.gemini.krakenbot.view.util.CssClasses.BTN_DANGER
import com.gemini.krakenbot.view.util.CssClasses.BTN_ICON
import com.gemini.krakenbot.view.util.CssClasses.BTN_PRIMARY
import com.gemini.krakenbot.view.util.CssClasses.BTN_SECONDARY
import com.gemini.krakenbot.view.util.CssClasses.CHECKBOX_CONTAINER
import com.gemini.krakenbot.view.util.CssClasses.CHECKBOX_CUSTOM
import com.gemini.krakenbot.view.util.CssClasses.CONTAINER
import com.gemini.krakenbot.view.util.CssClasses.ERROR_BANNER
import com.gemini.krakenbot.view.util.CssClasses.FORM_GROUP
import com.gemini.krakenbot.view.util.CssClasses.FORM_GROUP_CENTERED
import com.gemini.krakenbot.view.util.CssClasses.FORM_SECTION
import com.gemini.krakenbot.view.util.CssClasses.GLASS_PANEL
import com.gemini.krakenbot.view.util.CssClasses.GRID_2COL
import com.gemini.krakenbot.view.util.CssClasses.HEADER_TITLE_SECTION
import com.gemini.krakenbot.view.util.CssClasses.INPUT_GLASS
import com.gemini.krakenbot.view.util.CssClasses.PERCENT_SUFFIX
import com.gemini.krakenbot.view.util.CssClasses.SECTION_HEADER
import com.gemini.krakenbot.view.util.CssClasses.STATUS_BADGE_LIVE
import com.gemini.krakenbot.view.util.FormFields
import com.gemini.krakenbot.view.util.FormFields.DEVIATION_TRIGGER_PERCENT
import com.gemini.krakenbot.view.util.FormFields.DRY_RUN
import com.gemini.krakenbot.view.util.FormFields.DUST_THRESHOLD_USD
import com.gemini.krakenbot.view.util.FormFields.LOOP_DELAY_SECONDS
import com.gemini.krakenbot.view.util.FormFields.SYMBOLS
import com.gemini.krakenbot.view.util.FormFields.TARGETS
import com.gemini.krakenbot.view.util.HtmlAttrs.ONCLICK
import com.gemini.krakenbot.view.util.HtmlAttrs.ONINPUT
import com.gemini.krakenbot.view.util.HtmlAttrs.ONKEYDOWN
import com.gemini.krakenbot.view.util.HtmlIds.ALLOCATIONS_CONTAINER
import com.gemini.krakenbot.view.util.HtmlIds.NEW_SYMBOL_INPUT
import com.gemini.krakenbot.view.util.HtmlIds.SAVE_BUTTON
import com.gemini.krakenbot.view.util.HtmlIds.TOTAL_ALLOCATED_DISPLAY
import com.gemini.krakenbot.view.util.HtmxAttrs.HX_POST
import com.gemini.krakenbot.view.util.HtmxAttrs.HX_SWAP
import com.gemini.krakenbot.view.util.HtmxAttrs.HX_TARGET
import com.gemini.krakenbot.view.util.Icons.BACK_ARROW
import com.gemini.krakenbot.view.util.Icons.FLOPPY_DISK
import com.gemini.krakenbot.view.util.Icons.PLUS
import com.gemini.krakenbot.view.util.Icons.SHIELD_EXCLAMATION
import com.gemini.krakenbot.view.util.Icons.icon
import com.gemini.krakenbot.view.util.Layouts.formGroup
import com.gemini.krakenbot.view.util.Layouts.formSection
import com.gemini.krakenbot.view.util.Routes.ROOT
import com.gemini.krakenbot.view.util.Routes.SETTINGS
import com.gemini.krakenbot.view.util.Routes.STATIC_SETTINGS_JS
import com.gemini.krakenbot.view.util.ViewText.ADD_ASSET
import com.gemini.krakenbot.view.util.ViewText.DEVIATION_TRIGGER
import com.gemini.krakenbot.view.util.ViewText.DRY_RUN_MODE
import com.gemini.krakenbot.view.util.ViewText.DUST_THRESHOLD
import com.gemini.krakenbot.view.util.ViewText.FIAT_DEPLOYMENT_EXPONENT
import com.gemini.krakenbot.view.util.ViewText.FIAT_MAX_DRAWDOWN
import com.gemini.krakenbot.view.util.ViewText.GLOBAL_PARAMETERS
import com.gemini.krakenbot.view.util.ViewText.LOOP_INTERVAL
import com.gemini.krakenbot.view.util.ViewText.NEW_SYMBOL_PLACEHOLDER
import com.gemini.krakenbot.view.util.ViewText.REMOVE
import com.gemini.krakenbot.view.util.ViewText.SAVE_CONFIGURATION
import com.gemini.krakenbot.view.util.ViewText.SETTINGS_TITLE
import com.gemini.krakenbot.view.util.ViewText.TARGET_ALLOCATIONS
import com.gemini.krakenbot.view.util.ViewText.TOTAL_INITIAL
import kotlinx.html.*
import kotlinx.html.ButtonType.button
import kotlinx.html.ButtonType.submit
import kotlinx.html.InputType.*

class SettingsFormComponent {
    fun BODY.render(config: AppConfig, errorMessage: String?) {
        div(CONTAINER) {
            form {
                attributes[HX_POST] = SETTINGS
                attributes[HX_TARGET] = "body"
                attributes[HX_SWAP] = "innerHTML"

                header {
                    div(HEADER_TITLE_SECTION) {
                        a(
                            href = ROOT,
                            classes = "$BTN_SECONDARY $BTN_ICON"
                        ) {
                            icon(BACK_ARROW)
                        }
                        h1 { +SETTINGS_TITLE }
                    }
                    button(
                        type = submit,
                        classes = BTN_PRIMARY
                    ) {
                        id = SAVE_BUTTON
                        icon(FLOPPY_DISK)
                        span { +SAVE_CONFIGURATION }
                    }
                }

                if (errorMessage != null) {
                    div(ERROR_BANNER) {
                        +errorMessage
                    }
                }

                div(GLASS_PANEL) {
                    renderGlobalParametersSection(config)
                    renderTargetAllocationsSection(config)
                }
            }
        }

        renderSettingsScript()
    }

    private fun DIV.renderGlobalParametersSection(config: AppConfig) {
        formSection(GLOBAL_PARAMETERS, SHIELD_EXCLAMATION) {
            div(GRID_2COL) {
                formGroup(LOOP_INTERVAL) {
                    input(
                        type = number,
                        name = LOOP_DELAY_SECONDS,
                        classes = INPUT_GLASS
                    ) {
                        min = "1"
                        value = config.settings.loopDelaySeconds.toString()
                    }
                }

                formGroup(DEVIATION_TRIGGER) {
                    input(
                        type = number,
                        name = DEVIATION_TRIGGER_PERCENT,
                        classes = INPUT_GLASS
                    ) {
                        step = "0.1"
                        min = "0"
                        value =
                            config.settings.deviationTriggerPercent.toString()
                    }
                }

                formGroup(DUST_THRESHOLD) {
                    input(
                        type = number,
                        name = DUST_THRESHOLD_USD,
                        classes = INPUT_GLASS
                    ) {
                        step = "0.5"
                        value = config.settings.dustThresholdUSD.toString()
                    }
                }

                formGroup(FIAT_MAX_DRAWDOWN) {
                    input(
                        type = number,
                        name = FormFields.FIAT_MAX_DRAWDOWN,
                        classes = INPUT_GLASS
                    ) {
                        step = "1.0"
                        value = config.settings.fiatMaxDrawdown.toString()
                    }
                }

                formGroup(FIAT_DEPLOYMENT_EXPONENT) {
                    input(
                        type = number,
                        name = FormFields.FIAT_DEPLOYMENT_EXPONENT,
                        classes = INPUT_GLASS
                    ) {
                        step = "0.1"
                        value =
                            config.settings.fiatDeploymentExponent.toString()
                    }
                }

                div("$FORM_GROUP $FORM_GROUP_CENTERED") {
                    label(classes = CHECKBOX_CONTAINER) {
                        input(
                            type = checkBox,
                            name = DRY_RUN
                        ) {
                            checked = config.settings.dryRun
                        }
                        div(CHECKBOX_CUSTOM) {}
                        span { +DRY_RUN_MODE }
                    }
                }
            }
        }
    }

    private fun DIV.renderTargetAllocationsSection(config: AppConfig) {
        div(FORM_SECTION) {
            div(SECTION_HEADER) {
                h3 {
                    +TARGET_ALLOCATIONS
                }
                div(STATUS_BADGE_LIVE) {
                    id = TOTAL_ALLOCATED_DISPLAY
                    +TOTAL_INITIAL
                }
            }

            div(ALLOCATION_LIST_CONTAINER) {
                id = ALLOCATIONS_CONTAINER
                config.allocations.forEach { alloc ->
                    div(ALLOCATION_EDIT_ROW) {
                        div(ALLOCATION_EDIT_SYMBOL) { +alloc.symbol.value }
                        input(
                            type = InputType.hidden,
                            name = SYMBOLS
                        ) { value = alloc.symbol.value }
                        div(ALLOCATION_EDIT_INPUT_WRAPPER) {
                            input(
                                type = number,
                                name = TARGETS,
                                classes = INPUT_GLASS
                            ) {
                                step = "0.1"
                                value = alloc.targetPercent.toString()
                                attributes[ONINPUT] =
                                    "updateAllocationTotal()"
                            }
                            span(PERCENT_SUFFIX) { +"%" }
                        }
                        button(
                            type = button,
                            classes = BTN_DANGER
                        ) {
                            attributes[ONCLICK] =
                                "this.closest('.allocation-edit-row').remove(); updateAllocationTotal();"
                            +REMOVE
                        }
                    }
                }
            }

            div(ADD_ASSET_BOX) {
                input(type = text, classes = INPUT_GLASS) {
                    id = NEW_SYMBOL_INPUT
                    placeholder = NEW_SYMBOL_PLACEHOLDER
                    attributes[ONKEYDOWN] =
                        "if(event.key === 'Enter') { event.preventDefault(); addAssetRow(); }"
                }
                button(
                    type = button,
                    classes = BTN_SECONDARY
                ) {
                    attributes[ONCLICK] = "addAssetRow()"
                    icon(PLUS)
                    span { +ADD_ASSET }
                }
            }
        }
    }

    private fun BODY.renderSettingsScript() {
        script(src = STATIC_SETTINGS_JS) {}
    }
}
