package com.gemini.krakenbot.view.component

import com.gemini.krakenbot.model.PortfolioSnapshot
import kotlinx.html.*

class RecentActivityComponent {
    fun DIV.render(history: List<PortfolioSnapshot>) {
        div("glass-panel") {
            h2("glass-panel-title") {
                unsafe {
                    +"""<svg xmlns="http://www.w3.org/2000/svg" width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="22 12 18 12 15 21 9 3 6 12 2 12"></polyline></svg>"""
                }
                +"Recent Activity"
            }

            if (history.isEmpty()) {
                div("empty-history-box") {
                    unsafe {
                        +"""<svg xmlns="http://www.w3.org/2000/svg" width="48" height="48" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M12 2a10 10 0 1 0 10 10H12V2z"></path></svg>"""
                    }
                    h3 { +"Recent Activity" }
                    p { +"No trading history available." }
                }
            } else {
                div("table-wrapper custom-scrollbar max-h-100") {
                    table {
                        thead {
                            tr {
                                th { +"Time" }
                                th { +"Action" }
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
                                                +"No trades executed (Cycle complete)"
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
