/** Entry point for the dashboard picker page (index.html). */
import { Api } from './api.js';
import { Dashboard } from './dashboard.js';

const selectEl = document.getElementById('dashboard-select');
const titleEl = document.getElementById('dashboard-title');
const descEl = document.getElementById('dashboard-desc');
const gridEl = document.getElementById('panel-grid');
const clockEl = document.getElementById('clock');

let controller = null;
let currentId = null;

setInterval(() => { clockEl.textContent = new Date().toLocaleTimeString(); }, 1000);
clockEl.textContent = new Date().toLocaleTimeString();

function paramDashboardId() {
    return new URLSearchParams(location.search).get('dashboard');
}

function setDashboardParam(id) {
    const url = new URL(location.href);
    url.searchParams.set('dashboard', id);
    history.replaceState(null, '', url);
}

async function loadDashboard(id) {
    // Fetch BEFORE tearing anything down: if the fetch fails the current
    // dashboard keeps running instead of leaving an empty grid behind.
    let dashboard;
    try {
        dashboard = await Api.getDashboard(id);
    } catch (err) {
        if (currentId) selectEl.value = currentId;
        descEl.textContent = `Failed to load dashboard "${id}": ${(err && err.message) || err}`;
        return;
    }
    if (controller) controller.destroy();
    currentId = id;
    selectEl.value = id;
    setDashboardParam(id);
    titleEl.textContent = dashboard.title;
    descEl.textContent = dashboard.description || '';
    controller = Dashboard.mount(gridEl, dashboard);
}

async function init() {
    try {
        const dashboards = await Api.listDashboards();
        if (dashboards.length === 0) {
            titleEl.textContent = 'No dashboards configured';
            descEl.textContent = 'Add a dashboard JSON file under config/dashboards.';
            return;
        }
        for (const d of dashboards) {
            const option = document.createElement('option');
            option.value = d.id;
            option.textContent = d.title;
            selectEl.appendChild(option);
        }
        selectEl.addEventListener('change', () => loadDashboard(selectEl.value));

        const initialId = paramDashboardId() && dashboards.some((d) => d.id === paramDashboardId())
            ? paramDashboardId()
            : dashboards[0].id;
        await loadDashboard(initialId);
    } catch (err) {
        titleEl.textContent = 'Failed to load dashboards';
        descEl.textContent = (err && err.message) || String(err);
    }
}

init();
