let currentSortCol = 5;    // Defaults to Dev % column (index 5)
let currentSortDir = 'asc'; // Defaults to 'asc'

const updateAge = () => {
    const ageEl = document.querySelector('.data-age-value');
    const timeEl = document.querySelector('.data-age-time');
    if (!ageEl || !timeEl) return;

    const epochStr = timeEl.getAttribute('data-epoch');
    if (!epochStr) return;

    const epoch = parseInt(epochStr, 10);
    const now = Date.now();
    const diff = Math.floor(Math.max(0, now - epoch) / 1000);
    
    ageEl.textContent = `${diff}s ago`;
    const isStale = diff > 90;
    ageEl.classList.toggle('stale', isStale);

    // Localize the time display to expected hh:mm:ss a local format
    const date = new Date(epoch);
    const hours = date.getHours();
    const ampm = hours >= 12 ? 'PM' : 'AM';
    const displayHours = hours % 12 || 12; // 0 should be 12
    const hh = String(displayHours).padStart(2, '0');
    const mm = String(date.getMinutes()).padStart(2, '0');
    const ss = String(date.getSeconds()).padStart(2, '0');
    const localTimeStr = `${hh}:${mm}:${ss} ${ampm}`;
    
    if (timeEl.textContent.trim() !== localTimeStr) {
        timeEl.textContent = localTimeStr;
    }

    const badgeEl = document.querySelector('.status-badge');
    if (badgeEl) {
        badgeEl.classList.toggle('delayed', isStale);
        badgeEl.classList.toggle('live', !isStale);
        const badgeText = isStale ? 'DELAYED' : 'LIVE';
        if (badgeEl.textContent !== badgeText) {
            badgeEl.textContent = badgeText;
        }
    }
};

const reapplySort = () => {
    const headers = document.querySelectorAll('th.sortable');
    if (headers.length > currentSortCol) {
        const header = headers[currentSortCol];
        sortTable(header, currentSortCol, currentSortDir);
    }
};

const ageTimer = setInterval(updateAge, 1000);

document.addEventListener('DOMContentLoaded', () => {
    updateAge();
    reapplySort();
});

document.addEventListener('htmx:afterSwap', () => {
    updateAge();
    reapplySort();
});

function sortTable(header, colIdx, forceDir) {
    const table = header.closest('table');
    const tbody = table.querySelector('tbody');
    const rows = Array.from(tbody.querySelectorAll('tr.hoverable'));
    const isAsc = header.classList.contains('asc');
    const sortAsc = forceDir !== undefined ? forceDir === 'asc' : !isAsc;
    const key = colIdx === 0 ? 'string' : 'float';

    rows.sort((a, b) => {
        const aText = a.children[colIdx].textContent.trim().replace(/[$,%]/g, '');
        const bText = b.children[colIdx].textContent.trim().replace(/[$,%]/g, '');
        
        if (key === 'float') {
            const aVal = parseFloat(aText) || 0;
            const bVal = parseFloat(bText) || 0;
            return sortAsc ? aVal - bVal : bVal - aVal;
        }
        
        return sortAsc
            ? aText.localeCompare(bText)
            : bText.localeCompare(aText);
    });

    table.querySelectorAll('th.sortable').forEach(th => {
        th.classList.remove('asc', 'desc');
    });
    header.classList.add(sortAsc ? 'asc' : 'desc');

    rows.forEach(row => tbody.append(row));

    // Keep track of the user's latest sort criteria so swaps can re-apply them stably
    currentSortCol = colIdx;
    currentSortDir = sortAsc ? 'asc' : 'desc';
}
