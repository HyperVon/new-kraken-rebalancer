package com.gemini.krakenbot.view.component

import com.gemini.krakenbot.config.AppConfig
import com.gemini.krakenbot.view.util.ActiveNav
import com.gemini.krakenbot.view.util.CssClass
import com.gemini.krakenbot.view.util.FormFields
import com.gemini.krakenbot.view.util.HtmlAttrs
import com.gemini.krakenbot.view.util.HtmlIds
import com.gemini.krakenbot.view.util.HtmxAttrs
import com.gemini.krakenbot.view.util.HtmxValues
import com.gemini.krakenbot.view.util.Icons
import com.gemini.krakenbot.view.util.Icons.icon
import com.gemini.krakenbot.view.util.Routes
import com.gemini.krakenbot.view.util.ViewText
import com.gemini.krakenbot.view.util.brandWithMode
import com.gemini.krakenbot.view.util.button
import com.gemini.krakenbot.view.util.div
import com.gemini.krakenbot.view.util.formGroup
import com.gemini.krakenbot.view.util.formSection
import com.gemini.krakenbot.view.util.h3
import com.gemini.krakenbot.view.util.input
import com.gemini.krakenbot.view.util.label
import com.gemini.krakenbot.view.util.p
import com.gemini.krakenbot.view.util.primaryNav
import com.gemini.krakenbot.view.util.rebalancerJsSrc
import com.gemini.krakenbot.view.util.span
import kotlinx.html.*
import kotlinx.html.ButtonType.button
import kotlinx.html.ButtonType.submit
import kotlinx.html.InputType.*

class SettingsFormComponent {
    context(body: BODY)
    fun render(config: AppConfig, errorMessage: String?) {
        renderForm(body, config, errorMessage)
        renderSettingsScript()
    }

    fun renderForm(parent: FlowContent, config: AppConfig, errorMessage: String?) {
        parent.div(CssClass.Layout.Container) {
            form {
                attributes[HtmxAttrs.HX_POST] = Routes.SETTINGS
                attributes[HtmxAttrs.HX_TARGET] = HtmxValues.BODY
                attributes[HtmxAttrs.HX_SWAP] = HtmxValues.INNER_HTML

                header {
                    brandWithMode(config.settings)
                    div(CssClass.Layout.HeaderActions) {
                        primaryNav(ActiveNav.SETTINGS)
                        button(
                            CssClass.Button.Primary,
                            type = submit,
                        ) {
                            id = HtmlIds.SAVE_BUTTON
                            icon(Icons.FLOPPY_DISK)
                            span { +ViewText.SAVE_CONFIGURATION }
                        }
                    }
                }

                if (errorMessage != null) {
                    div(CssClass.Utility.ErrorBanner) {
                        +errorMessage
                    }
                }

                div(CssClass.Layout.GlassPanel) {
                    renderGlobalParametersSection(config)
                    renderSafetyModesSection(config)
                    renderTargetAllocationsSection(config)
                }
            }
        }
    }

    private fun DIV.renderGlobalParametersSection(config: AppConfig) {
        formSection(ViewText.GLOBAL_PARAMETERS, Icons.SHIELD_EXCLAMATION) {
            div(CssClass.Form.Grid2Col) {
                formGroup(ViewText.LOOP_INTERVAL) {
                    input(
                        CssClass.Form.InputGlass,
                        type = number,
                        name = FormFields.LOOP_DELAY_SECONDS,
                    ) {
                        min = "1"
                        value = config.settings.loopDelaySeconds.toString()
                    }
                }

                formGroup(ViewText.DEVIATION_TRIGGER) {
                    input(
                        CssClass.Form.InputGlass,
                        type = number,
                        name = FormFields.DEVIATION_TRIGGER_PERCENT,
                    ) {
                        step = "0.1"
                        min = "0"
                        value =
                            config.settings.deviationTriggerPercent.toString()
                    }
                }

                formGroup(ViewText.DUST_THRESHOLD) {
                    input(
                        CssClass.Form.InputGlass,
                        type = number,
                        name = FormFields.DUST_THRESHOLD_USD,
                    ) {
                        step = "0.5"
                        min = "0"
                        value = config.settings.dustThresholdUSD.toString()
                    }
                }

                formGroup(ViewText.FIAT_MAX_DRAWDOWN) {
                    input(
                        CssClass.Form.InputGlass,
                        type = number,
                        name = FormFields.FIAT_MAX_DRAWDOWN,
                    ) {
                        step = "1.0"
                        min = "0"
                        max = "100"
                        value = config.settings.fiatMaxDrawdown.toString()
                    }
                }

                formGroup(ViewText.FIAT_DEPLOYMENT_EXPONENT) {
                    input(
                        CssClass.Form.InputGlass,
                        type = number,
                        name = FormFields.FIAT_DEPLOYMENT_EXPONENT,
                    ) {
                        step = "0.1"
                        // HTML min is inclusive; step floor keeps spinner off invalid 0.
                        min = "0.1"
                        value =
                            config.settings.fiatDeploymentExponent.toString()
                    }
                }
            }
        }
    }

    private fun DIV.renderSafetyModesSection(config: AppConfig) {
        // SETT-1: promote the two highest-consequence controls to labelled toggle cards.
        formSection(ViewText.SAFETY_MODES, Icons.SHIELD_EXCLAMATION) {
            p(CssClass.Form.SectionSubtitle) { +ViewText.SAFETY_MODES_SUBTITLE }
            div(CssClass.Form.SafetyGroup) {
                div(CssClass.Form.SafetyToggles) {
                    renderSafetyCard(
                        name = FormFields.SIMULATION,
                        checked = config.settings.simulation,
                        title = ViewText.SIMULATION_MODE_TITLE,
                        desc = ViewText.SIMULATION_MODE_DESC,
                    )
                    renderSafetyCard(
                        name = FormFields.DRY_RUN,
                        checked = config.settings.dryRun,
                        title = ViewText.DRY_RUN_MODE_TITLE,
                        desc = ViewText.DRY_RUN_MODE_DESC,
                    )
                }
            }
        }
    }

    private fun DIV.renderSafetyCard(name: String, checked: Boolean, title: String, desc: String) {
        label(CssClass.Form.SafetyCard) {
            input(type = checkBox, name = name) {
                this.checked = checked
            }
            div(CssClass.Form.SafetyCardInner) {
                div(CssClass.Form.SafetyCardIcon) { icon(Icons.SHIELD_EXCLAMATION) }
                div(CssClass.Form.SafetyCardBody) {
                    div(CssClass.Form.SafetyCardTitleRow) {
                        span(CssClass.Form.SafetyCardTitle) { +title }
                        span(CssClass.Form.SafetyStatePill) {
                            span(CssClass.Form.SafetyStateOn) { +ViewText.SAFETY_ON }
                            span(CssClass.Form.SafetyStateOff) { +ViewText.SAFETY_OFF }
                        }
                    }
                    div(CssClass.Form.SafetyCardDesc) { +desc }
                }
            }
        }
    }

    private fun DIV.renderTargetAllocationsSection(config: AppConfig) {
        div(CssClass.Form.Section) {
            div(CssClass.Form.SectionHeader) {
                h3 {
                    +ViewText.TARGET_ALLOCATIONS
                }
                div(CssClass.StatusCard.Live) {
                    id = HtmlIds.TOTAL_ALLOCATED_DISPLAY
                    +ViewText.TOTAL_INITIAL
                }
            }

            div(CssClass.Form.AllocationListContainer) {
                id = HtmlIds.ALLOCATIONS_CONTAINER
                config.allocations.forEach { alloc ->
                    div(CssClass.Form.AllocationEditRow) {
                        div(CssClass.Form.AllocationEditSymbol) { +alloc.symbol.value }
                        input(
                            type = InputType.hidden,
                            name = FormFields.SYMBOLS,
                        ) { value = alloc.symbol.value }
                        div(CssClass.Form.AllocationEditInputWrapper) {
                            input(
                                CssClass.Form.InputGlass,
                                type = number,
                                name = FormFields.TARGETS,
                            ) {
                                step = "0.1"
                                min = "0"
                                max = "100"
                                value = alloc.targetPercent.toString()
                                attributes[HtmlAttrs.ONINPUT] =
                                    "updateAllocationTotal()"
                            }
                            span(CssClass.Form.PercentSuffix) { +"%" }
                        }
                        button(
                            CssClass.Button.Danger,
                            type = button,
                        ) {
                            attributes[HtmlAttrs.ONCLICK] =
                                "this.closest('.allocation-edit-row').remove(); updateAllocationTotal();"
                            +ViewText.REMOVE
                        }
                    }
                }
            }

            div(CssClass.Form.AddAssetBox) {
                input(CssClass.Form.InputGlass, type = text) {
                    id = HtmlIds.NEW_SYMBOL_INPUT
                    placeholder = ViewText.NEW_SYMBOL_PLACEHOLDER
                    attributes[HtmlAttrs.ONKEYDOWN] =
                        "if(event.key === 'Enter') { event.preventDefault(); addAssetRow(); }"
                }
                button(
                    CssClass.Button.Secondary,
                    type = button,
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
        body.script(src = rebalancerJsSrc()) {}
    }
}
