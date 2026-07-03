package com.gemini.krakenbot.view.component

import com.gemini.krakenbot.config.AppConfig
import com.gemini.krakenbot.view.util.CssClasses
import com.gemini.krakenbot.view.util.FormFields
import com.gemini.krakenbot.view.util.HtmlAttrs
import com.gemini.krakenbot.view.util.HtmlIds
import com.gemini.krakenbot.view.util.HtmxAttrs
import com.gemini.krakenbot.view.util.Icons
import com.gemini.krakenbot.view.util.Icons.icon
import com.gemini.krakenbot.view.util.Layouts.formGroup
import com.gemini.krakenbot.view.util.Layouts.formSection
import com.gemini.krakenbot.view.util.Routes
import com.gemini.krakenbot.view.util.ViewText
import kotlinx.html.*
import kotlinx.html.ButtonType.button
import kotlinx.html.ButtonType.submit
import kotlinx.html.InputType.*

class SettingsFormComponent {
    context(body: BODY)
    fun render(config: AppConfig, errorMessage: String?) {
        body.div(CssClasses.CONTAINER) {
            form {
                attributes[HtmxAttrs.HX_POST] = Routes.SETTINGS
                attributes[HtmxAttrs.HX_TARGET] = "body"
                attributes[HtmxAttrs.HX_SWAP] = "innerHTML"

                header {
                    div(CssClasses.HEADER_TITLE_SECTION) {
                        a(
                            href = Routes.ROOT,
                            classes = "${CssClasses.BTN_SECONDARY} ${CssClasses.BTN_ICON}"
                        ) {
                            icon(Icons.BACK_ARROW)
                        }
                        h1 { +ViewText.SETTINGS_TITLE }
                    }
                    button(
                        type = submit,
                        classes = CssClasses.BTN_PRIMARY
                    ) {
                        id = HtmlIds.SAVE_BUTTON
                        icon(Icons.FLOPPY_DISK)
                        span { +ViewText.SAVE_CONFIGURATION }
                    }
                }

                if (errorMessage != null) {
                    div(CssClasses.ERROR_BANNER) {
                        +errorMessage
                    }
                }

                div(CssClasses.GLASS_PANEL) {
                    renderGlobalParametersSection(config)
                    renderTargetAllocationsSection(config)
                }
            }
        }

        renderSettingsScript()
    }

    private fun DIV.renderGlobalParametersSection(config: AppConfig) {
        formSection(ViewText.GLOBAL_PARAMETERS, Icons.SHIELD_EXCLAMATION) {
            div(CssClasses.GRID_2COL) {
                formGroup(ViewText.LOOP_INTERVAL) {
                    input(
                        type = number,
                        name = FormFields.LOOP_DELAY_SECONDS,
                        classes = CssClasses.INPUT_GLASS
                    ) {
                        min = "1"
                        value = config.settings.loopDelaySeconds.toString()
                    }
                }

                formGroup(ViewText.DEVIATION_TRIGGER) {
                    input(
                        type = number,
                        name = FormFields.DEVIATION_TRIGGER_PERCENT,
                        classes = CssClasses.INPUT_GLASS
                    ) {
                        step = "0.1"
                        min = "0"
                        value =
                            config.settings.deviationTriggerPercent.toString()
                    }
                }

                formGroup(ViewText.DUST_THRESHOLD) {
                    input(
                        type = number,
                        name = FormFields.DUST_THRESHOLD_USD,
                        classes = CssClasses.INPUT_GLASS
                    ) {
                        step = "0.5"
                        value = config.settings.dustThresholdUSD.toString()
                    }
                }

                formGroup(ViewText.FIAT_MAX_DRAWDOWN) {
                    input(
                        type = number,
                        name = FormFields.FIAT_MAX_DRAWDOWN,
                        classes = CssClasses.INPUT_GLASS
                    ) {
                        step = "1.0"
                        value = config.settings.fiatMaxDrawdown.toString()
                    }
                }

                formGroup(ViewText.FIAT_DEPLOYMENT_EXPONENT) {
                    input(
                        type = number,
                        name = FormFields.FIAT_DEPLOYMENT_EXPONENT,
                        classes = CssClasses.INPUT_GLASS
                    ) {
                        step = "0.1"
                        value =
                            config.settings.fiatDeploymentExponent.toString()
                    }
                }

                div("${CssClasses.FORM_GROUP} ${CssClasses.FORM_GROUP_CENTERED}") {
                    label(classes = CssClasses.CHECKBOX_CONTAINER) {
                        input(
                            type = checkBox,
                            name = FormFields.DRY_RUN
                        ) {
                            checked = config.settings.dryRun
                        }
                        div(CssClasses.CHECKBOX_CUSTOM) {}
                        span { +ViewText.DRY_RUN_MODE }
                    }
                }

                div("${CssClasses.FORM_GROUP} ${CssClasses.FORM_GROUP_CENTERED}") {
                    label(classes = CssClasses.CHECKBOX_CONTAINER) {
                        input(
                            type = checkBox,
                            name = FormFields.SIMULATION
                        ) {
                            checked = config.settings.simulation
                        }
                        div(CssClasses.CHECKBOX_CUSTOM) {}
                        span { +ViewText.SIMULATION_MODE }
                    }
                }
            }
        }
    }

    private fun DIV.renderTargetAllocationsSection(config: AppConfig) {
        div(CssClasses.FORM_SECTION) {
            div(CssClasses.SECTION_HEADER) {
                h3 {
                    +ViewText.TARGET_ALLOCATIONS
                }
                div(CssClasses.STATUS_BADGE_LIVE) {
                    id = HtmlIds.TOTAL_ALLOCATED_DISPLAY
                    +ViewText.TOTAL_INITIAL
                }
            }

            div(CssClasses.ALLOCATION_LIST_CONTAINER) {
                id = HtmlIds.ALLOCATIONS_CONTAINER
                config.allocations.forEach { alloc ->
                    div(CssClasses.ALLOCATION_EDIT_ROW) {
                        div(CssClasses.ALLOCATION_EDIT_SYMBOL) { +alloc.symbol.value }
                        input(
                            type = InputType.hidden,
                            name = FormFields.SYMBOLS
                        ) { value = alloc.symbol.value }
                        div(CssClasses.ALLOCATION_EDIT_INPUT_WRAPPER) {
                            input(
                                type = number,
                                name = FormFields.TARGETS,
                                classes = CssClasses.INPUT_GLASS
                            ) {
                                step = "0.1"
                                value = alloc.targetPercent.toString()
                                attributes[HtmlAttrs.ONINPUT] =
                                    "updateAllocationTotal()"
                            }
                            span(CssClasses.PERCENT_SUFFIX) { +"%" }
                        }
                        button(
                            type = button,
                            classes = CssClasses.BTN_DANGER
                        ) {
                            attributes[HtmlAttrs.ONCLICK] =
                                "this.closest('.allocation-edit-row').remove(); updateAllocationTotal();"
                            +ViewText.REMOVE
                        }
                    }
                }
            }

            div(CssClasses.ADD_ASSET_BOX) {
                input(type = text, classes = CssClasses.INPUT_GLASS) {
                    id = HtmlIds.NEW_SYMBOL_INPUT
                    placeholder = ViewText.NEW_SYMBOL_PLACEHOLDER
                    attributes[HtmlAttrs.ONKEYDOWN] =
                        "if(event.key === 'Enter') { event.preventDefault(); addAssetRow(); }"
                }
                button(
                    type = button,
                    classes = CssClasses.BTN_SECONDARY
                ) {
                    attributes[HtmlAttrs.ONCLICK] = "addAssetRow()"
                    icon(Icons.PLUS)
                    span { +ViewText.ADD_ASSET }
                }
            }
        }
    }

    context(body: BODY)
    private fun renderSettingsScript() {
        body.script(src = Routes.STATIC_SETTINGS_JS) {}
    }
}
