package com.gemini.krakenbot.view.component

import com.gemini.krakenbot.domain.ALLOCATION_TOLERANCE
import com.gemini.krakenbot.domain.HUNDRED
import com.gemini.krakenbot.model.Asset
import com.gemini.krakenbot.model.PortfolioSnapshot
import com.gemini.krakenbot.util.PrecisionConstants
import com.gemini.krakenbot.view.util.ChartProps
import com.gemini.krakenbot.view.util.CssClass
import com.gemini.krakenbot.view.util.Formatter
import com.gemini.krakenbot.view.util.Icons
import com.gemini.krakenbot.view.util.Icons.icon
import com.gemini.krakenbot.view.util.ViewText
import com.gemini.krakenbot.view.util.div
import com.gemini.krakenbot.view.util.span
import kotlinx.html.DIV
import kotlinx.html.style
import kotlinx.html.unsafe
import java.math.BigDecimal
import java.math.RoundingMode

class OverviewGridComponent {
    context(div: DIV)
    fun render(latest: PortfolioSnapshot) {
        val totalValue = latest.totalValueUSD
        val usdAsset = latest.assets[Asset.USD]
        val usdValue = usdAsset?.valueUSD ?: BigDecimal.ZERO
        val cryptoValue = totalValue - usdValue

        val assetsList = latest.assets.values.filter { !it.symbol.isUsd }
        val cryptoPercent =
            assetsList.fold(BigDecimal.ZERO) { acc, asset -> acc.add(asset.currentPercent) }
        val cryptoTargetPercent =
            assetsList.fold(BigDecimal.ZERO) { acc, asset -> acc.add(asset.targetPercent) }
        val cryptoCount = assetsList.size

        div.div(CssClass.Layout.HeroGrid) {
            renderHeroCard(latest)
            div(CssClass.Layout.HeroSide) {
                renderCashTile(latest, usdAsset, usdValue)
                renderCryptoTile(cryptoValue, cryptoPercent, cryptoTargetPercent, cryptoCount)
            }
        }
    }

    private fun DIV.renderHeroCard(latest: PortfolioSnapshot) {
        div(CssClass.Hero.Card) {
            div(CssClass.Hero.CardText) {
                div(CssClass.Hero.Label) {
                    icon(Icons.TREND_UP)
                    +ViewText.TOTAL_PORTFOLIO
                }
                div(CssClass.Hero.Value) { +"$${Formatter.formatCurrency(latest.totalValueUSD)}" }
            }
        }
    }

    private fun DIV.renderCashTile(
        latest: PortfolioSnapshot,
        usdAsset: PortfolioSnapshot.AssetSnapshot?,
        usdValue: BigDecimal,
    ) {
        div(CssClass.Hero.TileCash) {
            div(CssClass.Hero.TileHeader) {
                icon(Icons.WALLET)
                span(CssClass.Hero.TileTitle) { +ViewText.CASH_USD }
            }
            div(CssClass.Hero.TileValue) { +"$${Formatter.formatCurrency(usdValue)}" }
            if (usdAsset != null) {
                val currentPct = usdAsset.currentPercent
                renderTileBar(currentPct, ChartProps.COLOR_EMERALD)
                val targetPct = latest.effectiveUsdTargetPercent
                val baseTargetPct = usdAsset.targetPercent
                val dev = usdAsset.deviationPercent
                val devClass = Formatter.getDeviationClass(dev)
                val devSign = Formatter.getDeviationSign(dev)
                div(CssClass.Hero.TileMeta) {
                    +"${ViewText.TARGET_PREFIX}${Formatter.formatPercent(targetPct)}%"
                    if ((targetPct - baseTargetPct).abs() > PrecisionConstants.ALLOCATION_TOLERANCE) {
                        +" (${ViewText.BASE_PREFIX}${Formatter.formatPercent(baseTargetPct)}%)"
                    }
                    +" | "
                    span(devClass) { +"${ViewText.DEV_PREFIX}$devSign${Formatter.formatPercent(dev)}%" }
                }
            } else {
                div(CssClass.Hero.TileMeta) { +ViewText.NO_USD_DATA }
            }
        }
    }

    private fun DIV.renderCryptoTile(
        cryptoValue: BigDecimal,
        cryptoPercent: BigDecimal,
        cryptoTargetPercent: BigDecimal,
        cryptoCount: Int,
    ) {
        div(CssClass.Hero.TileCrypto) {
            div(CssClass.Hero.TileHeader) {
                icon(Icons.CIRCLES)
                span(CssClass.Hero.TileTitle) { +ViewText.CRYPTO_ASSETS }
            }
            div(CssClass.Hero.TileValue) { +"$${Formatter.formatCurrency(cryptoValue)}" }
            renderTileBar(cryptoPercent, ChartProps.COLOR_BLUE)
            div(CssClass.Hero.TileMeta) {
                val target = "${ViewText.TARGET_PREFIX}${Formatter.formatPercent(cryptoTargetPercent)}%"
                +"$target | $cryptoCount${ViewText.ASSETS_SUFFIX}"
            }
        }
    }

    private fun DIV.renderTileBar(percent: BigDecimal, color: String) {
        val pct = percent.max(BigDecimal.ZERO).min(PrecisionConstants.HUNDRED).setScale(2, RoundingMode.HALF_UP)
        div(CssClass.Hero.TileBarRow) {
            div(CssClass.Hero.TileBarTrack) {
                div(CssClass.Hero.TileBarFill) {
                    style = "width: $pct%; background-color: $color;"
                }
            }
            span(CssClass.Hero.TileMeta) { +"${Formatter.formatPercent(percent)}%" }
        }
    }
}
