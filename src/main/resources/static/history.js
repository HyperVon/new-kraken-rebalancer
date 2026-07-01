/* History Page — Chart.js charts & trade table rendering */

const CHART_COLORS = [
    'rgba(96, 165, 250, 1)',   /* blue-400 */
    'rgba(52, 211, 153, 1)',   /* emerald-400 */
    'rgba(251, 191, 36, 1)',   /* amber-400 */
    'rgba(167, 139, 250, 1)',  /* violet-400 */
    'rgba(248, 113, 113, 1)',  /* red-400 */
    'rgba(45, 212, 191, 1)',   /* teal-400 */
    'rgba(251, 146, 60, 1)',   /* orange-400 */
    'rgba(232, 121, 249, 1)'   /* fuchsia-400 */
];

const CHART_BG = [
    'rgba(96, 165, 250, 0.1)',
    'rgba(52, 211, 153, 0.1)',
    'rgba(251, 191, 36, 0.1)',
    'rgba(167, 139, 250, 0.1)',
    'rgba(248, 113, 113, 0.1)',
    'rgba(45, 212, 191, 0.1)',
    'rgba(251, 146, 60, 0.1)',
    'rgba(232, 121, 249, 0.1)'
];

const chartDefaults = {
    responsive: true,
    maintainAspectRatio: false,
    plugins: {
        legend: { labels: { color: '#94a3b8', font: { family: "'Inter', sans-serif", size: 12 } } },
        tooltip: {
            backgroundColor: 'rgba(15, 23, 42, 0.9)',
            borderColor: 'rgba(255,255,255,0.1)',
            borderWidth: 1,
            titleColor: '#f8fafc',
            bodyColor: '#cbd5e1',
            bodyFont: { family: "'Roboto Mono', monospace" },
            padding: 12,
            cornerRadius: 8
        }
    },
    scales: {
        x: {
            type: 'time',
            time: { tooltipFormat: 'MMM d, yyyy HH:mm' },
            grid: { color: 'rgba(51, 65, 85, 0.3)' },
            ticks: { color: '#64748b', maxTicksLimit: 8 }
        },
        y: {
            grid: { color: 'rgba(51, 65, 85, 0.3)' },
            ticks: { color: '#64748b' }
        }
    }
};

let charts = {};
let currentRange = '30d';

async function fetchJSON(url) {
    const res = await fetch(url);
    return res.json();
}

function formatUSD(val) {
    return '$' + Number(val).toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
}

function createOrUpdate(canvasId, config) {
    if (charts[canvasId]) {
        charts[canvasId].destroy();
    }
    const ctx = document.getElementById(canvasId);
    if (!ctx) return;
    charts[canvasId] = new Chart(ctx, config);
}

/* ---- Chart Builders ---- */

function buildPortfolioValueChart(snapshots) {
    if (!snapshots.length) return;

    const symbols = new Set();
    snapshots.forEach(s => Object.keys(s.assets || {}).forEach(k => symbols.add(k)));
    const symbolList = [...symbols].filter(s => s !== 'USD').sort();

    const datasets = [{
        label: 'Total Portfolio',
        data: snapshots.map(s => ({ x: s.timestamp, y: Number(s.totalValueUSD) })),
        borderColor: 'rgba(96, 165, 250, 1)',
        backgroundColor: 'rgba(96, 165, 250, 0.08)',
        fill: true,
        tension: 0.3,
        borderWidth: 2,
        pointRadius: 0,
        pointHitRadius: 10
    }];

    symbolList.forEach((sym, i) => {
        const ci = (i + 1) % CHART_COLORS.length;
        datasets.push({
            label: sym,
            data: snapshots.map(s => ({
                x: s.timestamp,
                y: s.assets[sym] ? Number(s.assets[sym].valueUSD) : 0
            })),
            borderColor: CHART_COLORS[ci],
            backgroundColor: 'transparent',
            tension: 0.3,
            borderWidth: 1.5,
            pointRadius: 0,
            pointHitRadius: 10
        });
    });

    createOrUpdate('portfolio-value-chart', {
        type: 'line',
        data: { datasets },
        options: {
            ...chartDefaults,
            plugins: {
                ...chartDefaults.plugins,
                tooltip: {
                    ...chartDefaults.plugins.tooltip,
                    callbacks: { label: ctx => `${ctx.dataset.label}: ${formatUSD(ctx.parsed.y)}` }
                }
            },
            scales: {
                ...chartDefaults.scales,
                y: { ...chartDefaults.scales.y, ticks: { ...chartDefaults.scales.y.ticks, callback: v => formatUSD(v) } }
            }
        }
    });
}

function buildAssetHoldingsChart(snapshots) {
    if (!snapshots.length) return;

    const symbols = new Set();
    snapshots.forEach(s => Object.keys(s.assets || {}).forEach(k => symbols.add(k)));
    const symbolList = [...symbols].filter(s => s !== 'USD').sort();

    const datasets = symbolList.map((sym, i) => ({
        label: sym,
        data: snapshots.map(s => ({
            x: s.timestamp,
            y: s.assets[sym] ? Number(s.assets[sym].balance) : 0
        })),
        borderColor: CHART_COLORS[i % CHART_COLORS.length],
        backgroundColor: 'transparent',
        tension: 0.3,
        borderWidth: 2,
        pointRadius: 0,
        pointHitRadius: 10,
        yAxisID: `y-${sym}`
    }));

    const scales = { x: chartDefaults.scales.x };
    symbolList.forEach((sym, i) => {
        scales[`y-${sym}`] = {
            type: 'linear',
            display: i === 0,
            position: i === 0 ? 'left' : 'right',
            grid: { color: i === 0 ? 'rgba(51, 65, 85, 0.3)' : 'transparent' },
            ticks: { color: CHART_COLORS[i % CHART_COLORS.length] }
        };
    });

    createOrUpdate('asset-holdings-chart', {
        type: 'line',
        data: { datasets },
        options: { ...chartDefaults, scales }
    });
}

function buildAllocationDriftChart(snapshots) {
    if (!snapshots.length) return;

    const symbols = new Set();
    snapshots.forEach(s => Object.keys(s.assets || {}).forEach(k => symbols.add(k)));
    const symbolList = [...symbols].sort();

    const datasets = symbolList.map((sym, i) => ({
        label: sym,
        data: snapshots.map(s => ({
            x: s.timestamp,
            y: s.assets[sym] ? Number(s.assets[sym].currentPercent) : 0
        })),
        borderColor: CHART_COLORS[i % CHART_COLORS.length],
        backgroundColor: CHART_BG[i % CHART_BG.length],
        fill: true,
        tension: 0.3,
        borderWidth: 1.5,
        pointRadius: 0,
        pointHitRadius: 10
    }));

    createOrUpdate('allocation-drift-chart', {
        type: 'line',
        data: { datasets },
        options: {
            ...chartDefaults,
            plugins: {
                ...chartDefaults.plugins,
                tooltip: {
                    ...chartDefaults.plugins.tooltip,
                    callbacks: { label: ctx => `${ctx.dataset.label}: ${Number(ctx.parsed.y).toFixed(2)}%` }
                }
            },
            scales: {
                ...chartDefaults.scales,
                y: {
                    ...chartDefaults.scales.y,
                    stacked: true,
                    ticks: { ...chartDefaults.scales.y.ticks, callback: v => v + '%' }
                }
            }
        }
    });
}

function buildCumulativePLChart(trades) {
    if (!trades.length) return;

    const sorted = [...trades].sort((a, b) => new Date(a.timestamp) - new Date(b.timestamp));
    let cumulative = 0;
    const data = sorted.filter(t => t.success).map(t => {
        const amt = Number(t.usdAmount);
        cumulative += t.side === 'SELL' ? amt : -amt;
        return { x: t.timestamp, y: cumulative };
    });

    if (!data.length) return;

    createOrUpdate('cumulative-pl-chart', {
        type: 'line',
        data: {
            datasets: [{
                label: 'Cumulative P&L',
                data,
                borderColor: 'rgba(52, 211, 153, 1)',
                backgroundColor: 'rgba(52, 211, 153, 0.08)',
                fill: true,
                tension: 0.3,
                borderWidth: 2,
                pointRadius: 0,
                pointHitRadius: 10
            }]
        },
        options: {
            ...chartDefaults,
            scales: {
                ...chartDefaults.scales,
                y: {
                    ...chartDefaults.scales.y,
                    ticks: { ...chartDefaults.scales.y.ticks, callback: v => formatUSD(v) }
                }
            }
        }
    });
}

/* ---- Trade Table ---- */

function renderTradeTable(trades) {
    const tbody = document.getElementById('trade-table-body');
    if (!tbody) return;

    if (!trades.length) {
        tbody.innerHTML = '<tr><td colspan="6" style="text-align:center;color:var(--color-text-muted);padding:2rem;">No trades found for this period.</td></tr>';
        return;
    }

    tbody.innerHTML = trades.map(t => {
        const time = new Date(t.timestamp).toLocaleString();
        const sideClass = t.side === 'BUY' ? 'badge badge-buy' : 'badge badge-sell';
        const statusText = t.success ? (t.dryRun ? 'DRY RUN' : 'SUCCESS') : 'FAILED';
        const statusClass = t.success ? (t.dryRun ? 'badge badge-info' : 'badge badge-buy') : 'badge badge-sell';
        return `<tr class="hoverable">
            <td class="mono-col">${time}</td>
            <td class="symbol-col">${t.pair}</td>
            <td><span class="${sideClass}">${t.side}</span></td>
            <td class="mono-col">${Number(t.volume).toFixed(8)}</td>
            <td class="mono-col">${formatUSD(t.usdAmount)}</td>
            <td><span class="${statusClass}">${statusText}</span></td>
        </tr>`;
    }).join('');
}

/* ---- Stats Cards ---- */

function updateStats(stats) {
    const ath = document.getElementById('stat-ath');
    const totalTrades = document.getElementById('stat-total-trades');
    const totalVolume = document.getElementById('stat-total-volume');
    const daysRunning = document.getElementById('stat-days-running');

    if (ath) ath.textContent = formatUSD(stats.allTimeHigh);
    if (totalTrades) totalTrades.textContent = stats.totalTradesExecuted.toLocaleString();
    if (totalVolume) totalVolume.textContent = formatUSD(stats.totalVolumeTraded);

    if (daysRunning && stats.firstSnapshotTime) {
        const first = new Date(stats.firstSnapshotTime);
        const now = new Date();
        const days = Math.floor((now - first) / (1000 * 60 * 60 * 24));
        daysRunning.textContent = days === 0 ? '< 1 day' : `${days} days`;
    } else if (daysRunning) {
        daysRunning.textContent = '--';
    }
}

/* ---- Main Load / Refresh ---- */

async function loadAll(range) {
    currentRange = range || currentRange;

    const [snapshots, trades, stats] = await Promise.all([
        fetchJSON(`/api/history/snapshots?range=${currentRange}`),
        fetchJSON(`/api/history/trades?range=${currentRange}`),
        fetchJSON('/api/history/stats')
    ]);

    buildPortfolioValueChart(snapshots);
    buildAssetHoldingsChart(snapshots);
    buildAllocationDriftChart(snapshots);
    buildCumulativePLChart(trades);
    renderTradeTable(trades);
    updateStats(stats);
}

/* ---- Time Range Buttons ---- */

document.addEventListener('DOMContentLoaded', () => {
    loadAll('30d');

    document.querySelectorAll('.time-range-btn').forEach(btn => {
        btn.addEventListener('click', () => {
            document.querySelectorAll('.time-range-btn').forEach(b => b.classList.remove('active'));
            btn.classList.add('active');
            loadAll(btn.getAttribute('data-range'));
        });
    });
});
