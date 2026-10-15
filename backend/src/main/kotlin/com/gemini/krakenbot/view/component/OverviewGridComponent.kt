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
import java.time.Duration
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

class OverviewGridComponent {
    context(div: DIV)
    fun render(latest: PortfolioSnapshot, recentSnapshots: List<PortfolioSnapshot> = listOf(latest)) {
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
            renderHeroCard(latest, recentSnapshots)
            div(CssClass.Layout.HeroSide) {
                renderCashTile(latest, usdAsset, usdValue)
                renderCryptoTile(cryptoValue, cryptoPercent, cryptoTargetPercent, cryptoCount)
            }
        }
    }

    private fun DIV.renderHeroCard(latest: PortfolioSnapshot, recentSnapshots: List<PortfolioSnapshot>) {
        div(CssClass.Hero.Card) {
            div(CssClass.Hero.CardText) {
                div(CssClass.Hero.Label) {
                    icon(Icons.TREND_UP)
                    +ViewText.TOTAL_PORTFOLIO
                }
                div(CssClass.Hero.Value) { +"$${Formatter.formatCurrency(latest.totalValueUSD)}" }
            }
            renderTrend(recentSnapshots)
        }
    }

    private fun DIV.renderTrend(recentSnapshots: List<PortfolioSnapshot>) {
        val samples = recentSnapshots.takeLast(MAX_TREND_SAMPLES)
        div(CssClass.Hero.Trend) {
            div(CssClass.Hero.TrendHeader) {
                span(CssClass.Hero.TrendTitle) { +ViewText.DASHBOARD_TREND_TITLE }
                span(CssClass.Hero.TrendSummary) {
                    val suffix =
                        if (samples.size == 1) {
                            ViewText.DASHBOARD_TREND_CYCLE_SINGULAR
                        } else {
                            ViewText.DASHBOARD_TREND_CYCLE_PLURAL
                        }
                    +"${samples.size}$suffix"
                }
            }
            if (samples.size < 2) {
                div(CssClass.Hero.TrendEmpty) { +ViewText.DASHBOARD_TREND_COLLECTING }
            } else {
                div(CssClass.Hero.TrendChart) {
                    unsafe { +renderTrendSvg(samples) }
                }
            }
        }
    }

    private fun renderTrendSvg(samples: List<PortfolioSnapshot>): String {
        val values = samples.map { it.totalValueUSD }
        val minimum = values.minOrNull() ?: BigDecimal.ZERO
        val maximum = values.maxOrNull() ?: BigDecimal.ZERO
        val rawRange = maximum.subtract(minimum)
        val padding =
            if (rawRange.signum() == 0) {
                minimum.abs().multiply(FLAT_LINE_PADDING_PERCENT).max(MINIMUM_TREND_PADDING)
            } else {
                rawRange.multiply(TREND_PADDING_PERCENT)
            }
        val yMinimum = minimum.subtract(padding)
        val yMaximum = maximum.add(padding)
        val yRange = yMaximum.subtract(yMinimum)
        val points =
            values.mapIndexed { index, value ->
                val x =
                    PLOT_LEFT + index.toDouble() / (values.lastIndex) * (PLOT_RIGHT - PLOT_LEFT)
                val valueRatio =
                    value.subtract(yMinimum).divide(yRange, COORDINATE_SCALE, RoundingMode.HALF_UP).toDouble()
                val y = PLOT_BOTTOM - valueRatio * (PLOT_BOTTOM - PLOT_TOP)
                "${formatCoordinate(x)},${formatCoordinate(y)}"
            }
        val gridRows =
            listOf(yMaximum, yMinimum.add(yMaximum).divide(TWO, COORDINATE_SCALE, RoundingMode.HALF_UP), yMinimum)
                .mapIndexed { index, value ->
                    val y = PLOT_TOP + index * (PLOT_BOTTOM - PLOT_TOP) / 2
                    """
                    <line class="${CssClass.Hero.TrendGrid.value}" x1="${formatCoordinate(
                        PLOT_LEFT,
                    )}" y1="${formatCoordinate(y)}" x2="${formatCoordinate(PLOT_RIGHT)}" y2="${formatCoordinate(y)}"/>
                    <text class="${CssClass.Hero.TrendAxisLabel.value}" x="${formatCoordinate(
                        PLOT_LEFT - 8,
                    )}" y="${formatCoordinate(
                        y + AXIS_LABEL_BASELINE,
                    )}" text-anchor="end">${formatAxisCurrency(value)}</text>
                    """.trimIndent()
                }.joinToString("")
        val formatter =
            if (Duration.between(samples.first().timestamp, samples.last().timestamp) < DETAILED_TIME_WINDOW) {
                TREND_TIME_FORMATTER_WITH_SECONDS
            } else {
                TREND_TIME_FORMATTER
            }
        val startTime = formatter.format(samples.first().timestamp)
        val endTime = formatter.format(samples.last().timestamp)
        val lastPoint = points.last().split(',')
        return """
            <svg class="${CssClass.Hero.TrendSvg.value}" viewBox="0 0 ${formatCoordinate(
            SVG_WIDTH,
        )} ${formatCoordinate(
            SVG_HEIGHT,
        )}" preserveAspectRatio="none">
              $gridRows
              <polyline class="${CssClass.Hero.TrendLine.value}" points="${points.joinToString(" ")}"/>
              <circle class="${CssClass.Hero.TrendPoint.value}" cx="${lastPoint[0]}" cy="${lastPoint[1]}" r="5"/>
              <text class="${CssClass.Hero.TrendAxisLabel.value}" x="${formatCoordinate(
            PLOT_LEFT,
        )}" y="${formatCoordinate(X_AXIS_LABEL_Y)}" text-anchor="start">$startTime</text>
              <text class="${CssClass.Hero.TrendAxisLabel.value}" x="${formatCoordinate(
            PLOT_RIGHT,
        )}" y="${formatCoordinate(X_AXIS_LABEL_Y)}" text-anchor="end">$endTime</text>
            </svg>
        """.trimIndent()
    }

    private fun formatAxisCurrency(value: BigDecimal): String = when {
        value.abs() >= MILLION -> "$${value.divide(
            MILLION,
            1,
            RoundingMode.HALF_UP,
        ).stripTrailingZeros().toPlainString()}m"

        value.abs() >= THOUSAND -> "$${value.divide(
            THOUSAND,
            1,
            RoundingMode.HALF_UP,
        ).stripTrailingZeros().toPlainString()}k"

        else -> "$${value.setScale(0, RoundingMode.HALF_UP).toPlainString()}"
    }

    private fun formatCoordinate(value: Double): String = String.format(Locale.US, "%.2f", value)

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

private const val MAX_TREND_SAMPLES = 24
private const val SVG_WIDTH = 520.0
private const val SVG_HEIGHT = 150.0
private const val PLOT_LEFT = 66.0
private const val PLOT_RIGHT = 514.0
private const val PLOT_TOP = 10.0
private const val PLOT_BOTTOM = 112.0
private const val X_AXIS_LABEL_Y = 142.0
private const val AXIS_LABEL_BASELINE = 4.0
private const val COORDINATE_SCALE = 12
private val TWO = BigDecimal("2")
private val THOUSAND = BigDecimal("1000")
private val MILLION = BigDecimal("1000000")
private val TREND_PADDING_PERCENT = BigDecimal("0.08")
private val FLAT_LINE_PADDING_PERCENT = BigDecimal("0.005")
private val MINIMUM_TREND_PADDING = BigDecimal("1.00")
private val DETAILED_TIME_WINDOW = Duration.ofMinutes(5)
private val TREND_TIME_FORMATTER = DateTimeFormatter.ofPattern("h:mm a", Locale.US).withZone(ZoneId.systemDefault())
private val TREND_TIME_FORMATTER_WITH_SECONDS =
    DateTimeFormatter.ofPattern("h:mm:ss a", Locale.US).withZone(ZoneId.systemDefault())
