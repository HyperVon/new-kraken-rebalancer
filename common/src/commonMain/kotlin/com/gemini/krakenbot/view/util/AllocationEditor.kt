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
    private val syncColorJs =
        "this.closest('${CssClass.Form.AllocationEditRow.querySelector}').querySelector(" +
            "'${CssClass.Form.AllocationColorInput.querySelector}').value = this.value"
    private val removeRowJs =
        "this.closest('${CssClass.Form.AllocationEditRow.querySelector}').remove(); updateAllocationTotal()"
    private const val UPDATE_TOTAL_JS = "updateAllocationTotal()"

    /**
     * Renders one allocation-edit row.
     *
     * The output is injected as raw HTML on both sides (kotlinx.html `unsafe {}` on the server,
     * `innerHTML` on the client), so every interpolated value is HTML-escaped here rather than
     * relying on callers to sanitize first. Current callers already restrict their inputs
     * (uppercase-alphanumeric symbols, normalized 6-digit hex colors, numeric targets), so the
     * escaping is a no-op for them but keeps the template safe against future less-trusted callers.
     */
    fun editRow(symbol: String, color: String, targetPercent: String): String =
        """
        <div class="${CssClass.Form.AllocationEditRow}">
            <div class="${CssClass.Form.AllocationEditSymbol}">${escapeHtml(symbol)}</div>
            <input type="hidden" name="${FormFields.SYMBOLS}" value="${escapeHtml(symbol)}">
            <input type="hidden" name="${FormFields.COLORS}" class="${CssClass.Form.AllocationColorInput}" value="${escapeHtml(
            color,
        )}">
            <label>
                <input type="color" class="${CssClass.Form.AllocationColorSwatch}" value="${escapeHtml(
            color,
        )}" oninput="$syncColorJs">
            </label>
            <div class="${CssClass.Form.AllocationEditInputWrapper}">
                <input class="${CssClass.Form.InputGlass}" type="number" name="${FormFields.TARGETS}"
                  step="${PrecisionConstants.ALLOCATION_STEP_PERCENT}"
                  min="${PrecisionConstants.ALLOCATION_MIN_PERCENT}"
                  max="${PrecisionConstants.TOTAL_ALLOCATION_PERCENTAGE}"
                  value="${escapeHtml(targetPercent)}" oninput="$UPDATE_TOTAL_JS">
                <span class="${CssClass.Form.PercentSuffix}">%</span>
            </div>
            <button class="${CssClass.Button.DangerGhost}" type="button" onclick="$removeRowJs">${ViewText.REMOVE}</button>
        </div>
        """.trimIndent()

    private fun escapeHtml(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&#39;")
}
