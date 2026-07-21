package com.gemini.krakenbot.view.component

import com.gemini.krakenbot.model.Asset
import com.gemini.krakenbot.model.PortfolioSnapshot
import com.gemini.krakenbot.view.util.CssClass
import com.gemini.krakenbot.view.util.Formatter
import com.gemini.krakenbot.view.util.Icons
import com.gemini.krakenbot.view.util.Layouts.statusCard
import com.gemini.krakenbot.view.util.ViewText
import com.gemini.krakenbot.view.util.div
import kotlinx.html.DIV
import kotlinx.html.span
import java.math.BigDecimal
import kotlin.math.abs

class OverviewGridComponent {
    context(div: DIV)
    fun render(latest: PortfolioSnapshot) {
        val totalValue = latest.totalValueUSD
        val usdAsset = latest.assets[Asset.USD]
        val usdValue = usdAsset?.valueUSD ?: BigDecimal.ZERO
        val cryptoValue = totalValue - usdValue

        val assetsList =
            latest.assets.values.filter { !it.symbol.isUsd }
        val cryptoPercent = assetsList.sumOf { it.currentPercent.toDouble() }
        val cryptoTargetPercent =
            assetsList.sumOf { it.targetPercent.toDouble() }
        val cryptoCount = assetsList.size

        div.div(CssClass.Layout.OverviewGrid) {
            statusCard(
                title = ViewText.TOTAL_PORTFOLIO,
                iconSvg = Icons.TREND_UP,
                value = "$${Formatter.formatCurrency(totalValue)}"
            ) {
                val drawdown = latest.drawdownPercent
                val isDrawdown = drawdown.signum() > 0
                val colorClass = if (isDrawdown) CssClass.Utility.TextDanger.value else ""
                span(classes = colorClass) {
                    +"${ViewText.DRAWDOWN_PREFIX}${
                        Formatter.formatPercent(
                            drawdown
                        )
                    }%"
                }
            }

            statusCard(
                title = ViewText.CASH_USD,
                iconSvg = Icons.WALLET,
                value = "$${Formatter.formatCurrency(usdValue)}",
                isSuccess = true
            ) {
                if (usdAsset != null) {
                    val currentPct = usdAsset.currentPercent
                    val targetPct = latest.effectiveUsdTargetPercent
                    val baseTargetPct = usdAsset.targetPercent
                    val dev = usdAsset.deviationPercent
                    val devClass = Formatter.getDeviationClass(dev)
                    val devSign = Formatter.getDeviationSign(dev)

                    span {
                        +"${Formatter.formatPercent(currentPct)}% | ${ViewText.TARGET_PREFIX}${
                            Formatter.formatPercent(
                                targetPct
                            )
                        }%"
                        if (abs(targetPct.toDouble() - baseTargetPct.toDouble()) > 0.01) {
                            +" (${ViewText.BASE_PREFIX}${
                                Formatter.formatPercent(
                                    baseTargetPct
                                )
                            }%)"
                        }
                        +" | "
                        span(devClass) {
                            +"${ViewText.DEV_PREFIX}$devSign${
                                Formatter.formatPercent(
                                    dev
                                )
                            }%"
                        }
                    }
                } else {
                    +ViewText.NO_USD_DATA
                }
            }

            statusCard(
                title = ViewText.CRYPTO_ASSETS,
                iconSvg = Icons.CIRCLES,
                value = "$${Formatter.formatCurrency(cryptoValue)}"
            ) {
                span {
                    +"${Formatter.formatPercent(cryptoPercent)}% | ${ViewText.TARGET_PREFIX}${
                        Formatter.formatPercent(
                            cryptoTargetPercent
                        )
                    }% | $cryptoCount${ViewText.ASSETS_SUFFIX}"
                }
            }
        }
    }
}
