package com.gemini.krakenbot.view.component

import com.gemini.krakenbot.config.AppConfig
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
                            unsafe {
                                +"""<svg xmlns="http://www.w3.org/2000/svg" width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><line x1="19" y1="12" x2="5" y2="12"></line><polyline points="12 19 5 12 12 5"></polyline></svg>"""
                            }
                        }
                        h1 { +"Settings" }
                    }
                    button(type = ButtonType.submit, classes = "btn btn-primary") {
                        id = "save-button"
                        unsafe {
                            +"""<svg xmlns="http://www.w3.org/2000/svg" width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M19 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h11l5 5v11a2 2 0 0 1-2 2z"></path><polyline points="17 21 17 13 7 13 7 21"></polyline><polyline points="7 3 7 8 15 8"></polyline></svg>"""
                        }
                        span { +"Save Configuration" }
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
        div("form-section") {
            h3("form-section-title") {
                unsafe {
                    +"""<svg xmlns="http://www.w3.org/2000/svg" width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z"></path><line x1="12" y1="8" x2="12" y2="12"></line><line x1="12" y1="16" x2="12.01" y2="16"></line></svg>"""
                }
                +"Global Parameters"
            }

            div("grid-2col") {
                div("form-group") {
                    label(classes = "form-label") { +"Loop Interval (Seconds)" }
                    input(type = InputType.number, name = "loopDelaySeconds", classes = "input-glass") {
                        min = "1"
                        value = config.settings.loopDelaySeconds.toString()
                    }
                }

                div("form-group") {
                    label(classes = "form-label") { +"Deviation Trigger (%)" }
                    input(type = InputType.number, name = "deviationTriggerPercent", classes = "input-glass") {
                        step = "0.1"
                        min = "0"
                        value = config.settings.deviationTriggerPercent.toString()
                    }
                }

                div("form-group") {
                    label(classes = "form-label") { +"Dust Threshold ($)" }
                    input(type = InputType.number, name = "dustThresholdUSD", classes = "input-glass") {
                        step = "0.5"
                        value = config.settings.dustThresholdUSD.toString()
                    }
                }

                div("form-group") {
                    label(classes = "form-label") { +"Fiat Max Drawdown (%)" }
                    input(type = InputType.number, name = "fiatMaxDrawdown", classes = "input-glass") {
                        step = "1.0"
                        value = config.settings.fiatMaxDrawdown.toString()
                    }
                }

                div("form-group") {
                    label(classes = "form-label") { +"Fiat Deployment Exponent" }
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
                        span { +"Dry Run Mode (Safe)" }
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
                    +"Target Allocations"
                }
                div("status-badge live") {
                    id = "total-allocated-display"
                    +"Total: 0.00%"
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
                            +"Remove"
                        }
                    }
                }
            }

            div("add-asset-box") {
                input(type = InputType.text, classes = "input-glass") {
                    id = "new-symbol-input"
                    placeholder = "New Symbol (e.g. DOT)"
                    style = "text-transform: uppercase; flex-grow: 1;"
                    attributes["onkeydown"] = "if(event.key === 'Enter') { event.preventDefault(); addAssetRow(); }"
                }
                button(type = ButtonType.button, classes = "btn btn-secondary") {
                    attributes["onclick"] = "addAssetRow()"
                    unsafe {
                        +"""<svg xmlns="http://www.w3.org/2000/svg" width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><line x1="12" y1="5" x2="12" y2="19"></line><line x1="5" y1="12" x2="19" y2="12"></line></svg>"""
                    }
                    span { +"Add Asset" }
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
            """
        }
    }

    private fun BODY.renderSettingsScript() {
        script {
            unsafe {
                +"""
                function updateAllocationTotal() {
                    const targets = Array.from(document.querySelectorAll('input[name="targets"]')).map(input => parseFloat(input.value) || 0.0);
                    const total = targets.reduce((sum, val) => sum + val, 0.0);
                    const totalDisplay = document.getElementById('total-allocated-display');
                    totalDisplay.textContent = 'Total: ' + total.toFixed(2) + '%';
                    
                    const saveButton = document.getElementById('save-button');
                    const isValid = Math.abs(total - 100.0) <= 0.01;
                    
                    const symbols = Array.from(document.querySelectorAll('input[name="symbols"]')).map(input => input.value.toUpperCase());
                    const hasUsd = symbols.includes('USD');
                    
                    if (isValid && hasUsd) {
                        totalDisplay.className = 'status-badge live';
                        saveButton.removeAttribute('disabled');
                    } else {
                        totalDisplay.className = 'status-badge delayed';
                        saveButton.setAttribute('disabled', 'true');
                    }
                }
                
                function addAssetRow() {
                    const symbolInput = document.getElementById('new-symbol-input');
                    const symbol = symbolInput.value.trim().toUpperCase();
                    if (!symbol) return;
                    
                    const existingSymbols = Array.from(document.querySelectorAll('input[name="symbols"]')).map(input => input.value.toUpperCase());
                    if (existingSymbols.includes(symbol)) {
                        alert('Symbol already exists');
                        return;
                    }
                    
                    const container = document.getElementById('allocations-container');
                    const template = document.getElementById('allocation-row-template');
                    const clone = template.content.cloneNode(true);
                    
                    clone.querySelector('.symbol-label').textContent = symbol;
                    clone.querySelector('input[name="symbols"]').value = symbol;
                    clone.querySelector('input[name="targets"]').value = "0.0";
                    
                    container.appendChild(clone);
                    symbolInput.value = '';
                    updateAllocationTotal();
                }
                
                updateAllocationTotal();
                """
            }
        }
    }
}
