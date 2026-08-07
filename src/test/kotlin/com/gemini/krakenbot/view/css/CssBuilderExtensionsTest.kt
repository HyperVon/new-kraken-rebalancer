package com.gemini.krakenbot.view.css

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.string.shouldContain
import kotlinx.css.CssBuilder

class CssBuilderExtensionsTest : StringSpec() {
    init {
        "raw extension helpers emit the correct CSS property name" {
            val css =
                CssBuilder()
                    .apply {
                        boxShadowRaw("0 1px 2px black")
                        justifyContentRaw("space-between")
                        transitionRaw("all 200ms")
                        lineHeightRaw("1.4")
                        letterSpacingRaw("0.01em")
                        flexWrapRaw("wrap")
                        textTransformRaw("uppercase")
                        outlineRaw("none")
                        fontVariantNumericRaw("tabular-nums")
                        appearanceRaw("none")
                        webkitAppearanceRaw("none")
                        mozAppearanceRaw("none")
                        whiteSpaceRaw("nowrap")
                        userSelectRaw("none")
                        minHeightRaw("40px")
                        animationRaw("spin 1s linear")
                        filterRaw("blur(2px)")
                        clipPathRaw("inset(0)")
                        boxSizingRaw("border-box")
                        accentColorRaw("#3b82f6")
                        webkitFontSmoothingRaw("antialiased")
                        webkitBackdropFilterRaw("blur(4px)")
                        mozOsxFontSmoothingRaw("grayscale")
                    }
                    .toString()

            css shouldContain "box-shadow: 0 1px 2px black"
            css shouldContain "justify-content: space-between"
            css shouldContain "transition: all 200ms"
            css shouldContain "line-height: 1.4"
            css shouldContain "letter-spacing: 0.01em"
            css shouldContain "flex-wrap: wrap"
            css shouldContain "text-transform: uppercase"
            css shouldContain "outline: none"
            css shouldContain "font-variant-numeric: tabular-nums"
            css shouldContain "appearance: none"
            css shouldContain "-webkit-appearance: none"
            css shouldContain "-moz-appearance: none"
            css shouldContain "white-space: nowrap"
            css shouldContain "user-select: none"
            css shouldContain "min-height: 40px"
            css shouldContain "animation: spin 1s linear"
            css shouldContain "filter: blur(2px)"
            css shouldContain "clip-path: inset(0)"
            css shouldContain "box-sizing: border-box"
            css shouldContain "accent-color: #3b82f6"
            css shouldContain "-webkit-font-smoothing: antialiased"
            css shouldContain "-webkit-backdrop-filter: blur(4px)"
            css shouldContain "-moz-osx-font-smoothing: grayscale"
        }
    }
}
