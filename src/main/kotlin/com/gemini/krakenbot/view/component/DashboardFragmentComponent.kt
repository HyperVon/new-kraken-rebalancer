package com.gemini.krakenbot.view.component

import com.gemini.krakenbot.config.Allocation
import com.gemini.krakenbot.model.OrderIntent
import com.gemini.krakenbot.model.OrderIntentState
import com.gemini.krakenbot.model.PortfolioSnapshot
import com.gemini.krakenbot.util.StreamStatus
import com.gemini.krakenbot.view.util.CssClass
import com.gemini.krakenbot.view.util.FormFields
import com.gemini.krakenbot.view.util.HtmlAttrs
import com.gemini.krakenbot.view.util.HtmlIds
import com.gemini.krakenbot.view.util.HtmxAttrs
import com.gemini.krakenbot.view.util.HtmxValues
import com.gemini.krakenbot.view.util.Routes
import com.gemini.krakenbot.view.util.ViewText
import com.gemini.krakenbot.view.util.div
import com.gemini.krakenbot.view.util.span
import kotlinx.html.ButtonType
import kotlinx.html.DIV
import kotlinx.html.FormMethod
import kotlinx.html.InputType
import kotlinx.html.button
import kotlinx.html.form
import kotlinx.html.h3
import kotlinx.html.id
import kotlinx.html.input
import kotlinx.html.option
import kotlinx.html.p
import kotlinx.html.select
import kotlinx.html.strong
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class DashboardFragmentComponent(
    private val overviewGridComponent: OverviewGridComponent,
    private val allocationChartComponent: AllocationChartComponent,
    private val performanceTableComponent: PerformanceTableComponent,
    private val recentActivityComponent: RecentActivityComponent,
) {
    private val timeFormatter =
        DateTimeFormatter.ofPattern("hh:mm:ss a")
            .withZone(ZoneId.systemDefault())

    context(div: DIV)
    fun render(
        latest: PortfolioSnapshot,
        history: List<PortfolioSnapshot>,
        allocations: List<Allocation> = emptyList(),
        delta24h: BigDecimal? = null,
        unresolvedIntents: List<OrderIntent> = emptyList(),
        csrfToken: String? = null,
    ) {
        val timeSinceUpdate =
            0L.coerceAtLeast(
                Instant.now().epochSecond - latest.timestamp.epochSecond,
            )
        val isStale = StreamStatus.isStale(timeSinceUpdate)

        // Mode plate stays in the shell; this OOB swap only refreshes STREAM/STALE
        // (SSE freshness — StatusCard.Live here means healthy stream, not live trading).
        renderStreamStatus(latest, timeSinceUpdate, isStale)

        if (unresolvedIntents.isNotEmpty()) {
            renderUnresolvedIntentsBanner(unresolvedIntents, csrfToken)
        }

        overviewGridComponent.render(latest, history, delta24h)

        div.div(CssClass.Layout.DetailGrid) {
            allocationChartComponent.render(latest, allocations)
            performanceTableComponent.render(latest)
        }

        recentActivityComponent.render(history)
    }

    context(div: DIV)
    private fun renderUnresolvedIntentsBanner(intents: List<OrderIntent>, csrfToken: String?) {
        div.div(CssClass.Utility.ErrorBanner) {
            h3 {
                +ViewText.UNRESOLVED_INTENT_BANNER_TITLE_PREFIX
                +"${intents.size}"
                +ViewText.UNRESOLVED_INTENT_BANNER_TITLE_SUFFIX
            }
            p {
                +ViewText.UNRESOLVED_INTENT_BANNER_BODY
            }
            div {
                for (intent in intents) {
                    val intentId = intent.id ?: continue
                    div {
                        p {
                            strong {
                                +ViewText.UNRESOLVED_INTENT_LINE_PREFIX
                                +"$intentId"
                                +ViewText.UNRESOLVED_INTENT_LINE_SUFFIX
                            }
                            +"${intent.side} ${intent.volume} ${intent.symbol} "
                            +"(~$${intent.usdAmount}) • State: ${intent.state}"
                        }
                        if (!intent.errorMessage.isNullOrBlank()) {
                            p {
                                +ViewText.UNRESOLVED_INTENT_ERROR_PREFIX
                                +"${intent.errorMessage}"
                            }
                        }
                        val resolveUrl = Routes.API_ORDER_INTENTS_RESOLVE_TEMPLATE.replace("{id}", "$intentId")
                        form(action = resolveUrl, method = FormMethod.post) {
                            attributes[HtmxAttrs.HX_POST] = resolveUrl
                            attributes[HtmxAttrs.HX_TARGET] = "body"
                            if (csrfToken != null) {
                                input(type = InputType.hidden, name = FormFields.CSRF_TOKEN) {
                                    value = csrfToken
                                }
                            }
                            select(classes = CssClass.Form.InputGlass.value) {
                                name = FormFields.ORDER_INTENT_STATE
                                option {
                                    value = OrderIntentState.CONFIRMED.name
                                    +ViewText.RESOLVE_INTENT_CONFIRMED_OPTION
                                }
                                option {
                                    value = OrderIntentState.REJECTED.name
                                    +ViewText.RESOLVE_INTENT_REJECTED_OPTION
                                }
                            }
                            input(type = InputType.text, classes = CssClass.Form.InputGlass.value) {
                                name = FormFields.ORDER_INTENT_ORDER_TXID
                                placeholder = ViewText.RESOLVE_INTENT_TXID_PLACEHOLDER
                            }
                            input(type = InputType.text, classes = CssClass.Form.InputGlass.value) {
                                name = FormFields.ORDER_INTENT_EVIDENCE
                                placeholder = ViewText.RESOLVE_INTENT_EVIDENCE_PLACEHOLDER
                                required = true
                            }
                            button(type = ButtonType.submit, classes = CssClass.Button.Primary.value) {
                                +ViewText.RESOLVE_INTENT_BUTTON_PREFIX
                                +"$intentId"
                            }
                        }
                    }
                }
            }
        }
    }

    context(div: DIV)
    private fun renderStreamStatus(latest: PortfolioSnapshot, timeSinceUpdate: Long, isStale: Boolean) {
        div.div(CssClass.Layout.HeaderStatus) {
            id = HtmlIds.HEADER_STATUS
            attributes[HtmxAttrs.HX_SWAP_OOB] = HtmxValues.TRUE
            val badgeClass = if (isStale) CssClass.StatusCard.Delayed else CssClass.StatusCard.Live
            val badgeText = if (isStale) ViewText.STREAM_STALE else ViewText.STREAM
            div(badgeClass) { +badgeText }
            val ageClass = if (isStale) CssClass.DataAge.ValueStale else CssClass.DataAge.Value
            span(ageClass) { +"$timeSinceUpdate${ViewText.AGO_SECONDS}" }
            span(CssClass.DataAge.Time) {
                attributes[HtmlAttrs.DATA_EPOCH] =
                    latest.timestamp.toEpochMilli().toString()
                +timeFormatter.format(latest.timestamp)
            }
        }
    }
}
