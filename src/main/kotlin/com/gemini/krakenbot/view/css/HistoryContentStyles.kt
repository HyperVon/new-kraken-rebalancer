package com.gemini.krakenbot.view.css

import com.gemini.krakenbot.view.util.CssClass
import kotlinx.css.Align
import kotlinx.css.CssBuilder
import kotlinx.css.Display
import kotlinx.css.FontWeight
import kotlinx.css.GridTemplateColumns
import kotlinx.css.Padding
import kotlinx.css.TextAlign
import kotlinx.css.alignItems
import kotlinx.css.background
import kotlinx.css.borderRadius
import kotlinx.css.color
import kotlinx.css.display
import kotlinx.css.fontFamily
import kotlinx.css.fontSize
import kotlinx.css.fontWeight
import kotlinx.css.gap
import kotlinx.css.gridTemplateColumns
import kotlinx.css.height
import kotlinx.css.marginBottom
import kotlinx.css.padding
import kotlinx.css.pct
import kotlinx.css.px
import kotlinx.css.rem
import kotlinx.css.textAlign
import kotlinx.css.width

object HistoryContentStyles {
    fun CssBuilder.applyHistoryContentStyles() {
        ".${CssClass.History.StatsGrid}" {
            display = Display.grid
            gridTemplateColumns = GridTemplateColumns("1fr")
            gap = 0.75.rem
            marginBottom = 1.rem
            alignItems = Align.start
        }

        ".${CssClass.History.TradeLogHeader}" {
            display = Display.flex
            justifyContentRaw("space-between")
            alignItems = Align.center
            marginBottom = 1.rem
        }

        ".${CssClass.History.TitleNoMargin}" {
            marginBottom = 0.px
        }

        ".${CssClass.History.MutedSmallText}" {
            fontSize = 0.875.rem
            color = CssTheme.colorTextMuted
        }

        ".${CssClass.History.EmptyTableCell}" {
            textAlign = TextAlign.center
            color = CssTheme.colorTextMuted
            padding = Padding(2.rem)
        }

        ".${CssClass.History.SyncBanner}" {
            display = Display.none
            marginBottom = 1.5.rem
            padding = Padding(1.5.rem)
        }

        ".${CssClass.History.SyncHeader}" {
            display = Display.flex
            alignItems = Align.center
            justifyContentRaw("space-between")
            marginBottom = 0.75.rem
        }

        ".${CssClass.History.SyncTitle}" {
            fontWeight = FontWeight.w600
            color = CssTheme.colorTextPrimary
            display = Display.flex
            alignItems = Align.center
            gap = 0.5.rem
        }

        ".${CssClass.History.SyncSpinner}" {
            width = 1.rem
            height = 1.rem
            solidBorder(CssTheme.colorKrakenBlue, 2.px)
            borderTopColorRaw("transparent")
            borderRadius = CssTheme.radiusPill
            animationRaw("spin 1s linear infinite")
        }

        ".${CssClass.History.SyncText}" {
            fontFamily = CssTheme.fontMono
            fontSize = 0.875.rem
            color = CssTheme.colorTextMuted
        }

        ".${CssClass.History.ProgressTrack}" {
            width = 100.pct
            height = 0.5.rem
            background = CssTheme.colorWhiteMuted.value
            borderRadius = CssTheme.radiusPill
            overflowRaw("hidden")
        }

        ".${CssClass.History.ProgressBar}" {
            width = 0.pct
            height = 100.pct
            background = CssTheme.colorKrakenBlue.value
            transitionRaw("width 0.3s ease")
            borderRadius = CssTheme.radiusPill
        }
    }
}
