package com.gemini.krakenbot.view.css

import kotlinx.css.CssBuilder

/**
 * Typed `put(...)` escape-hatch wrappers for raw CSS properties.
 *
 * kotlinx.css only types a subset of properties; everything else is set via the
 * generic `put(property, value)` escape hatch. A typo in the property *name*
 * there silently emits broken CSS with no compile error. These extensions move
 * the property name into the Kotlin function name so a misspelling fails at
 * compile time. Values stay inline per the design-system convention.
 */
fun CssBuilder.boxShadowRaw(value: String) = put("box-shadow", value)
fun CssBuilder.justifyContentRaw(value: String) = put("justify-content", value)
fun CssBuilder.transitionRaw(value: String) = put("transition", value)
fun CssBuilder.lineHeightRaw(value: String) = put("line-height", value)
fun CssBuilder.letterSpacingRaw(value: String) = put("letter-spacing", value)
fun CssBuilder.flexWrapRaw(value: String) = put("flex-wrap", value)
fun CssBuilder.textTransformRaw(value: String) = put("text-transform", value)
fun CssBuilder.outlineRaw(value: String) = put("outline", value)
fun CssBuilder.fontVariantNumericRaw(value: String) = put("font-variant-numeric", value)
fun CssBuilder.appearanceRaw(value: String) = put("appearance", value)
fun CssBuilder.webkitAppearanceRaw(value: String) = put("-webkit-appearance", value)
fun CssBuilder.mozAppearanceRaw(value: String) = put("-moz-appearance", value)
fun CssBuilder.whiteSpaceRaw(value: String) = put("white-space", value)
fun CssBuilder.userSelectRaw(value: String) = put("user-select", value)
fun CssBuilder.minHeightRaw(value: String) = put("min-height", value)
fun CssBuilder.marginLeftRaw(value: String) = put("margin-left", value)
fun CssBuilder.animationRaw(value: String) = put("animation", value)
fun CssBuilder.filterRaw(value: String) = put("filter", value)
fun CssBuilder.borderTopColorRaw(value: String) = put("border-top-color", value)
fun CssBuilder.widthRaw(value: String) = put("width", value)
fun CssBuilder.verticalAlignRaw(value: String) = put("vertical-align", value)
fun CssBuilder.transitionDurationRaw(value: String) = put("transition-duration", value)
fun CssBuilder.transformRaw(value: String) = put("transform", value)
fun CssBuilder.scrollBehaviorRaw(value: String) = put("scroll-behavior", value)
fun CssBuilder.pointerEventsRaw(value: String) = put("pointer-events", value)
fun CssBuilder.overflowRaw(value: String) = put("overflow", value)
fun CssBuilder.minWidthRaw(value: String) = put("min-width", value)
fun CssBuilder.maxWidthRaw(value: String) = put("max-width", value)
fun CssBuilder.marginRightRaw(value: String) = put("margin-right", value)
fun CssBuilder.flexShrinkRaw(value: String) = put("flex-shrink", value)
fun CssBuilder.contentRaw(value: String) = put("content", value)
fun CssBuilder.clipRaw(value: String) = put("clip", value)
fun CssBuilder.clipPathRaw(value: String) = put("clip-path", value)
fun CssBuilder.boxSizingRaw(value: String) = put("box-sizing", value)
fun CssBuilder.borderWidthRaw(value: String) = put("border-width", value)
fun CssBuilder.backgroundRepeatRaw(value: String) = put("background-repeat", value)
fun CssBuilder.backgroundPositionRaw(value: String) = put("background-position", value)
fun CssBuilder.backgroundImageRaw(value: String) = put("background-image", value)
fun CssBuilder.backgroundColorRaw(value: String) = put("background-color", value)
fun CssBuilder.backgroundBlendModeRaw(value: String) = put("background-blend-mode", value)
fun CssBuilder.backdropFilterRaw(value: String) = put("backdrop-filter", value)
fun CssBuilder.animationIterationCountRaw(value: String) = put("animation-iteration-count", value)
fun CssBuilder.animationDurationRaw(value: String) = put("animation-duration", value)
fun CssBuilder.accentColorRaw(value: String) = put("accent-color", value)
fun CssBuilder.webkitFontSmoothingRaw(value: String) = put("-webkit-font-smoothing", value)
fun CssBuilder.webkitBackdropFilterRaw(value: String) = put("-webkit-backdrop-filter", value)
fun CssBuilder.mozOsxFontSmoothingRaw(value: String) = put("-moz-osx-font-smoothing", value)
