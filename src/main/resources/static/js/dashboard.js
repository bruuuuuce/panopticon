/**
 * Renders a single dashboard into a grid container and keeps each panel
 * refreshing on its own RefreshPolicy interval. Exported as `Dashboard` so
 * both index.html (dashboard picker) and monitor.html (rotation) can reuse
 * the same rendering engine.
 */
import { Api } from './api.js';
import { Charts } from './charts.js';
import { humanize, escapeHtml, formatCell, formatRelativeTime } from './format.js';
import { evaluateThresholds, worstLevel } from './thresholds.js';

/** e.g. 30 -> "30s", 90 -> "1m30s", 120 -> "2m" */
function formatInterval(seconds) {
    if (seconds < 60) return `${seconds}s`;
    const minutes = Math.floor(seconds / 60);
    const rest = seconds % 60;
    return rest === 0 ? `${minutes}m` : `${minutes}m${rest}s`;
}

function refreshLabel(panel) {
    return panel.refresh && panel.refresh.enabled && panel.refresh.intervalSeconds > 0
        ? `every ${formatInterval(panel.refresh.intervalSeconds)}`
        : 'manual';
}

function buildPanelCard(panel) {
    const el = document.createElement('div');
    el.className = 'panel';
    el.style.gridRow = `${panel.grid.row} / span ${panel.grid.rowSpan}`;
    el.style.gridColumn = `${panel.grid.col} / span ${panel.grid.colSpan}`;

    const header = document.createElement('div');
    header.className = 'panel-header';

    const dot = document.createElement('span');
    dot.className = 'status-dot status-pending';

    const title = document.createElement('span');
    title.className = 'panel-title';
    title.textContent = panel.title;
    title.title = panel.title;

    const time = document.createElement('span');
    time.className = 'refresh-time';
    time.textContent = refreshLabel(panel);
    time.title = 'Query refresh interval · time since last refresh';

    header.append(dot, title, time);

    const body = document.createElement('div');
    body.className = 'panel-body' + (panel.type === 'table' ? ' no-pad' : '');
    body.innerHTML = '<div class="panel-loading">Loading…</div>';

    el.append(header, body);

    return {
        panel, el, dot, time, body, chart: null, timerId: null,
        lastRefreshAt: null, lastResult: null, refreshing: false, datasourceName: null, breaches: [],
    };
}

function renderKpi(state, result) {
    const opts = state.panel.options || {};
    const row = result.rows[0] || {};
    const raw = row[opts.valueField];
    let displayValue = '—';
    if (raw !== null && raw !== undefined && raw !== '') {
        const num = Number(raw);
        displayValue = opts.format === 'decimal' ? num.toFixed(1) : Math.round(num).toLocaleString();
    }
    const level = worstLevel(state.breaches);
    const levelClass = level ? ` level-${level}` : '';
    state.body.innerHTML = `
        <div class="kpi-body">
            <div><span class="kpi-value${levelClass}">${escapeHtml(displayValue)}</span>${opts.unit ? `<span class="kpi-unit">${escapeHtml(opts.unit)}</span>` : ''}</div>
            <div class="kpi-label">${escapeHtml(state.panel.title)}</div>
        </div>`;
}

function renderTable(state, result) {
    const opts = state.panel.options || {};
    const columns = (opts.columns && opts.columns.length) ? opts.columns : result.columns.map((c) => c.name);
    if (result.rows.length === 0) {
        state.body.innerHTML = '<div class="panel-empty">No data</div>';
        return;
    }
    // rowIndex+field -> level, so a cell whose column is under threshold gets a
    // highlight class even though the breach list itself is column-agnostic.
    const cellLevels = new Map();
    for (const b of state.breaches) cellLevels.set(`${b.rowIndex}:${b.field}`, b.level);

    const thead = `<thead><tr>${columns.map((c) => `<th>${escapeHtml(humanize(c))}</th>`).join('')}</tr></thead>`;
    const tbody = `<tbody>${result.rows.map((row, rowIndex) =>
        `<tr>${columns.map((c) => {
            const level = cellLevels.get(`${rowIndex}:${c}`);
            const cellClass = level ? ` class="cell-threshold-${level}"` : '';
            return `<td${cellClass}>${formatCell(row[c])}</td>`;
        }).join('')}</tr>`
    ).join('')}</tbody>`;
    state.body.innerHTML = `<div class="panel-table-wrap"><table class="panel-table">${thead}${tbody}</table></div>`;
}

function renderChart(state, result, kind) {
    const opts = state.panel.options || {};
    if (!state.chart) {
        state.body.innerHTML = '';
        const canvas = document.createElement('div');
        canvas.className = 'chart-canvas';
        state.body.appendChild(canvas);
        // Created once per panel and reused for every subsequent refresh via
        // setOption() below — re-creating the ECharts instance on every poll
        // would repaint from scratch and drop hover/resize state for no reason.
        state.chart = echarts.init(canvas, null, { renderer: 'canvas' });
    }
    if (result.rows.length === 0) {
        state.chart.clear();
        return;
    }
    let option;
    if (kind === 'bar') option = Charts.barOption(result.rows, opts.xField, opts.yField, opts.seriesName);
    else if (kind === 'line') option = Charts.lineOption(result.rows, opts.xField, opts.yField, opts.seriesName);
    else option = Charts.donutOption(result.rows, opts.labelField, opts.valueField);
    state.chart.setOption(option, true);
}

function render(state, result) {
    switch (state.panel.type) {
        case 'stat': renderKpi(state, result); break;
        case 'table': renderTable(state, result); break;
        case 'bar': renderChart(state, result, 'bar'); break;
        case 'line': renderChart(state, result, 'line'); break;
        case 'donut': renderChart(state, result, 'donut'); break;
        default: state.body.innerHTML = `<div class="panel-error">Unsupported panel type: ${escapeHtml(state.panel.type)}</div>`;
    }
}

function dotClassForLevel(level) {
    if (level === 'critical') return 'status-dot status-breach';
    if (level === 'warning') return 'status-dot status-warn';
    return 'status-dot status-ok';
}

async function refresh(dashboardId, state, onAlertsChanged) {
    // A slow query (near its timeout) could still be in flight when the next
    // interval tick fires; skip that tick rather than let two fetches for the
    // same panel race and render out of order.
    if (state.refreshing) return;
    // Don't poll a tab nobody can see; the visibilitychange handler in mount()
    // refreshes everything as soon as the page is shown again.
    if (document.hidden) return;
    state.refreshing = true;
    try {
        const result = await Api.getPanelData(dashboardId, state.panel.id);
        state.lastRefreshAt = new Date();
        state.lastResult = result;
        state.datasourceName = result.datasourceName || null;
        state.breaches = evaluateThresholds(state.panel, result);
        const level = worstLevel(state.breaches);
        state.dot.className = dotClassForLevel(level);
        state.dot.title = level ? `${state.breaches.length} threshold breach(es), worst: ${level}` : '';
        render(state, result);
        if (onAlertsChanged) onAlertsChanged(state.panel.id, state.breaches, state.panel.title);
    } catch (err) {
        state.breaches = [];
        state.dot.className = 'status-dot status-error';
        state.body.innerHTML = `<div class="panel-error">${escapeHtml((err && err.message) || 'Failed to load panel data')}</div>`;
        if (onAlertsChanged) onAlertsChanged(state.panel.id, [], state.panel.title);
    } finally {
        state.refreshing = false;
    }
}

/**
 * Mounts `dashboard` into `gridEl`, kicks off an immediate fetch for
 * every panel, and schedules recurring refreshes per panel.refresh.
 * Returns a controller object with a `destroy()` to stop all timers
 * (call before mounting a different dashboard into the same element).
 *
 * `fillHeight: true` (used by monitor mode) makes panel rows share the
 * container's full height evenly instead of the picker page's fixed
 * `grid-auto-rows`, so a dashboard fills a wall monitor edge-to-edge with no
 * dead space and no scrolling, whatever its row count.
 */
function applyAccentColor(dashboard) {
    if (dashboard.accentColor) {
        document.body.style.setProperty('--dashboard-accent', dashboard.accentColor);
    } else {
        document.body.style.removeProperty('--dashboard-accent');
    }
}

function mount(gridEl, dashboard, { fillHeight = false, onAlertsChanged = null } = {}) {
    gridEl.innerHTML = '';
    gridEl.style.gridTemplateColumns = `repeat(${dashboard.gridColumns}, 1fr)`;
    applyAccentColor(dashboard);
    if (fillHeight) {
        const rowCount = Math.max(1, ...dashboard.panels.map((p) => p.grid.row + p.grid.rowSpan - 1));
        gridEl.style.gridAutoRows = 'unset';
        gridEl.style.gridTemplateRows = `repeat(${rowCount}, 1fr)`;
    } else {
        gridEl.style.gridTemplateRows = '';
    }

    const states = [];
    for (const panel of dashboard.panels) {
        const state = buildPanelCard(panel);
        gridEl.appendChild(state.el);
        states.push(state);

        refresh(dashboard.id, state, onAlertsChanged);
        if (panel.refresh && panel.refresh.enabled && panel.refresh.intervalSeconds > 0) {
            state.timerId = setInterval(() => refresh(dashboard.id, state, onAlertsChanged), panel.refresh.intervalSeconds * 1000);
        }
    }

    const clockTimerId = setInterval(() => {
        for (const state of states) {
            const dsPart = state.datasourceName ? ` · ${state.datasourceName}` : '';
            state.time.textContent = `${refreshLabel(state.panel)}${dsPart} · ${formatRelativeTime(state.lastRefreshAt)}`;
            state.time.title = state.datasourceName
                ? `Connection: ${state.datasourceName} · time since last refresh`
                : 'Query refresh interval · time since last refresh';
        }
    }, 1000);

    // Debounced: a window drag fires resize continuously, and resizing every
    // chart on every event is a repaint storm that grows with panel count.
    let resizeTimerId = null;
    const resizeHandler = () => {
        clearTimeout(resizeTimerId);
        resizeTimerId = setTimeout(() => states.forEach((s) => s.chart && s.chart.resize()), 150);
    };
    window.addEventListener('resize', resizeHandler);

    const visibilityHandler = () => {
        if (!document.hidden) {
            for (const state of states) refresh(dashboard.id, state, onAlertsChanged);
        }
    };
    document.addEventListener('visibilitychange', visibilityHandler);

    // Chart colors are baked into canvas draw calls at setOption() time, not
    // live CSS custom properties like the rest of the UI — a day/night theme
    // flip (see theme.js) needs charts explicitly re-rendered with the new
    // palette rather than just re-cascading, or they'd look wrong until
    // their next scheduled data refresh (up to panel.refresh.intervalSeconds later).
    const themeChangeHandler = () => {
        for (const state of states) {
            if (state.chart && state.lastResult) {
                render(state, state.lastResult);
            }
        }
    };
    window.addEventListener('panopticon:theme-changed', themeChangeHandler);

    return {
        destroy() {
            clearInterval(clockTimerId);
            clearTimeout(resizeTimerId);
            window.removeEventListener('resize', resizeHandler);
            document.removeEventListener('visibilitychange', visibilityHandler);
            window.removeEventListener('panopticon:theme-changed', themeChangeHandler);
            document.body.style.removeProperty('--dashboard-accent');
            for (const state of states) {
                if (state.timerId) clearInterval(state.timerId);
                if (state.chart) state.chart.dispose();
                // Clear this panel's alerts so switching dashboards (or rotating in
                // monitor mode) doesn't leave "ghost" alerts from an unmounted panel.
                if (onAlertsChanged) onAlertsChanged(state.panel.id, [], state.panel.title);
            }
        },
    };
}

export const Dashboard = { mount };
