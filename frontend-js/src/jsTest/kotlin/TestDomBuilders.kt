package com.gemini.krakenbot.frontend

import com.gemini.krakenbot.model.Asset
import com.gemini.krakenbot.view.util.ViewText

object TestDomBuilders {
    fun settingsDom(): String =
        """
        <span id="mode-plate" class="mode-plate mode-dry-run" title="${ViewText.MODE_DRY_RUN_TITLE}">
          <span class="mode-plate-dot"></span>
          <span id="mode-plate-label">${ViewText.MODE_DRY_RUN}</span>
        </span>
        <input type="checkbox" name="simulation">
        <input type="checkbox" name="dryRun" checked>
        <span id="total-allocated-display"></span>
        <button id="save-button"></button>
        """.trimIndent()

    fun assetEditDom(newSymbol: String = Asset.BTC): String =
        """
        <input type="text" id="new-symbol-input" value="$newSymbol">
        <div id="allocations-container"></div>
        """.trimIndent()

    // StatusCard.Live is "status-badge live", so Badge ("status-badge") queries match production chips.
    fun dataAgeDom(epoch: String = ""): String =
        """
        <span class="data-age-value"></span>
        <span class="data-age-time" data-epoch="$epoch"></span>
        <span class="status-badge live"></span>
        """.trimIndent()

    fun sortableTableDom(): String =
        """
        <table>
            <thead>
                <tr>
                    <th class="sortable">Col0</th>
                    <th class="sortable">Col1</th>
                </tr>
            </thead>
            <tbody>
                <tr class="hoverable">
                    <td data-sort-value="10">A</td>
                    <td data-sort-value="20">B</td>
                </tr>
                <tr class="hoverable">
                    <td data-sort-value="5">C</td>
                    <td data-sort-value="15">D</td>
                </tr>
            </tbody>
        </table>
        """.trimIndent()

    fun emptyTableDom(): String =
        """
        <table><tbody></tbody></table>
        """.trimIndent()

    fun settingsFormDom(): String =
        """
        <div id="allocations-container"></div>
        ${settingsDom()}
        """.trimIndent()
}
