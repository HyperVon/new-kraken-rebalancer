package com.gemini.krakenbot.view.component

import com.gemini.krakenbot.model.PortfolioSnapshot
import com.gemini.krakenbot.view.util.Icons
import com.gemini.krakenbot.view.util.Icons.icon
import com.gemini.krakenbot.view.util.Layouts.glassPanel
import com.gemini.krakenbot.view.util.ViewText
import kotlinx.html.*

class RecentActivityComponent {
    fun DIV.render(history: List<PortfolioSnapshot>) {
        glassPanel(ViewText.RECENT_ACTIVITY, Icons.PULSE) {
            if (history.isEmpty()) {
                div("empty-history-box") {
                    icon(Icons.EMPTY_PIE)
                    h3 { +ViewText.RECENT_ACTIVITY }
                    p { +ViewText.NO_TRADING_HISTORY }
                }
            } else {
                div("table-wrapper custom-scrollbar max-h-100") {
                    table {
                        thead {
                            tr {
                                th { +ViewText.HEADER_TIME }
                                th { +ViewText.HEADER_ACTION }
                            }
                        }
                        tbody {
                            history.forEach { snapshot ->
                                val timeStr = snapshot.timestamp.toString().replace("T", " ").substringBefore(".")
                                if (snapshot.actions.isEmpty()) {
                                    tr("hoverable") {
                                        td("mono-col") { +timeStr }
                                        td {
                                            span {
                                                style = "color: var(--color-text-muted); font-style: italic; display: flex; align-items: center; gap: 0.5rem;"
                                                span {
                                                    style = "width: 0.375rem; height: 0.375rem; border-radius: 50%; background-color: var(--color-text-muted);"
                                                }
                                                +ViewText.NO_TRADES_EXECUTED
                                            }
                                        }
                                    }
                                } else {
                                    snapshot.actions.forEach { action ->
                                        val isBuy = action.uppercase().startsWith("BUY")
                                        val isSell = action.uppercase().startsWith("SELL")
                                        val badgeClass = if (isBuy) "badge badge-buy" else if (isSell) "badge badge-sell" else "badge badge-info"
                                        val badgeText = if (isBuy) "BUY" else if (isSell) "SELL" else "INFO"

                                        tr("hoverable") {
                                            td("mono-col") { +timeStr }
                                            td {
                                                div {
                                                    style = "display: flex; align-items: center; gap: 0.75rem;"
                                                    span(badgeClass) { +badgeText }
                                                    span { +action }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

