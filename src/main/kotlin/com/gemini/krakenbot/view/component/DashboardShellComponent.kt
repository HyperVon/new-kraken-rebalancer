package com.gemini.krakenbot.view.component

import kotlinx.html.*

class DashboardShellComponent {

    fun HTML.render() {
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
            renderShellScript()
        }
    }

    private fun BODY.renderShellScript() {
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
