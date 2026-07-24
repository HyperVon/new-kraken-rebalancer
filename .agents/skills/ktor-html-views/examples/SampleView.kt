package com.gemini.krakenbot.view

import com.gemini.krakenbot.common.CssClass
import com.gemini.krakenbot.common.HtmlAttrs
import com.gemini.krakenbot.common.HtmlIds
import com.gemini.krakenbot.common.ViewText
import kotlinx.html.BODY
import kotlinx.html.button
import kotlinx.html.div
import kotlinx.html.h2
import kotlinx.html.p
import kotlinx.html.span

fun BODY.renderDashboardSummaryCard(statusText: String, isHealthy: Boolean) {
    div(CssClass.GlassCard.name) {
        id = HtmlIds.STATUS_CARD

        h2(CssClass.CardTitle.name) {
            +ViewText.PORTFOLIO_SUMMARY
        }

        div(CssClass.FlexBetween.name) {
            p { +"Status:" }
            span(if (isHealthy) CssClass.BadgeSuccess.name else CssClass.BadgeWarning.name) {
                +statusText
            }
        }

        button(CssClass.ButtonPrimary.name) {
            attributes[HtmlAttrs.HX_POST] = "/api/rebalance/trigger"
            attributes[HtmlAttrs.HX_TARGET] = "#${HtmlIds.STATUS_CARD}"
            attributes[HtmlAttrs.HX_SWAP] = "outerHTML"
            +ViewText.TRIGGER_REBALANCE
        }
    }
}
