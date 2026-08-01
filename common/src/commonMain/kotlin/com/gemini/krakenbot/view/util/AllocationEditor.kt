package com.gemini.krakenbot.view.util

import com.gemini.krakenbot.util.PrecisionConstants

/**
 * Single source of truth for the interactive allocation-edit row.
 *
 * The settings form renders this row twice — server-side for persisted allocations
 * (SettingsFormComponent, kotlinx.html) and client-side for newly added assets
 * (Settings.kt `addAssetRow`) — so the markup and the inline behaviour (color-swatch→hidden
 * input sync, target-input total refresh, row removal) must never drift apart. Both sides build
 * through this one function instead of duplicating the row structure.
 */
object AllocationEditor {
    private val editRow = CssClass.Form.AllocationEditRow.toString()
    private val editSymbol = CssClass.Form.AllocationEditSymbol.toString()
    private val colorInput = CssClass.Form.AllocationColorInput.toString()
    private val colorSwatch = CssClass.Form.AllocationColorSwatch.toString()
    private val editInputWrapper = CssClass.Form.AllocationEditInputWrapper.toString()
    private val inputGlass = CssClass.Form.InputGlass.toString()
    private val percentSuffix = CssClass.Form.PercentSuffix.toString()
    private val dangerButton = CssClass.Button.Danger.toString()

    private val syncColorJs =
        "this.closest('.$editRow').querySelector('.$colorInput').value = this.value"
    private val removeRowJs = "this.closest('.$editRow').remove(); updateAllocationTotal()"
    private const val UPDATE_TOTAL_JS = "updateAllocationTotal()"

    /** Renders one allocation-edit row. `targetPercent` and `color` are attributed by the caller. */
    fun editRow(symbol: String, color: String, targetPercent: String): String =
        """
        <div class="$editRow">
            <div class="$editSymbol">$symbol</div>
            <input type="hidden" name="${FormFields.SYMBOLS}" value="$symbol">
            <input type="hidden" name="${FormFields.COLORS}" class="$colorInput" value="$color">
            <label>
                <input type="color" class="$colorSwatch" value="$color" oninput="$syncColorJs">
            </label>
            <div class="$editInputWrapper">
                <input class="$inputGlass" type="number" name="${FormFields.TARGETS}"
                  step="${PrecisionConstants.ALLOCATION_STEP_PERCENT}"
                  min="${PrecisionConstants.ALLOCATION_MIN_PERCENT}"
                  max="${PrecisionConstants.TOTAL_ALLOCATION_PERCENTAGE}"
                  value="$targetPercent" oninput="$UPDATE_TOTAL_JS">
                <span class="$percentSuffix">%</span>
            </div>
            <button class="$dangerButton" type="button" onclick="$removeRowJs">${ViewText.REMOVE}</button>
        </div>
        """.trimIndent()
}
