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
            
            // Localize the time display to expected hh:mm:ss a local format
            var date = new Date(epoch);
            var hours = date.getHours();
            var ampm = hours >= 12 ? 'PM' : 'AM';
            hours = hours % 12;
            hours = hours ? hours : 12; // 0 should be 12
            var hh = ('0' + hours).slice(-2);
            var mm = ('0' + date.getMinutes()).slice(-2);
            var ss = ('0' + date.getSeconds()).slice(-2);
            var localTimeStr = hh + ':' + mm + ':' + ss + ' ' + ampm;
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
