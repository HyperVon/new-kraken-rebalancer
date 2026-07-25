package com.gemini.krakenbot.view.component

import com.gemini.krakenbot.model.Asset
import com.gemini.krakenbot.model.PortfolioSnapshot
import com.gemini.krakenbot.service.impl.PortfolioCalculations.HUNDRED
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
    fun render(latest: PortfolioSnapshot, history: List<PortfolioSnapshot>) {
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
            renderHeroCard(latest, history)
            div(CssClass.Layout.HeroSide) {
                renderCashTile(latest, usdAsset, usdValue)
                renderCryptoTile(cryptoValue, cryptoPercent, cryptoTargetPercent, cryptoCount)
            }
        }
    }

    private fun DIV.renderHeroCard(latest: PortfolioSnapshot, history: List<PortfolioSnapshot>) {
        div(CssClass.Hero.Card) {
            div(CssClass.Hero.CardText) {
                div(CssClass.Hero.Label) {
                    icon(Icons.TREND_UP)
                    +ViewText.TOTAL_PORTFOLIO
                }
                div(CssClass.Hero.Value) { +"$${Formatter.formatCurrency(latest.totalValueUSD)}" }
                renderDeltaRow(latest, history)
                renderDrawdown(latest.drawdownPercent)
            }
            val spark = sparklineSvg(history)
            if (spark.isNotEmpty()) {
                div(CssClass.Hero.Spark) { unsafe { +spark } }
            }
        }
    }

    private fun DIV.renderDeltaRow(latest: PortfolioSnapshot, history: List<PortfolioSnapshot>) {
        val delta = compute24hDelta(latest, history) ?: return
        div(CssClass.Hero.DeltaRow) {
            val signum = delta.signum()
            val cls =
                when {
                    signum > 0 -> CssClass.Hero.DeltaUp
                    signum < 0 -> CssClass.Hero.DeltaDown
                    else -> CssClass.Hero.DeltaFlat
                }
            val sign = if (signum > 0) "+" else ""
            span(cls) { +"$sign${Formatter.formatPercent(delta)}%" }
            span(CssClass.Hero.DeltaWindow) { +ViewText.DELTA_WINDOW_24H }
        }
    }

    private fun DIV.renderDrawdown(drawdown: BigDecimal) {
        val cls =
            if (drawdown.signum() > 0) {
                CssClass.Utility.TextDanger + CssClass.Hero.Drawdown
            } else {
                CssClass.Hero.Drawdown
            }
        span(cls) {
            +"${ViewText.DRAWDOWN_PREFIX}${Formatter.formatPercent(drawdown)}%"
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
                    if (
                        (targetPct - baseTargetPct).abs() >
                        BigDecimal.valueOf(PrecisionConstants.ALLOCATION_TOLERANCE_DELTA)
                    ) {
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
        val pct = percent.max(BigDecimal.ZERO).min(HUNDRED).setScale(2, RoundingMode.HALF_UP)
        div(CssClass.Hero.TileBarRow) {
            div(CssClass.Hero.TileBarTrack) {
                div(CssClass.Hero.TileBarFill) {
                    style = "width: $pct%; background-color: $color;"
                }
            }
            span(CssClass.Hero.TileMeta) { +"${Formatter.formatPercent(percent)}%" }
        }
    }

    companion object {
        private const val SECONDS_PER_DAY = 86_400L
        private const val SPARK_WIDTH = 300.0
        private const val SPARK_HEIGHT = 80.0
        private const val SPARK_PAD = 4.0

        /**
         * 24h percentage change vs the most recent snapshot at least 24h older.
         * Returns null when fewer than two points exist, no ≥24h baseline is available,
         * or the baseline value is zero — never invents a shorter window labeled "24H".
         */
        internal fun compute24hDelta(latest: PortfolioSnapshot, history: List<PortfolioSnapshot>): BigDecimal? {
            if (history.size < 2) return null
            val cutoff = latest.timestamp.minusSeconds(SECONDS_PER_DAY)
            val past = history.firstOrNull { it.timestamp <= cutoff } ?: return null
            val base = past.totalValueUSD
            if (base.signum() == 0) return null
            return (latest.totalValueUSD - base)
                .divide(base, 6, RoundingMode.HALF_UP)
                .multiply(HUNDRED)
        }

        /** Inline SVG sparkline of total portfolio value; empty when too few points. */
        internal fun sparklineSvg(history: List<PortfolioSnapshot>): String {
            if (history.size < 2) return ""
            val values = history.asReversed().map { it.totalValueUSD.toDouble() }
            val min = values.min()
            val max = values.max()
            val range = max - min
            val lastIndex = values.size - 1
            val usableHeight = SPARK_HEIGHT - SPARK_PAD * 2
            val coords =
                values.mapIndexed { i, v ->
                    val x = i.toDouble() / lastIndex * SPARK_WIDTH
                    val y =
                        if (range == 0.0) {
                            SPARK_HEIGHT / 2
                        } else {
                            SPARK_HEIGHT - SPARK_PAD - (v - min) / range * usableHeight
                        }
                    "${fmt(x)},${fmt(y)}"
                }
            val line = coords.joinToString(" ")
            val area = "M0,$SPARK_HEIGHT L${coords.joinToString(" L")} L$SPARK_WIDTH,$SPARK_HEIGHT Z"
            // Unique gradient id per sparkline so HTMX swaps / future reuse cannot collide.
            val gradId = "hero-spark-grad-${history.first().timestamp.toEpochMilli()}"
            return buildString {
                append("<svg viewBox=\"0 0 ${fmt(SPARK_WIDTH)} ${fmt(SPARK_HEIGHT)}\" preserveAspectRatio=\"none\">")
                append("<defs><linearGradient id=\"$gradId\" x1=\"0\" y1=\"0\" x2=\"0\" y2=\"1\">")
                append("<stop offset=\"0%\" stop-color=\"${ChartProps.COLOR_BLUE}\" stop-opacity=\"0.55\"/>")
                append("<stop offset=\"100%\" stop-color=\"${ChartProps.COLOR_BLUE}\" stop-opacity=\"0\"/>")
                append("</linearGradient></defs>")
                append("<path d=\"$area\" fill=\"url(#$gradId)\"/>")
                append(
                    "<polyline points=\"$line\" fill=\"none\" stroke=\"${ChartProps.COLOR_BLUE}\" " +
                        "stroke-width=\"2.5\" stroke-linejoin=\"round\" stroke-linecap=\"round\"/>",
                )
                append("</svg>")
            }
        }

        private fun fmt(v: Double): String = ((v * 100).toLong() / 100.0).toString()
    }
}
