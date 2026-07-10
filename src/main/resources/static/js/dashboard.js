/**
 * Renders a single dashboard into #panel-grid and keeps each panel
 * refreshing on its own RefreshPolicy interval. Exposed as `Dashboard` so
 * both index.html (dashboard picker) and monitor.html (rotation) can reuse
 * the same rendering engine.
 */
const Dashboard = (() => {

    function humanize(fieldName) {
        return fieldName.replace(/_/g, ' ').replace(/\b\w/g, (c) => c.toUpperCase());
    }

    function escapeHtml(value) {
        const div = document.createElement('div');
        div.textContent = value === null || value === undefined ? '' : String(value);
        return div.innerHTML;
    }

    function formatCell(value) {
        if (value === null || value === undefined) return '<span style="opacity:.4">—</span>';
        if (typeof value === 'string' && /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}/.test(value)) {
            const d = new Date(value);
            if (!isNaN(d.getTime())) return escapeHtml(d.toLocaleString());
        }
        return escapeHtml(value);
    }

    function formatRelativeTime(date) {
        if (!date) return '—';
        const seconds = Math.max(0, Math.round((Date.now() - date.getTime()) / 1000));
        if (seconds < 5) return 'just now';
        if (seconds < 60) return `${seconds}s ago`;
        const minutes = Math.round(seconds / 60);
        if (minutes < 60) return `${minutes}m ago`;
        const hours = Math.round(minutes / 60);
        return `${hours}h ago`;
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
        time.textContent = '—';

        header.append(dot, title, time);

        const body = document.createElement('div');
        body.className = 'panel-body' + (panel.type === 'TABLE' ? ' no-pad' : '');
        body.innerHTML = '<div class="panel-loading">Loading…</div>';

        el.append(header, body);

        return { panel, el, dot, time, body, chart: null, timerId: null, lastRefreshAt: null };
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
        state.body.innerHTML = `
            <div class="kpi-body">
                <div><span class="kpi-value">${escapeHtml(displayValue)}</span>${opts.unit ? `<span class="kpi-unit">${escapeHtml(opts.unit)}</span>` : ''}</div>
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
        const thead = `<thead><tr>${columns.map((c) => `<th>${escapeHtml(humanize(c))}</th>`).join('')}</tr></thead>`;
        const tbody = `<tbody>${result.rows.map((row) =>
            `<tr>${columns.map((c) => `<td>${formatCell(row[c])}</td>`).join('')}</tr>`
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
            case 'KPI': renderKpi(state, result); break;
            case 'TABLE': renderTable(state, result); break;
            case 'BAR_CHART': renderChart(state, result, 'bar'); break;
            case 'LINE_CHART': renderChart(state, result, 'line'); break;
            case 'DONUT_CHART': renderChart(state, result, 'donut'); break;
            default: state.body.innerHTML = `<div class="panel-error">Unsupported panel type: ${escapeHtml(state.panel.type)}</div>`;
        }
    }

    async function refresh(dashboardId, state) {
        try {
            const result = await Api.getPanelData(dashboardId, state.panel.id);
            state.lastRefreshAt = new Date();
            state.dot.className = 'status-dot status-ok';
            render(state, result);
        } catch (err) {
            state.dot.className = 'status-dot status-error';
            state.body.innerHTML = `<div class="panel-error">${escapeHtml((err && err.message) || 'Failed to load panel data')}</div>`;
        }
    }

    /**
     * Mounts `dashboard` into `gridEl`, kicks off an immediate fetch for
     * every panel, and schedules recurring refreshes per panel.refresh.
     * Returns a controller object with a `destroy()` to stop all timers
     * (call before mounting a different dashboard into the same element).
     */
    function mount(gridEl, dashboard) {
        gridEl.innerHTML = '';
        gridEl.style.gridTemplateColumns = `repeat(${dashboard.gridColumns}, 1fr)`;

        const states = [];
        for (const panel of dashboard.panels) {
            const state = buildPanelCard(panel);
            gridEl.appendChild(state.el);
            states.push(state);

            refresh(dashboard.id, state);
            if (panel.refresh && panel.refresh.enabled && panel.refresh.intervalSeconds > 0) {
                state.timerId = setInterval(() => refresh(dashboard.id, state), panel.refresh.intervalSeconds * 1000);
            }
        }

        const clockTimerId = setInterval(() => {
            for (const state of states) {
                state.time.textContent = formatRelativeTime(state.lastRefreshAt);
            }
        }, 1000);

        const resizeHandler = () => states.forEach((s) => s.chart && s.chart.resize());
        window.addEventListener('resize', resizeHandler);

        return {
            destroy() {
                clearInterval(clockTimerId);
                window.removeEventListener('resize', resizeHandler);
                for (const state of states) {
                    if (state.timerId) clearInterval(state.timerId);
                    if (state.chart) state.chart.dispose();
                }
            },
        };
    }

    return { mount };
})();
