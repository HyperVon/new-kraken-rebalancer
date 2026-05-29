package com.gemini.krakenbot.view

import com.gemini.krakenbot.config.AppConfig
import com.gemini.krakenbot.model.PortfolioSnapshot
import kotlinx.html.*
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs

class DashboardView {

    private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault())

    fun HTML.renderDashboardShell() {
        head {
            meta(charset = "utf-8")
            meta(name = "viewport", content = "width=device-width, initial-scale=1.0")
            title("Kraken Rebalancer")
            link(rel = "stylesheet", href = "/static/style.css")
            script(src = "https://unpkg.com/htmx.org@2.0.4") {}
            script(src = "https://unpkg.com/htmx-ext-sse@2.2.2/sse.js") {}
        }
        body {
            div("container") {
                div {
                    attributes["hx-ext"] = "sse"
                    attributes["sse-connect"] = "/api/status/stream"

                    div {
                        attributes["hx-get"] = "/fragments/dashboard"
                        attributes["hx-trigger"] = "load, sse:message"

                        div("spinner-container") {
                            div("spinner") {}
                            p { +"Connecting to KrakenRebalancer..." }
                        }
                    }
                }
            }
            script {
                unsafe {
                    +"""
                    var currentSortCol = 5;    // Defaults to Dev % column (index 5)
                    var currentSortDir = 'asc'; // Defaults to 'asc'

                    function updateAge() {
                        var ageEl = document.querySelector('.data-age-value');
                        var timeEl = document.querySelector('.data-age-time');
                        if (ageEl && timeEl) {
                            var epochStr = timeEl.getAttribute('data-epoch');
                            if (epochStr) {
                                var epoch = parseInt(epochStr, 10);
                                var now = Date.now();
                                var diff = Math.floor(Math.max(0, now - epoch) / 1000);
                                ageEl.textContent = diff + 's ago';
                                var delayedClass = diff > 90 ? 'data-age-value stale' : 'data-age-value';
                                if (ageEl.className !== delayedClass) {
                                    ageEl.className = delayedClass;
                                }
                                
                                // Localize the time display to expected HH:mm:ss local format
                                var date = new Date(epoch);
                                var hh = ('0' + date.getHours()).slice(-2);
                                var mm = ('0' + date.getMinutes()).slice(-2);
                                var ss = ('0' + date.getSeconds()).slice(-2);
                                var localTimeStr = hh + ':' + mm + ':' + ss;
                                if (timeEl.textContent.trim() !== localTimeStr) {
                                    timeEl.textContent = localTimeStr;
                                }

                                var badgeEl = document.querySelector('.status-badge');
                                if (badgeEl) {
                                    var badgeClass = diff > 90 ? 'status-badge delayed' : 'status-badge live';
                                    var badgeText = diff > 90 ? 'DELAYED' : 'LIVE';
                                    if (badgeEl.className !== badgeClass) {
                                        badgeEl.className = badgeClass;
                                        badgeEl.textContent = badgeText;
                                    }
                                }
                            }
                        }
                    }

                    function reapplySort() {
                        var headers = document.querySelectorAll('th.sortable');
                        if (headers.length > currentSortCol) {
                            var header = headers[currentSortCol];
                            sortTable(header, currentSortCol, currentSortDir);
                        }
                    }

                    var ageTimer = setInterval(updateAge, 1000);
                    document.addEventListener('DOMContentLoaded', function() {
                        updateAge();
                        reapplySort();
                    });
                    document.addEventListener('htmx:afterSwap', function() {
                        updateAge();
                        reapplySort();
                    });

                    function sortTable(header, colIdx, forceDir) {
                        var table = header.closest('table');
                        var tbody = table.querySelector('tbody');
                        var rows = Array.from(tbody.querySelectorAll('tr.hoverable'));
                        var isAsc = header.classList.contains('asc');
                        var sortAsc = (forceDir !== undefined) ? (forceDir === 'asc') : !isAsc;
                        var key = colIdx === 0 ? 'string' : 'float';

                        rows.sort(function(a, b) {
                            var aText = a.children[colIdx].textContent.trim().replace(/[$,%]/g, '');
                            var bText = b.children[colIdx].textContent.trim().replace(/[$,%]/g, '');
                            if (key === 'float') {
                                var aVal = parseFloat(aText) || 0;
                                var bVal = parseFloat(bText) || 0;
                                return sortAsc ? aVal - bVal : bVal - aVal;
                            } else {
                                return sortAsc
                                    ? aText.localeCompare(bText)
                                    : bText.localeCompare(aText);
                            }
                        });

                        table.querySelectorAll('th.sortable').forEach(function(th) {
                            th.classList.remove('asc', 'desc');
                        });
                        header.classList.add(sortAsc ? 'asc' : 'desc');

                        rows.forEach(function(row) { tbody.append(row); });

                        // Keep track of the user's latest sort criteria so swaps can re-apply them stably
                        currentSortCol = colIdx;
                        currentSortDir = sortAsc ? 'asc' : 'desc';
                    }
                    """
                }
            }
        }
    }

    fun HTML.renderSettingsPage(config: AppConfig, errorMessage: String?) {
        head {
            meta(charset = "utf-8")
            meta(name = "viewport", content = "width=device-width, initial-scale=1.0")
            title("Settings - Kraken Rebalancer")
            link(rel = "stylesheet", href = "/static/style.css")
            script(src = "https://unpkg.com/htmx.org@2.0.4") {}
        }
        body {
            renderSettingsForm(config, errorMessage)
        }
    }

    fun DIV.renderDashboardFragment(latest: PortfolioSnapshot, history: List<PortfolioSnapshot>) {
        val totalValue = latest.totalValueUSD
        val usdAsset = latest.assets["USD"]
        val usdValue = usdAsset?.valueUSD ?: BigDecimal.ZERO
        val cryptoValue = totalValue - usdValue

        val assetsList = latest.assets.values.filter { it.symbol != "USD" }
        val cryptoPercent = assetsList.sumOf { it.currentPercent.toDouble() }
        val cryptoTargetPercent = assetsList.sumOf { it.targetPercent.toDouble() }
        val cryptoCount = assetsList.size

        val timeSinceUpdate = 0L.coerceAtLeast(Instant.now().epochSecond - latest.timestamp.epochSecond)
        val isStale = timeSinceUpdate > 90

        header {
            div("header-title-section") {
                h1 { +"Kraken Rebalancer" }
                val badgeClass = if (isStale) "status-badge delayed" else "status-badge live"
                val badgeText = if (isStale) "DELAYED" else "LIVE"
                div(badgeClass) { +badgeText }
            }

            div("header-actions") {
                div("data-age-container") {
                    div("data-age-label") { +"Data Age" }
                    val ageClass = if (isStale) "data-age-value stale" else "data-age-value"
                    div(ageClass) { +"${timeSinceUpdate}s ago" }
                    div("data-age-time") {
                        attributes["data-epoch"] = latest.timestamp.toEpochMilli().toString()
                        +timeFormatter.format(latest.timestamp)
                    }
                }
                a(href = "/settings", classes = "btn btn-secondary") {
                    unsafe {
                        +"""<svg xmlns="http://www.w3.org/2000/svg" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M12.22 2h-.44a2 2 0 0 0-2 2v.18a2 2 0 0 1-1 1.73l-.43.25a2 2 0 0 1-2 0l-.15-.08a2 2 0 0 0-2.73.73l-.22.38a2 2 0 0 0 .73 2.73l.15.1a2 2 0 0 1 1 1.72v.51a2 2 0 0 1-1 1.74l-.15.09a2 2 0 0 0-.73 2.73l.22.38a2 2 0 0 0 2.73.73l.15-.08a2 2 0 0 1 2 0l.43.25a2 2 0 0 1 1 1.73V20a2 2 0 0 0 2 2h.44a2 2 0 0 0 2-2v-.18a2 2 0 0 1 1-1.73l.43-.25a2 2 0 0 1 2 0l.15.08a2 2 0 0 0 2.73-.73l.22-.39a2 2 0 0 0-.73-2.73l-.15-.08a2 2 0 0 1-1-1.74v-.5a2 2 0 0 1 1-1.74l.15-.1a2 2 0 0 0 .73-2.73l-.22-.38a2 2 0 0 0-2.73-.73l-.15.08a2 2 0 0 1-2 0l-.43-.25a2 2 0 0 1-1-1.73V4a2 2 0 0 0-2-2z"></path><circle cx="12" cy="12" r="3"></circle></svg>"""
                    }
                    span { +"Settings" }
                }
            }
        }

        div("overview-grid") {
            div("glass-panel status-card") {
                div("status-card-header") {
                    span("status-card-title") { +"Total Portfolio" }
                    div("status-card-icon") {
                        unsafe {
                            +"""<svg xmlns="http://www.w3.org/2000/svg" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="22 7 13.5 15.5 8.5 10.5 2 17"></polyline><polyline points="16 7 22 7 22 13"></polyline></svg>"""
                        }
                    }
                }
                div("status-card-value") { +"$${formatCurrency(totalValue)}" }
                div("status-card-sub") {
                    val drawdown = latest.drawdownPercent
                    val isDrawdown = drawdown.signum() > 0
                    val colorClass = if (isDrawdown) "text-danger" else ""
                    span(colorClass) {
                        +"Drawdown: ${formatPercent(drawdown)}%"
                    }
                }
            }

            div("glass-panel status-card success") {
                div("status-card-header") {
                    span("status-card-title") { +"Cash (USD)" }
                    div("status-card-icon") {
                        unsafe {
                            +"""<svg xmlns="http://www.w3.org/2000/svg" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M21 12V7H5a2 2 0 0 1 2-2h14V4a2 2 0 0 0-2-2H3a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h16a2 2 0 0 0 2-2v-5H7a2 2 0 0 1-2-2h16z"></path></svg>"""
                        }
                    }
                }
                div("status-card-value") { +"$${formatCurrency(usdValue)}" }
                div("status-card-sub") {
                    if (usdAsset != null) {
                        val currentPct = usdAsset.currentPercent
                        val targetPct = latest.effectiveUsdTargetPercent
                        val baseTargetPct = usdAsset.targetPercent
                        val dev = usdAsset.deviationPercent
                        val devClass = getDeviationClass(dev)
                        val devSign = getDeviationSign(dev)

                        span {
                            +"${formatPercent(currentPct)}% | Target: ${formatPercent(targetPct)}%"
                            if (abs(targetPct.toDouble() - baseTargetPct.toDouble()) > 0.01) {
                                +" (Base: ${formatPercent(baseTargetPct)}%)"
                            }
                            +" | "
                            span(devClass) {
                                +"Dev: $devSign${formatPercent(dev)}%"
                            }
                        }
                    } else {
                        +"No USD Data"
                    }
                }
            }

            div("glass-panel status-card") {
                div("status-card-header") {
                    span("status-card-title") { +"Crypto Assets" }
                    div("status-card-icon") {
                        unsafe {
                            +"""<svg xmlns="http://www.w3.org/2000/svg" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="8" cy="8" r="6"></circle><circle cx="18" cy="18" r="4"></circle><path d="M12 18a6 6 0 0 0-6-6"></path></svg>"""
                        }
                    }
                }
                div("status-card-value") { +"$${formatCurrency(cryptoValue)}" }
                div("status-card-sub") {
                    span {
                        +"${formatPercent(cryptoPercent)}% | Target: ${formatPercent(cryptoTargetPercent)}% | $cryptoCount Assets"
                    }
                }
            }
        }

        div("detail-grid") {
            div("glass-panel") {
                h2("glass-panel-title") {
                    unsafe {
                        +"""<svg xmlns="http://www.w3.org/2000/svg" width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="10"></circle><path d="M12 2v20"></path><path d="M17 5H9.5a3.5 3.5 0 0 0 0 7h5a3.5 3.5 0 0 1 0 7H6"></path></svg>"""
                    }
                    +"Portfolio Allocation (Top Assets)"
                }

                div("allocation-chart-container") {
                    val sorted = latest.assets.values.sortedByDescending { it.valueUSD }
                    val topAssets = sorted.take(15)
                    val maxVal = topAssets.firstOrNull()?.valueUSD?.toDouble() ?: 1.0

                    topAssets.forEach { asset ->
                        val fillPct = if (maxVal > 0) (asset.valueUSD.toDouble() / maxVal * 100).toInt() else 0
                        div("allocation-bar-row") {
                            div("allocation-bar-label") { +asset.symbol }
                            div("allocation-bar-track") {
                                div("allocation-bar-fill") {
                                    style = "width: $fillPct%;"
                                }
                            }
                            div("allocation-bar-value") {
                                +"$${formatCurrency(asset.valueUSD)} (${formatPercent(asset.currentPercent)}%)"
                            }
                        }
                    }
                }
            }

            div("glass-panel") {
                h2("glass-panel-title") {
                    +"Asset Performance"
                }
                div("table-wrapper") {
                    table {
                        thead {
                            tr {
                                th { attributes["class"] = "sortable"; attributes["onclick"] = "sortTable(this, 0)"; +"Asset" }
                                th { attributes["class"] = "sortable"; attributes["onclick"] = "sortTable(this, 1)"; +"Price" }
                                th { attributes["class"] = "sortable"; attributes["onclick"] = "sortTable(this, 2)"; +"Value" }
                                th { attributes["class"] = "sortable"; attributes["onclick"] = "sortTable(this, 3)"; +"Target %" }
                                th { attributes["class"] = "sortable"; attributes["onclick"] = "sortTable(this, 4)"; +"Current %" }
                                th { attributes["class"] = "sortable asc"; attributes["onclick"] = "sortTable(this, 5)"; +"Dev %" }
                            }
                        }
                        tbody {
                            val cryptoOnly = latest.assets.values.filter { it.symbol != "USD" }.sortedBy { it.deviationPercent }
                            cryptoOnly.forEach { asset ->
                                val dev = asset.deviationPercent
                                val devClass = getDeviationClass(dev)
                                val sign = getDeviationSign(dev)

                                tr("hoverable") {
                                    td("symbol-col") { +asset.symbol }
                                    td("mono-col") { +"$${formatCurrency(asset.price)}" }
                                    td("mono-col") { +"$${formatCurrency(asset.valueUSD)}" }
                                    td { +"${formatPercent(asset.targetPercent)}%" }
                                    td { +"${formatPercent(asset.currentPercent)}%" }
                                    td(devClass) {
                                        div {
                                            style = "display: flex; flex-direction: column; line-height: 1.1;"
                                            span { +"$sign${formatPercent(dev)}%" }
                                            span {
                                                style = "font-size: 0.675rem; opacity: 0.7; font-family: monospace;"
                                                val devUSD = asset.deviationUSD
                                                val usdSign = if (devUSD.signum() >= 0) "+" else ""
                                                +"($usdSign$${formatCurrency(devUSD)})"
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        div("glass-panel") {
            h2("glass-panel-title") {
                unsafe {
                    +"""<svg xmlns="http://www.w3.org/2000/svg" width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="22 12 18 12 15 21 9 3 6 12 2 12"></polyline></svg>"""
                }
                +"Recent Activity"
            }

            if (history.isEmpty()) {
                div("empty-history-box") {
                    unsafe {
                        +"""<svg xmlns="http://www.w3.org/2000/svg" width="48" height="48" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M12 2a10 10 0 1 0 10 10H12V2z"></path></svg>"""
                    }
                    h3 { +"Recent Activity" }
                    p { +"No trading history available." }
                }
            } else {
                div("table-wrapper custom-scrollbar max-h-100") {
                    table {
                        thead {
                            tr {
                                th { +"Time" }
                                th { +"Action" }
                            }
                        }
                        tbody {
                            history.forEach { snapshot ->
                                val timeStr = snapshot.timestamp.toString().replace("T", " ").substringBefore(".")
                                if (snapshot.actions.isEmpty()) {
                                    tr("hoverable") {
                                        td("mono-col") { +timeStr }
                                        td {
                                            span {
                                                style = "color: var(--color-text-muted); font-style: italic; display: flex; align-items: center; gap: 0.5rem;"
                                                span {
                                                    style = "width: 0.375rem; height: 0.375rem; border-radius: 50%; background-color: var(--color-text-muted);"
                                                }
                                                +"No trades executed (Cycle complete)"
                                            }
                                        }
                                    }
                                } else {
                                    snapshot.actions.forEach { action ->
                                        val isBuy = action.uppercase().startsWith("BUY")
                                        val isSell = action.uppercase().startsWith("SELL")
                                        val badgeClass = if (isBuy) "badge badge-buy" else if (isSell) "badge badge-sell" else "badge badge-info"
                                        val badgeText = if (isBuy) "BUY" else if (isSell) "SELL" else "INFO"

                                        tr("hoverable") {
                                            td("mono-col") { +timeStr }
                                            td {
                                                div {
                                                    style = "display: flex; align-items: center; gap: 0.75rem;"
                                                    span(badgeClass) { +badgeText }
                                                    span { +action }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun BODY.renderSettingsForm(config: AppConfig, errorMessage: String?) {
        div("container") {
            form {
                attributes["hx-post"] = "/settings"
                attributes["hx-target"] = "body"
                attributes["hx-swap"] = "innerHTML"

                header {
                    div("header-title-section") {
                        a(href = "/", classes = "btn btn-secondary") {
                            style = "padding: 0.5rem;"
                            unsafe {
                                +"""<svg xmlns="http://www.w3.org/2000/svg" width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><line x1="19" y1="12" x2="5" y2="12"></line><polyline points="12 19 5 12 12 5"></polyline></svg>"""
                            }
                        }
                        h1 { +"Settings" }
                    }
                    button(type = ButtonType.submit, classes = "btn btn-primary") {
                        id = "save-button"
                        unsafe {
                            +"""<svg xmlns="http://www.w3.org/2000/svg" width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M19 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h11l5 5v11a2 2 0 0 1-2 2z"></path><polyline points="17 21 17 13 7 13 7 21"></polyline><polyline points="7 3 7 8 15 8"></polyline></svg>"""
                        }
                        span { +"Save Configuration" }
                    }
                }

                if (errorMessage != null) {
                    div {
                        style = "background-color: rgba(239, 68, 68, 0.15); border: 1px solid rgba(239, 68, 68, 0.3); color: #fecaca; padding: 1rem; border-radius: 0.5rem; margin-bottom: 1.5rem; font-weight: 500;"
                        +errorMessage
                    }
                }

                div("glass-panel") {
                    div("form-section") {
                        h3("form-section-title") {
                            unsafe {
                                +"""<svg xmlns="http://www.w3.org/2000/svg" width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z"></path><line x1="12" y1="8" x2="12" y2="12"></line><line x1="12" y1="16" x2="12.01" y2="16"></line></svg>"""
                            }
                            +"Global Parameters"
                        }

                        div("grid-2col") {
                            div("form-group") {
                                label(classes = "form-label") { +"Loop Interval (Seconds)" }
                                input(type = InputType.number, name = "loopDelaySeconds", classes = "input-glass") {
                                    min = "1"
                                    value = config.settings.loopDelaySeconds.toString()
                                }
                            }

                            div("form-group") {
                                label(classes = "form-label") { +"Deviation Trigger (%)" }
                                input(type = InputType.number, name = "deviationTriggerPercent", classes = "input-glass") {
                                    step = "0.1"
                                    min = "0"
                                    value = config.settings.deviationTriggerPercent.toString()
                                }
                            }

                            div("form-group") {
                                label(classes = "form-label") { +"Dust Threshold ($)" }
                                input(type = InputType.number, name = "dustThresholdUSD", classes = "input-glass") {
                                    step = "0.5"
                                    value = config.settings.dustThresholdUSD.toString()
                                }
                            }

                            div("form-group") {
                                label(classes = "form-label") { +"Fiat Max Drawdown (%)" }
                                input(type = InputType.number, name = "fiatMaxDrawdown", classes = "input-glass") {
                                    step = "1.0"
                                    value = config.settings.fiatMaxDrawdown.toString()
                                }
                            }

                            div("form-group") {
                                label(classes = "form-label") { +"Fiat Deployment Exponent" }
                                input(type = InputType.number, name = "fiatDeploymentExponent", classes = "input-glass") {
                                    step = "0.1"
                                    value = config.settings.fiatDeploymentExponent.toString()
                                }
                            }

                            div("form-group") {
                                style = "justify-content: center; padding-top: 1rem;"
                                label("checkbox-container") {
                                    input(type = InputType.checkBox, name = "dryRun") {
                                        checked = config.settings.dryRun
                                    }
                                    div("checkbox-custom") {}
                                    span { +"Dry Run Mode (Safe)" }
                                }
                            }
                        }
                    }

                    div("form-section") {
                        div {
                            style = "display: flex; justify-content: space-between; align-items: center; margin-bottom: 1.25rem;"
                            h3 {
                                style = "font-size: 1.125rem; font-weight: 600; color: white; margin: 0;"
                                +"Target Allocations"
                            }
                            div("status-badge live") {
                                id = "total-allocated-display"
                                +"Total: 0.00%"
                            }
                        }

                        div("allocation-list-container") {
                            id = "allocations-container"
                            config.allocations.forEach { alloc ->
                                div("allocation-edit-row") {
                                    div("allocation-edit-symbol") { +alloc.symbol }
                                    input(type = InputType.hidden, name = "symbols") { value = alloc.symbol }
                                    div("allocation-edit-input-wrapper") {
                                        input(type = InputType.number, name = "targets", classes = "input-glass") {
                                            step = "0.1"
                                            value = alloc.targetPercent.toString()
                                            attributes["oninput"] = "updateAllocationTotal()"
                                        }
                                        span("percent-suffix") { +"%" }
                                    }
                                    button(type = ButtonType.button, classes = "btn btn-danger") {
                                        attributes["onclick"] = "this.closest('.allocation-edit-row').remove(); updateAllocationTotal();"
                                        +"Remove"
                                    }
                                }
                            }
                        }

                        div("add-asset-box") {
                            input(type = InputType.text, classes = "input-glass") {
                                id = "new-symbol-input"
                                placeholder = "New Symbol (e.g. DOT)"
                                style = "text-transform: uppercase; flex-grow: 1;"
                                attributes["onkeydown"] = "if(event.key === 'Enter') { event.preventDefault(); addAssetRow(); }"
                            }
                            button(type = ButtonType.button, classes = "btn btn-secondary") {
                                attributes["onclick"] = "addAssetRow()"
                                unsafe {
                                    +"""<svg xmlns="http://www.w3.org/2000/svg" width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><line x1="12" y1="5" x2="12" y2="19"></line><line x1="5" y1="12" x2="19" y2="12"></line></svg>"""
                                }
                                span { +"Add Asset" }
                            }
                        }
                    }
                }
            }
        }

        unsafe {
            +"""
            <template id="allocation-row-template">
                <div class="allocation-edit-row">
                    <div class="allocation-edit-symbol symbol-label"></div>
                    <input type="hidden" name="symbols">
                    <div class="allocation-edit-input-wrapper">
                        <input type="number" step="0.1" name="targets" class="input-glass" oninput="updateAllocationTotal()">
                        <span class="percent-suffix">%</span>
                    </div>
                    <button type="button" class="btn btn-danger" onclick="this.closest('.allocation-edit-row').remove(); updateAllocationTotal();">Remove</button>
                </div>
            </template>
            """
        }

        script {
            unsafe {
                +"""
                function updateAllocationTotal() {
                    const targets = Array.from(document.querySelectorAll('input[name="targets"]')).map(input => parseFloat(input.value) || 0.0);
                    const total = targets.reduce((sum, val) => sum + val, 0.0);
                    const totalDisplay = document.getElementById('total-allocated-display');
                    totalDisplay.textContent = 'Total: ' + total.toFixed(2) + '%';
                    
                    const saveButton = document.getElementById('save-button');
                    const isValid = Math.abs(total - 100.0) <= 0.01;
                    
                    const symbols = Array.from(document.querySelectorAll('input[name="symbols"]')).map(input => input.value.toUpperCase());
                    const hasUsd = symbols.includes('USD');
                    
                    if (isValid && hasUsd) {
                        totalDisplay.className = 'status-badge live';
                        saveButton.removeAttribute('disabled');
                    } else {
                        totalDisplay.className = 'status-badge delayed';
                        saveButton.setAttribute('disabled', 'true');
                    }
                }
                
                function addAssetRow() {
                    const symbolInput = document.getElementById('new-symbol-input');
                    const symbol = symbolInput.value.trim().toUpperCase();
                    if (!symbol) return;
                    
                    const existingSymbols = Array.from(document.querySelectorAll('input[name="symbols"]')).map(input => input.value.toUpperCase());
                    if (existingSymbols.includes(symbol)) {
                        alert('Symbol already exists');
                        return;
                    }
                    
                    const container = document.getElementById('allocations-container');
                    const template = document.getElementById('allocation-row-template');
                    const clone = template.content.cloneNode(true);
                    
                    clone.querySelector('.symbol-label').textContent = symbol;
                    clone.querySelector('input[name="symbols"]').value = symbol;
                    clone.querySelector('input[name="targets"]').value = "0.0";
                    
                    container.appendChild(clone);
                    symbolInput.value = '';
                    updateAllocationTotal();
                }
                
                updateAllocationTotal();
                """
            }
        }
    }

    private fun formatCurrency(value: BigDecimal): String {
        return String.format("%,.2f", value)
    }

    private fun formatPercent(value: BigDecimal): String {
        return String.format("%.2f", value)
    }

    private fun formatPercent(value: Double): String {
        return String.format("%.2f", value)
    }

    private fun getDeviationClass(deviation: BigDecimal): String {
        return if (deviation.signum() > 0) "text-danger" else if (deviation.signum() < 0) "text-success" else ""
    }

    private fun getDeviationSign(deviation: BigDecimal): String {
        return if (deviation.signum() > 0) "+" else ""
    }
}
