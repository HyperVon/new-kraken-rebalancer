package com.gemini.krakenbot.view.component

import com.gemini.krakenbot.config.AppConfig
import com.gemini.krakenbot.view.util.Icons
import com.gemini.krakenbot.view.util.Icons.icon
import com.gemini.krakenbot.view.util.Layouts.formGroup
import com.gemini.krakenbot.view.util.Layouts.formSection
import com.gemini.krakenbot.view.util.ViewText
import kotlinx.html.*

class SettingsFormComponent {
    fun BODY.render(config: AppConfig, errorMessage: String?) {
        div("container") {
            form {
                attributes["hx-post"] = "/settings"
                attributes["hx-target"] = "body"
                attributes["hx-swap"] = "innerHTML"

                header {
                    div("header-title-section") {
                        a(href = "/", classes = "btn btn-secondary") {
                            style = "padding: 0.5rem;"
                            icon(Icons.BACK_ARROW)
                        }
                        h1 { +ViewText.SETTINGS_TITLE }
                    }
                    button(type = ButtonType.submit, classes = "btn btn-primary") {
                        id = "save-button"
                        icon(Icons.FLOPPY_DISK)
                        span { +ViewText.SAVE_CONFIGURATION }
                    }
                }

                if (errorMessage != null) {
                    div {
                        style = "background-color: rgba(239, 68, 68, 0.15); border: 1px solid rgba(239, 68, 68, 0.3); color: #fecaca; padding: 1rem; border-radius: 0.5rem; margin-bottom: 1.5rem; font-weight: 500;"
                        +errorMessage
                    }
                }

                div("glass-panel") {
                    renderGlobalParametersSection(config)
                    renderTargetAllocationsSection(config)
                }
            }
        }

        renderSettingsTemplate()
        renderSettingsScript()
    }

    private fun DIV.renderGlobalParametersSection(config: AppConfig) {
        formSection(ViewText.GLOBAL_PARAMETERS, Icons.SHIELD_EXCLAMATION) {
            div("grid-2col") {
                formGroup(ViewText.LOOP_INTERVAL) {
                    input(type = InputType.number, name = "loopDelaySeconds", classes = "input-glass") {
                        min = "1"
                        value = config.settings.loopDelaySeconds.toString()
                    }
                }

                formGroup(ViewText.DEVIATION_TRIGGER) {
                    input(type = InputType.number, name = "deviationTriggerPercent", classes = "input-glass") {
                        step = "0.1"
                        min = "0"
                        value = config.settings.deviationTriggerPercent.toString()
                    }
                }

                formGroup(ViewText.DUST_THRESHOLD) {
                    input(type = InputType.number, name = "dustThresholdUSD", classes = "input-glass") {
                        step = "0.5"
                        value = config.settings.dustThresholdUSD.toString()
                    }
                }

                formGroup(ViewText.FIAT_MAX_DRAWDOWN) {
                    input(type = InputType.number, name = "fiatMaxDrawdown", classes = "input-glass") {
                        step = "1.0"
                        value = config.settings.fiatMaxDrawdown.toString()
                    }
                }

                formGroup(ViewText.FIAT_DEPLOYMENT_EXPONENT) {
                    input(type = InputType.number, name = "fiatDeploymentExponent", classes = "input-glass") {
                        step = "0.1"
                        value = config.settings.fiatDeploymentExponent.toString()
                    }
                }

                div("form-group") {
                    style = "justify-content: center; padding-top: 1rem;"
                    label("checkbox-container") {
                        input(type = InputType.checkBox, name = "dryRun") {
                            checked = config.settings.dryRun
                        }
                        div("checkbox-custom") {}
                        span { +ViewText.DRY_RUN_MODE }
                    }
                }
            }
        }
    }

    private fun DIV.renderTargetAllocationsSection(config: AppConfig) {
        div("form-section") {
            div {
                style = "display: flex; justify-content: space-between; align-items: center; margin-bottom: 1.25rem;"
                h3 {
                    style = "font-size: 1.125rem; font-weight: 600; color: white; margin: 0;"
                    +ViewText.TARGET_ALLOCATIONS
                }
                div("status-badge live") {
                    id = "total-allocated-display"
                    +ViewText.TOTAL_INITIAL
                }
            }

            div("allocation-list-container") {
                id = "allocations-container"
                config.allocations.forEach { alloc ->
                    div("allocation-edit-row") {
                        div("allocation-edit-symbol") { +alloc.symbol }
                        input(type = InputType.hidden, name = "symbols") { value = alloc.symbol }
                        div("allocation-edit-input-wrapper") {
                            input(type = InputType.number, name = "targets", classes = "input-glass") {
                                step = "0.1"
                                value = alloc.targetPercent.toString()
                                attributes["oninput"] = "updateAllocationTotal()"
                            }
                            span("percent-suffix") { +"%" }
                        }
                        button(type = ButtonType.button, classes = "btn btn-danger") {
                            attributes["onclick"] = "this.closest('.allocation-edit-row').remove(); updateAllocationTotal();"
                            +ViewText.REMOVE
                        }
                    }
                }
            }

            div("add-asset-box") {
                input(type = InputType.text, classes = "input-glass") {
                    id = "new-symbol-input"
                    placeholder = ViewText.NEW_SYMBOL_PLACEHOLDER
                    style = "text-transform: uppercase; flex-grow: 1;"
                    attributes["onkeydown"] = "if(event.key === 'Enter') { event.preventDefault(); addAssetRow(); }"
                }
                button(type = ButtonType.button, classes = "btn btn-secondary") {
                    attributes["onclick"] = "addAssetRow()"
                    icon(Icons.PLUS)
                    span { +ViewText.ADD_ASSET }
                }
            }
        }
    }

    private fun BODY.renderSettingsTemplate() {
        unsafe {
            +"""
            <template id="allocation-row-template">
                <div class="allocation-edit-row">
                    <div class="allocation-edit-symbol symbol-label"></div>
                    <input type="hidden" name="symbols">
                    <div class="allocation-edit-input-wrapper">
                        <input type="number" step="0.1" name="targets" class="input-glass" oninput="updateAllocationTotal()">
                        <span class="percent-suffix">%</span>
                    </div>
                    <button type="button" class="btn btn-danger" onclick="this.closest('.allocation-edit-row').remove(); updateAllocationTotal();">Remove</button>
                </div>
            </template>
            """.trimIndent()
        }
    }

    private fun BODY.renderSettingsScript() {
        script(src = "/static/settings.js") {}
    }
}
