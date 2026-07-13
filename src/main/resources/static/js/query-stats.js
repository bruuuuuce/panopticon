/**
 * Fixed "query stats" ops page: one row per loaded data definition, with its
 * real execution history (count, last execution, avg/min/max/p95/p99
 * duration — each extreme paired with when it happened) and an on-demand
 * execution plan for jdbc-backed queries. Not a JSON-configured dashboard;
 * bootstraps itself against /api/query-stats the same way monitor.js
 * bootstraps monitor mode against /api/dashboards.
 */
import { Api } from './api.js';
import { escapeHtml, formatRelativeTime } from './format.js';

const REFRESH_INTERVAL_MS = 10_000;
const container = document.getElementById('query-stats-container');

/** e.g. 7 -> "7 ms", 1234 -> "1.23 s" */
function formatDuration(ms) {
    if (ms === null || ms === undefined) return '—';
    if (ms < 1000) return `${Math.round(ms)} ms`;
    return `${(ms / 1000).toFixed(2)} s`;
}

function formatTimestampCell(iso) {
    if (!iso) return '<span class="stat-value">—</span>';
    const date = new Date(iso);
    return `<span class="stat-value" title="${escapeHtml(date.toLocaleString())}">${escapeHtml(formatRelativeTime(date))}</span>`;
}

/** Duration value with a small muted "when it happened" subline underneath. */
function formatDurationWithTimestamp(ms, iso) {
    if (ms === null || ms === undefined) {
        return '<span class="stat-value">—</span>';
    }
    const sub = iso
        ? `<div class="stat-sub" title="${escapeHtml(new Date(iso).toLocaleString())}">${escapeHtml(formatRelativeTime(new Date(iso)))}</div>`
        : '';
    return `<div class="stat-value">${escapeHtml(formatDuration(ms))}</div>${sub}`;
}

function statusBadge(status) {
    if (!status) return '';
    const cls = status === 'OK' ? 'status-good' : 'status-critical';
    return `<span class="stat-status-badge ${cls}">${escapeHtml(status)}</span>`;
}

const ACCESS_TYPE_LABEL = {
    FULL_SCAN: 'Full scan',
    INDEX_SEARCH: 'Index search',
    OTHER: 'Other',
};

const ACCESS_TYPE_CLASS = {
    FULL_SCAN: 'access-full-scan',
    INDEX_SEARCH: 'access-index-search',
    OTHER: 'access-other',
};

function renderStepsSummary(steps) {
    const fullScans = steps.filter((s) => s.accessType === 'FULL_SCAN').length;
    const indexed = steps.filter((s) => s.accessType === 'INDEX_SEARCH').length;
    const parts = [`${steps.length} access path${steps.length === 1 ? '' : 's'}`];
    if (fullScans > 0) parts.push(`<span class="access-full-scan">${fullScans} full scan${fullScans === 1 ? '' : 's'}</span>`);
    if (indexed > 0) parts.push(`<span class="access-index-search">${indexed} indexed</span>`);
    return `<div class="plan-summary">${parts.join(' · ')}</div>`;
}

function renderStepsTable(steps) {
    const rows = steps.map((s) => `<tr>
        <td>${escapeHtml(s.subject || '—')}</td>
        <td><span class="access-badge ${ACCESS_TYPE_CLASS[s.accessType] || 'access-other'}">${escapeHtml(ACCESS_TYPE_LABEL[s.accessType] || s.accessType)}</span></td>
        <td>${escapeHtml(s.detail)}</td>
    </tr>`).join('');
    return `<table class="panel-table plan-steps-table">
        <thead><tr><th>Subject</th><th>Access</th><th>Detail</th></tr></thead>
        <tbody>${rows}</tbody>
    </table>`;
}

function renderRawPlanTable(planRows) {
    if (planRows.length === 0) return '';
    const columns = Object.keys(planRows[0]);
    const thead = `<thead><tr>${columns.map((c) => `<th>${escapeHtml(c)}</th>`).join('')}</tr></thead>`;
    const tbody = `<tbody>${planRows.map((row) =>
        `<tr>${columns.map((c) => `<td>${escapeHtml(row[c])}</td>`).join('')}</tr>`
    ).join('')}</tbody>`;
    return `<details class="plan-raw"><summary>Raw plan (${planRows.length} row${planRows.length === 1 ? '' : 's'})</summary>
        <div class="panel-table-wrap plan-table-wrap"><table class="panel-table">${thead}${tbody}</table></div>
    </details>`;
}

function renderPlanRow(dataId, plan) {
    const cell = document.createElement('td');
    cell.colSpan = 12;
    cell.className = 'plan-cell';

    if (!plan.supported) {
        cell.innerHTML = `<div class="plan-unsupported">Execution plan not available: ${escapeHtml(plan.unsupportedReason || 'unsupported')}</div>`;
        return cell;
    }

    const stepsHtml = plan.steps.length
        ? `${renderStepsSummary(plan.steps)}${renderStepsTable(plan.steps)}`
        : '<div class="plan-unsupported">No table/index access paths were extracted from this plan.</div>';

    cell.innerHTML = `${stepsHtml}${renderRawPlanTable(plan.planRows)}`;
    return cell;
}

async function togglePlan(dataId, button, planRowEl) {
    if (!planRowEl.hidden) {
        planRowEl.hidden = true;
        button.textContent = 'Plan';
        return;
    }
    if (!planRowEl.dataset.loaded) {
        button.disabled = true;
        button.textContent = 'Loading…';
        try {
            const plan = await Api.getQueryPlan(dataId);
            planRowEl.replaceChildren(renderPlanRow(dataId, plan));
            planRowEl.dataset.loaded = 'true';
        } catch (err) {
            const cell = document.createElement('td');
            cell.colSpan = 12;
            cell.className = 'plan-cell';
            cell.innerHTML = `<div class="panel-error">${escapeHtml((err && err.message) || 'Failed to load plan')}</div>`;
            planRowEl.replaceChildren(cell);
        } finally {
            button.disabled = false;
        }
    }
    planRowEl.hidden = false;
    button.textContent = 'Hide plan';
}

function buildRow(stats) {
    const tr = document.createElement('tr');
    tr.innerHTML = `
        <td>
            <div class="stat-value">${escapeHtml(stats.name)}</div>
            <div class="stat-sub">${escapeHtml(stats.dataId)}</div>
        </td>
        <td>${escapeHtml(stats.provider)}</td>
        <td>
            <div class="stat-value">${escapeHtml(stats.datasourceDisplayName)}</div>
            ${stats.datasourceDisplayName !== stats.datasource ? `<div class="stat-sub">${escapeHtml(stats.datasource)}</div>` : ''}
        </td>
        <td>${formatTimestampCell(stats.lastExecutedAt)} ${statusBadge(stats.lastStatus)}</td>
        <td>${escapeHtml(formatDuration(stats.lastDurationMs))}</td>
        <td>${stats.lastRowCount === null || stats.lastRowCount === undefined ? '—' : escapeHtml(stats.lastRowCount)}</td>
        <td>${stats.totalExecutions}${stats.totalErrors > 0 ? ` <span class="stat-errors">(${stats.totalErrors} errors)</span>` : ''}</td>
        <td>${escapeHtml(formatDuration(stats.avgDurationMs))}</td>
        <td>${formatDurationWithTimestamp(stats.minDurationMs, stats.minAt)}</td>
        <td>${formatDurationWithTimestamp(stats.maxDurationMs, stats.maxAt)}</td>
        <td>${formatDurationWithTimestamp(stats.p95DurationMs, stats.p95At)}</td>
        <td>${formatDurationWithTimestamp(stats.p99DurationMs, stats.p99At)}</td>
        <td><button type="button" class="plan-toggle">Plan</button></td>`;

    const planRow = document.createElement('tr');
    planRow.className = 'plan-row';
    planRow.hidden = true;
    const placeholderCell = document.createElement('td');
    placeholderCell.colSpan = 12;
    planRow.appendChild(placeholderCell);

    const button = tr.querySelector('.plan-toggle');
    button.addEventListener('click', () => togglePlan(stats.dataId, button, planRow));

    const fragment = document.createDocumentFragment();
    fragment.append(tr, planRow);
    return fragment;
}

function render(statsList) {
    if (statsList.length === 0) {
        container.innerHTML = '<div class="panel-empty">No data definitions loaded</div>';
        return;
    }
    const table = document.createElement('table');
    table.className = 'panel-table';
    table.innerHTML = `<thead><tr>
        <th>Query</th><th>Provider</th><th>Datasource</th><th>Last execution</th>
        <th>Last duration</th><th>Records read</th><th>Total executions</th>
        <th>Avg</th><th>Min</th><th>Max</th><th>P95</th><th>P99</th><th>Plan</th>
    </tr></thead>`;
    const tbody = document.createElement('tbody');
    for (const stats of statsList) {
        tbody.appendChild(buildRow(stats));
    }
    table.appendChild(tbody);

    container.innerHTML = '';
    const wrap = document.createElement('div');
    wrap.className = 'panel-table-wrap stats-table-wrap';
    wrap.appendChild(table);
    container.appendChild(wrap);
}

async function refresh() {
    if (document.hidden) return;
    try {
        const statsList = await Api.getQueryStats();
        render(statsList);
    } catch (err) {
        container.innerHTML = `<div class="panel-error">${escapeHtml((err && err.message) || 'Failed to load query stats')}</div>`;
    }
}

refresh();
setInterval(refresh, REFRESH_INTERVAL_MS);
document.addEventListener('visibilitychange', () => {
    if (!document.hidden) refresh();
});
