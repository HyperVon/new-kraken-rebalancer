package com.gemini.krakenbot.view.component

import com.gemini.krakenbot.config.AppConfig
import com.gemini.krakenbot.view.util.CssClass
import com.gemini.krakenbot.view.util.FormFields
import com.gemini.krakenbot.view.util.HtmlAttrs
import com.gemini.krakenbot.view.util.HtmlIds
import com.gemini.krakenbot.view.util.HtmxAttrs
import com.gemini.krakenbot.view.util.HtmxValues
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
        body.div(CssClass.Layout.Container.value) {
            form {
                attributes[HtmxAttrs.HX_POST] = Routes.SETTINGS
                attributes[HtmxAttrs.HX_TARGET] = HtmxValues.BODY
                attributes[HtmxAttrs.HX_SWAP] = HtmxValues.INNER_HTML

                header {
                    div(CssClass.Layout.HeaderTitleSection.value) {
                        a(
                            href = Routes.ROOT,
                            classes = "${CssClass.Button.Secondary.value} ${CssClass.Button.Icon.value}"
                        ) {
                            icon(Icons.BACK_ARROW)
                        }
                        h1 { +ViewText.SETTINGS_TITLE }
                    }
                    button(
                        type = submit,
                        classes = CssClass.Button.Primary.value
                    ) {
                        id = HtmlIds.SAVE_BUTTON
                        icon(Icons.FLOPPY_DISK)
                        span { +ViewText.SAVE_CONFIGURATION }
                    }
                }

                if (errorMessage != null) {
                    div(CssClass.Utility.ErrorBanner.value) {
                        +errorMessage
                    }
                }

                div(CssClass.Layout.GlassPanel.value) {
                    renderGlobalParametersSection(config)
                    renderTargetAllocationsSection(config)
                }
            }
        }

        renderSettingsScript()
    }

    private fun DIV.renderGlobalParametersSection(config: AppConfig) {
        formSection(ViewText.GLOBAL_PARAMETERS, Icons.SHIELD_EXCLAMATION) {
            div(CssClass.Form.Grid2Col.value) {
                formGroup(ViewText.LOOP_INTERVAL) {
                    input(
                        type = number,
                        name = FormFields.LOOP_DELAY_SECONDS,
                        classes = CssClass.Form.InputGlass.value
                    ) {
                        min = "1"
                        value = config.settings.loopDelaySeconds.toString()
                    }
                }

                formGroup(ViewText.DEVIATION_TRIGGER) {
                    input(
                        type = number,
                        name = FormFields.DEVIATION_TRIGGER_PERCENT,
                        classes = CssClass.Form.InputGlass.value
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
                        classes = CssClass.Form.InputGlass.value
                    ) {
                        step = "0.5"
                        value = config.settings.dustThresholdUSD.toString()
                    }
                }

                formGroup(ViewText.FIAT_MAX_DRAWDOWN) {
                    input(
                        type = number,
                        name = FormFields.FIAT_MAX_DRAWDOWN,
                        classes = CssClass.Form.InputGlass.value
                    ) {
                        step = "1.0"
                        value = config.settings.fiatMaxDrawdown.toString()
                    }
                }

                formGroup(ViewText.FIAT_DEPLOYMENT_EXPONENT) {
                    input(
                        type = number,
                        name = FormFields.FIAT_DEPLOYMENT_EXPONENT,
                        classes = CssClass.Form.InputGlass.value
                    ) {
                        step = "0.1"
                        value =
                            config.settings.fiatDeploymentExponent.toString()
                    }
                }

                div("${CssClass.Form.Group.value} ${CssClass.Form.GroupCentered.value}") {
                    label(classes = CssClass.Form.CheckboxContainer.value) {
                        input(
                            type = checkBox,
                            name = FormFields.DRY_RUN
                        ) {
                            checked = config.settings.dryRun
                        }
                        div(CssClass.Form.CheckboxCustom.value) {}
                        span { +ViewText.DRY_RUN_MODE }
                    }
                }

                div("${CssClass.Form.Group.value} ${CssClass.Form.GroupCentered.value}") {
                    label(classes = CssClass.Form.CheckboxContainer.value) {
                        input(
                            type = checkBox,
                            name = FormFields.SIMULATION
                        ) {
                            checked = config.settings.simulation
                        }
                        div(CssClass.Form.CheckboxCustom.value) {}
                        span { +ViewText.SIMULATION_MODE }
                    }
                }
            }
        }
    }

    private fun DIV.renderTargetAllocationsSection(config: AppConfig) {
        div(CssClass.Form.Section.value) {
            div(CssClass.Form.SectionHeader.value) {
                h3 {
                    +ViewText.TARGET_ALLOCATIONS
                }
                div(CssClass.StatusCard.Live.value) {
                    id = HtmlIds.TOTAL_ALLOCATED_DISPLAY
                    +ViewText.TOTAL_INITIAL
                }
            }

            div(CssClass.Form.AllocationListContainer.value) {
                id = HtmlIds.ALLOCATIONS_CONTAINER
                config.allocations.forEach { alloc ->
                    div(CssClass.Form.AllocationEditRow.value) {
                        div(CssClass.Form.AllocationEditSymbol.value) { +alloc.symbol.value }
                        input(
                            type = InputType.hidden,
                            name = FormFields.SYMBOLS
                        ) { value = alloc.symbol.value }
                        div(CssClass.Form.AllocationEditInputWrapper.value) {
                            input(
                                type = number,
                                name = FormFields.TARGETS,
                                classes = CssClass.Form.InputGlass.value
                            ) {
                                step = "0.1"
                                value = alloc.targetPercent.toString()
                                attributes[HtmlAttrs.ONINPUT] =
                                    "updateAllocationTotal()"
                            }
                            span(CssClass.Form.PercentSuffix.value) { +"%" }
                        }
                        button(
                            type = button,
                            classes = CssClass.Button.Danger.value
                        ) {
                            attributes[HtmlAttrs.ONCLICK] =
                                "this.closest('.allocation-edit-row').remove(); updateAllocationTotal();"
                            +ViewText.REMOVE
                        }
                    }
                }
            }

            div(CssClass.Form.AddAssetBox.value) {
                input(type = text, classes = CssClass.Form.InputGlass.value) {
                    id = HtmlIds.NEW_SYMBOL_INPUT
                    placeholder = ViewText.NEW_SYMBOL_PLACEHOLDER
                    attributes[HtmlAttrs.ONKEYDOWN] =
                        "if(event.key === 'Enter') { event.preventDefault(); addAssetRow(); }"
                }
                button(
                    type = button,
                    classes = CssClass.Button.Secondary.value
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
        body.script(src = Routes.STATIC_REBALANCER_JS) {}
    }
}
