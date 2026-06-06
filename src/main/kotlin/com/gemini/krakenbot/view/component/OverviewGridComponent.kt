package com.gemini.krakenbot.view.component

import com.gemini.krakenbot.model.Asset
import com.gemini.krakenbot.model.PortfolioSnapshot
import com.gemini.krakenbot.view.util.CssClasses.OVERVIEW_GRID
import com.gemini.krakenbot.view.util.CssClasses.TEXT_DANGER
import com.gemini.krakenbot.view.util.Formatter.formatCurrency
import com.gemini.krakenbot.view.util.Formatter.formatPercent
import com.gemini.krakenbot.view.util.Formatter.getDeviationClass
import com.gemini.krakenbot.view.util.Formatter.getDeviationSign
import com.gemini.krakenbot.view.util.Icons.CIRCLES
import com.gemini.krakenbot.view.util.Icons.TREND_UP
import com.gemini.krakenbot.view.util.Icons.WALLET
import com.gemini.krakenbot.view.util.Layouts.statusCard
import com.gemini.krakenbot.view.util.ViewText.ASSETS_SUFFIX
import com.gemini.krakenbot.view.util.ViewText.BASE_PREFIX
import com.gemini.krakenbot.view.util.ViewText.CASH_USD
import com.gemini.krakenbot.view.util.ViewText.CRYPTO_ASSETS
import com.gemini.krakenbot.view.util.ViewText.DEV_PREFIX
import com.gemini.krakenbot.view.util.ViewText.DRAWDOWN_PREFIX
import com.gemini.krakenbot.view.util.ViewText.NO_USD_DATA
import com.gemini.krakenbot.view.util.ViewText.TARGET_PREFIX
import com.gemini.krakenbot.view.util.ViewText.TOTAL_PORTFOLIO
import kotlinx.html.DIV
import kotlinx.html.div
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

        div.div(OVERVIEW_GRID) {
            statusCard(
                title = TOTAL_PORTFOLIO,
                iconSvg = TREND_UP,
                value = "$${formatCurrency(totalValue)}"
            ) {
                val drawdown = latest.drawdownPercent
                val isDrawdown = drawdown.signum() > 0
                val colorClass = if (isDrawdown) TEXT_DANGER else ""
                span(colorClass) {
                    +"${DRAWDOWN_PREFIX}${
                        formatPercent(
                            drawdown
                        )
                    }%"
                }
            }

            statusCard(
                title = CASH_USD,
                iconSvg = WALLET,
                value = "$${formatCurrency(usdValue)}",
                isSuccess = true
            ) {
                if (usdAsset != null) {
                    val currentPct = usdAsset.currentPercent
                    val targetPct = latest.effectiveUsdTargetPercent
                    val baseTargetPct = usdAsset.targetPercent
                    val dev = usdAsset.deviationPercent
                    val devClass = getDeviationClass(dev)
                    val devSign = getDeviationSign(dev)

                    span {
                        +"${formatPercent(currentPct)}% | ${TARGET_PREFIX}${
                            formatPercent(
                                targetPct
                            )
                        }%"
                        if (abs(targetPct.toDouble() - baseTargetPct.toDouble()) > 0.01) {
                            +" (${BASE_PREFIX}${
                                formatPercent(
                                    baseTargetPct
                                )
                            }%)"
                        }
                        +" | "
                        span(devClass) {
                            +"${DEV_PREFIX}$devSign${
                                formatPercent(
                                    dev
                                )
                            }%"
                        }
                    }
                } else {
                    +NO_USD_DATA
                }
            }

            statusCard(
                title = CRYPTO_ASSETS,
                iconSvg = CIRCLES,
                value = "$${formatCurrency(cryptoValue)}"
            ) {
                span {
                    +"${formatPercent(cryptoPercent)}% | ${TARGET_PREFIX}${
                        formatPercent(
                            cryptoTargetPercent
                        )
                    }% | $cryptoCount${ASSETS_SUFFIX}"
                }
            }
        }
    }
}

