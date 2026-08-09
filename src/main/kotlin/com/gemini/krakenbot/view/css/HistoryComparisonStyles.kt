package com.gemini.krakenbot.view.css

import com.gemini.krakenbot.view.util.CssClass
import kotlinx.css.CssBuilder
import kotlinx.css.Display
import kotlinx.css.FontWeight
import kotlinx.css.GridTemplateColumns
import kotlinx.css.Padding
import kotlinx.css.TextAlign
import kotlinx.css.WhiteSpace
import kotlinx.css.background
import kotlinx.css.borderColor
import kotlinx.css.borderRadius
import kotlinx.css.color
import kotlinx.css.display
import kotlinx.css.fontFamily
import kotlinx.css.fontSize
import kotlinx.css.fontWeight
import kotlinx.css.gridTemplateColumns
import kotlinx.css.padding
import kotlinx.css.rem
import kotlinx.css.textAlign
import kotlinx.css.whiteSpace

object HistoryComparisonStyles {
    fun CssBuilder.applyHistoryComparisonStyles() {
        ".${CssClass.History.ComparisonHeader}" {
            display = Display.grid
            gridTemplateColumns = GridTemplateColumns("minmax(0, 1fr) auto auto")
        }

        ".${CssClass.History.ComparisonDelta}" {
            fontSize = 0.8125.rem
            fontWeight = FontWeight.w700
            fontFamily = CssTheme.fontMono
            padding = Padding(0.25.rem, 0.625.rem)
            borderRadius = CssTheme.radiusSm
            background = CssTheme.colorGlass.value
            solidBorder(CssTheme.colorGlassBorder)
            whiteSpace = WhiteSpace.nowrap
        }

        ".${CssClass.History.ComparisonDelta}.${CssClass.Utility.Positive}" {
            color = CssTheme.colorSuccess
            borderColor = CssTheme.colorSuccessBorder
        }

        ".${CssClass.History.ComparisonDelta}.${CssClass.Utility.Negative}" {
            color = CssTheme.colorDanger
            borderColor = CssTheme.colorDangerBorder
        }

        ".${CssClass.History.ComparisonDelta}.${CssClass.Utility.Neutral}" {
            color = CssTheme.colorTextMuted
        }

        ".${CssClass.History.ComparisonUnavailable}" {
            display = Display.none
            padding = Padding(1.5.rem)
            textAlign = TextAlign.center
            color = CssTheme.colorTextMuted
            fontSize = 0.875.rem
        }

        ".${CssClass.History.ComparisonUnavailable}.${CssClass.Utility.Visible}" {
            display = Display.block
        }

        ".${CssClass.History.ComparisonChartArea}" {
            display = Display.block
        }

        ".${CssClass.History.ComparisonChartArea}.${CssClass.Utility.Hidden}" {
            display = Display.none
        }

        ".${CssClass.History.ComparisonConfidenceBadge}" {
            display = Display.none
            fontSize = 0.75.rem
            color = CssTheme.colorWarning
            padding = Padding(0.25.rem, 0.75.rem)
        }

        ".${CssClass.History.ComparisonConfidenceBadge}.${CssClass.Utility.Visible}" {
            display = Display.inline
        }
    }
}
