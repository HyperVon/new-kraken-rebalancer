package com.gemini.krakenbot.view.component

import com.gemini.krakenbot.config.AppConfig
import com.gemini.krakenbot.domain.QualityAllocation
import com.gemini.krakenbot.util.PrecisionConstants
import com.gemini.krakenbot.view.util.ActiveNav
import com.gemini.krakenbot.view.util.AllocationEditor
import com.gemini.krakenbot.view.util.ChartProps
import com.gemini.krakenbot.view.util.CssClass
import com.gemini.krakenbot.view.util.FormFields
import com.gemini.krakenbot.view.util.HtmlAttrs
import com.gemini.krakenbot.view.util.HtmlIds
import com.gemini.krakenbot.view.util.HtmxAttrs
import com.gemini.krakenbot.view.util.HtmxValues
import com.gemini.krakenbot.view.util.Icons
import com.gemini.krakenbot.view.util.Icons.icon
import com.gemini.krakenbot.view.util.Routes
import com.gemini.krakenbot.view.util.SettingsState
import com.gemini.krakenbot.view.util.ViewText
import com.gemini.krakenbot.view.util.brandWithMode
import com.gemini.krakenbot.view.util.button
import com.gemini.krakenbot.view.util.div
import com.gemini.krakenbot.view.util.formGroup
import com.gemini.krakenbot.view.util.formSection
import com.gemini.krakenbot.view.util.h3
import com.gemini.krakenbot.view.util.input
import com.gemini.krakenbot.view.util.label
import com.gemini.krakenbot.view.util.loopControl
import com.gemini.krakenbot.view.util.p
import com.gemini.krakenbot.view.util.primaryNav
import com.gemini.krakenbot.view.util.rebalancerJsSrc
import com.gemini.krakenbot.view.util.span
import kotlinx.html.BODY
import kotlinx.html.ButtonType.button
import kotlinx.html.ButtonType.submit
import kotlinx.html.DIV
import kotlinx.html.FlowContent
import kotlinx.html.InputType.*
import kotlinx.html.form
import kotlinx.html.header
import kotlinx.html.id
import kotlinx.html.script
import kotlinx.html.time
import kotlinx.html.unsafe

/** Raw, display-only form values used when a rejected request needs to recover the open form. */
data class SettingsFormValues(
    val loopDelaySeconds: String,
    val deviationTriggerPercent: String,
    val minimumOrderSizeUsd: String,
    val simulation: Boolean,
    val dryRun: Boolean,
    val allocations: List<SettingsAllocationFormValue>,
    val scoreEmphasis: String,
    val scoreSleevePercent: String,
)

data class SettingsAllocationFormValue(
    val symbol: String,
    val color: String,
    val targetPercent: String,
    val score: String,
)

class SettingsFormComponent {
    private data class NumericFieldSpec(
        val label: String,
        val name: String,
        val value: String,
        val step: String? = null,
        val min: String? = null,
        val max: String? = null,
    )

    private fun numericFieldSpecs(config: AppConfig, formValues: SettingsFormValues?) = listOf(
        NumericFieldSpec(
            ViewText.LOOP_INTERVAL,
            FormFields.LOOP_DELAY_SECONDS,
            formValues?.loopDelaySeconds ?: config.settings.loopDelaySeconds.toString(),
            min = "1",
        ),
        NumericFieldSpec(
            ViewText.DEVIATION_TRIGGER,
            FormFields.DEVIATION_TRIGGER_PERCENT,
            formValues?.deviationTriggerPercent ?: config.settings.deviationTriggerPercent.toString(),
            step = "0.1",
            min = "0",
        ),
        NumericFieldSpec(
            ViewText.MINIMUM_ORDER_SIZE,
            FormFields.MINIMUM_ORDER_SIZE_USD,
            formValues?.minimumOrderSizeUsd ?: config.settings.minimumOrderSizeUSD.toString(),
            step = "0.5",
            min = "2",
        ),
    )

    context(body: BODY)
    fun render(
        config: AppConfig,
        errorMessage: String?,
        csrfToken: String,
        paused: Boolean = false,
        formValues: SettingsFormValues? = null,
    ) {
        renderForm(
            body,
            config,
            errorMessage,
            csrfToken,
            paused,
            formValues,
        )
        renderSettingsScript()
    }

    fun renderForm(
        parent: FlowContent,
        config: AppConfig,
        errorMessage: String?,
        csrfToken: String,
        paused: Boolean = false,
        formValues: SettingsFormValues? = null,
    ) {
        parent.div(CssClass.Layout.Container) {
            form {
                id = SettingsState.FORM_ID
                attributes[SettingsState.BASELINE_ATTRIBUTE] = settingsBaseline(config)
                attributes[HtmxAttrs.HX_POST] = Routes.SETTINGS
                attributes[HtmxAttrs.HX_TARGET] = HtmxValues.BODY
                attributes[HtmxAttrs.HX_SWAP] = HtmxValues.INNER_HTML
                input(type = hidden, name = FormFields.CSRF_TOKEN) {
                    value = csrfToken
                    id = HtmlIds.CSRF_TOKEN
                }

                header {
                    brandWithMode(config.settings) {
                        loopControl(paused)
                    }
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

                time(CssClass.Utility.Hidden.value) {
                    id = SettingsState.SAVE_STATUS_ID
                }

                if (errorMessage != null) {
                    div(CssClass.Utility.ErrorBanner) {
                        +errorMessage
                    }
                }

                div(CssClass.Layout.GlassPanel) {
                    renderGlobalParametersSection(
                        config,
                        formValues,
                    )
                    renderSafetyModesSection(config, formValues)
                    renderTargetAllocationsSection(config, formValues)
                }
            }
        }
    }

    private fun settingsBaseline(config: AppConfig): String = buildString {
        fun appendField(name: String, value: String) {
            append(name.length).append(':').append(name)
            append(value.length).append(':').append(value)
        }

        appendField(FormFields.LOOP_DELAY_SECONDS, config.settings.loopDelaySeconds.toString())
        appendField(FormFields.DEVIATION_TRIGGER_PERCENT, config.settings.deviationTriggerPercent.toString())
        appendField(FormFields.MINIMUM_ORDER_SIZE_USD, config.settings.minimumOrderSizeUSD.toString())
        appendField(FormFields.SIMULATION, config.settings.simulation.toString())
        appendField(FormFields.DRY_RUN, config.settings.dryRun.toString())
        config.allocations.forEach { allocation ->
            appendField(FormFields.SYMBOLS, allocation.symbol.value)
            appendField(FormFields.COLORS, allocation.color ?: ChartProps.SOLID_FALLBACK)
            appendField(FormFields.SCORES, config.settings.qualityScores[allocation.symbol.value]?.toString().orEmpty())
            appendField(FormFields.TARGETS, allocation.targetPercent.toString())
        }
        appendField(FormFields.SCORE_EMPHASIS, QualityAllocation.DEFAULT_EMPHASIS.toString())
        appendField(FormFields.SCORE_SLEEVE_PERCENT, "")
    }

    private fun DIV.renderGlobalParametersSection(config: AppConfig, formValues: SettingsFormValues?) {
        formSection(ViewText.GLOBAL_PARAMETERS, Icons.SHIELD_EXCLAMATION) {
            div(CssClass.Form.Grid2Col) {
                numericFieldSpecs(config, formValues).forEach { field ->
                    formGroup(field.label, field.name) {
                        input(CssClass.Form.InputGlass, type = number, name = field.name) {
                            id = field.name
                            field.step?.let { step = it }
                            field.min?.let { min = it }
                            field.max?.let { max = it }
                            value = field.value
                        }
                    }
                }
            }
        }
    }

    private fun DIV.renderSafetyModesSection(config: AppConfig, formValues: SettingsFormValues?) {
        // SETT-1: promote the two highest-consequence controls to labelled toggle cards.
        formSection(ViewText.SAFETY_MODES, Icons.SHIELD_EXCLAMATION) {
            p(CssClass.Form.SectionSubtitle) { +ViewText.SAFETY_MODES_SUBTITLE }
            div(CssClass.Form.SafetyGroup) {
                div(CssClass.Form.SafetyToggles) {
                    renderSafetyCard(
                        name = FormFields.SIMULATION,
                        checked = formValues?.simulation ?: config.settings.simulation,
                        title = ViewText.SIMULATION_MODE_TITLE,
                        desc = ViewText.SIMULATION_MODE_DESC,
                    )
                    renderSafetyCard(
                        name = FormFields.DRY_RUN,
                        checked = formValues?.dryRun ?: config.settings.dryRun,
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

    private fun DIV.renderTargetAllocationsSection(config: AppConfig, formValues: SettingsFormValues?) {
        div(CssClass.Form.Section) {
            div(CssClass.Form.SectionHeader) {
                h3 {
                    +ViewText.TARGET_ALLOCATIONS
                }
                div(CssClass.Form.AllocationTotal + CssClass.Form.AllocationTotalOk) {
                    id = HtmlIds.TOTAL_ALLOCATED_DISPLAY
                    +ViewText.TOTAL_INITIAL
                }
            }

            div(CssClass.Form.AllocationListContainer) {
                id = HtmlIds.ALLOCATIONS_CONTAINER
                val allocations = formValues?.allocations ?: config.allocations.map { alloc ->
                    SettingsAllocationFormValue(
                        symbol = alloc.symbol.value,
                        color = alloc.color ?: ChartProps.SOLID_FALLBACK,
                        targetPercent = alloc.targetPercent.toString(),
                        score = config.settings.qualityScores[alloc.symbol.value]?.toString().orEmpty(),
                    )
                }
                allocations.forEach { alloc ->
                    unsafe {
                        +AllocationEditor.editRow(
                            symbol = alloc.symbol,
                            color = alloc.color,
                            targetPercent = alloc.targetPercent,
                            score = alloc.score,
                        )
                    }
                }
            }

            // The score controls and the add-asset control used to share one dashed box, which read
            // as a single form of related fields. They are separate jobs, so they are separate
            // groups. This one carries no group title: "scored sleeve" is the name of the second
            // field, and titling the whole box with it made the emphasis field look unexplained.
            // The hint leads instead, and each field names itself the way the rows do.
            div(CssClass.Form.AllocationControlRow) {
                div(CssClass.Form.AllocationControlGroup) {
                    span(CssClass.Form.AllocationControlHint) { +ViewText.ALLOCATION_SCORE_HINT }
                    div(CssClass.Form.AllocationControlInputs) {
                        div(CssClass.Form.AllocationControlField) {
                            span(CssClass.Form.AllocationFieldLabel) { +ViewText.ALLOCATION_SCORE_EMPHASIS }
                            input(CssClass.Form.InputGlass, type = number) {
                                id = HtmlIds.SCORE_EMPHASIS_INPUT
                                name = FormFields.SCORE_EMPHASIS
                                value = formValues?.scoreEmphasis ?: QualityAllocation.DEFAULT_EMPHASIS.toString()
                                min = "1"
                                max = QualityAllocation.MAX_EMPHASIS.toString()
                            }
                        }
                        div(CssClass.Form.AllocationControlField) {
                            span(CssClass.Form.AllocationFieldLabel) { +ViewText.ALLOCATION_SLEEVE_FIELD }
                            input(CssClass.Form.InputGlass, type = number) {
                                id = HtmlIds.SCORE_SLEEVE_INPUT
                                name = FormFields.SCORE_SLEEVE_PERCENT
                                step = PrecisionConstants.ALLOCATION_STEP_PERCENT.toString()
                                placeholder = ViewText.ALLOCATION_SLEEVE_PLACEHOLDER
                                formValues?.scoreSleevePercent?.let { value = it }
                            }
                        }
                        button(
                            CssClass.Button.Secondary,
                            type = button,
                        ) {
                            attributes[HtmxAttrs.HX_POST] = Routes.FRAGMENT_SETTINGS_ALLOCATIONS_PREVIEW
                            attributes[HtmxAttrs.HX_INCLUDE] = "closest form"
                            attributes[HtmxAttrs.HX_TARGET] = "#${HtmlIds.ALLOCATIONS_CONTAINER}"
                            attributes[HtmxAttrs.HX_SWAP] = HtmxValues.INNER_HTML
                            span { +ViewText.ALLOCATIONS_FROM_SCORES }
                        }
                    }
                }
            }

            div(CssClass.Form.AllocationControlRow) {
                div(CssClass.Form.AllocationControlGroup) {
                    div(CssClass.Form.AllocationControlInputs) {
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
        }
    }

    context(body: BODY)
    private fun renderSettingsScript() {
        body.script(src = rebalancerJsSrc()) {}
    }
}
