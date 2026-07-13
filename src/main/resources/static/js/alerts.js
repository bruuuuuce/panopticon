/**
 * The "bell" widget in the app header: aggregates threshold breaches across
 * every panel of whatever dashboard is currently mounted (see the
 * `onAlertsChanged` callback dashboard.js invokes on every panel refresh)
 * into a badge + dropdown list. Pages that don't render the bell markup
 * (none today, but kept defensive) simply no-op.
 */
import { humanize, escapeHtml, formatCell } from './format.js';

const panelAlerts = new Map(); // panelId -> { title, breaches }

let toggleBtn = null;
let badgeEl = null;
let dropdownEl = null;

function levelRank(level) {
    return level === 'critical' ? 2 : 1;
}

function aggregate() {
    let total = 0;
    let worst = null;
    for (const { breaches } of panelAlerts.values()) {
        total += breaches.length;
        for (const b of breaches) {
            if (!worst || levelRank(b.level) > levelRank(worst)) worst = b.level;
        }
    }
    return { total, worst };
}

function updateBadge() {
    const { total, worst } = aggregate();
    if (total === 0) {
        badgeEl.hidden = true;
        badgeEl.className = 'alerts-badge';
    } else {
        badgeEl.hidden = false;
        badgeEl.textContent = String(total);
        badgeEl.className = `alerts-badge level-${worst}`;
    }
}

function renderDropdown() {
    const entries = [...panelAlerts.entries()].filter(([, v]) => v.breaches.length > 0);
    if (entries.length === 0) {
        dropdownEl.innerHTML = '<div class="alerts-empty">No active alerts</div>';
        return;
    }
    dropdownEl.innerHTML = entries.map(([, { title, breaches }]) => `
        <div class="alert-item">
            <div class="alert-item-panel">${escapeHtml(title)}</div>
            ${breaches.map((b) => `
                <div class="alert-item-detail">
                    <span class="alert-level-badge level-${b.level}">${escapeHtml(b.level)}</span>
                    <span>${escapeHtml(b.label || humanize(b.field))}: ${formatCell(b.value)}</span>
                </div>
            `).join('')}
        </div>
    `).join('');
}

/** Called by dashboard.js after every panel refresh (and with an empty list on unmount). */
function setBreaches(panelId, breaches, panelTitle) {
    if (!toggleBtn) return;
    if (!breaches || breaches.length === 0) {
        panelAlerts.delete(panelId);
    } else {
        panelAlerts.set(panelId, { title: panelTitle, breaches });
    }
    updateBadge();
    if (!dropdownEl.hidden) renderDropdown();
}

function toggleDropdown() {
    dropdownEl.hidden = !dropdownEl.hidden;
    if (!dropdownEl.hidden) renderDropdown();
}

function init() {
    toggleBtn = document.getElementById('alerts-toggle');
    badgeEl = document.getElementById('alerts-badge');
    dropdownEl = document.getElementById('alerts-dropdown');
    if (!toggleBtn || !badgeEl || !dropdownEl) {
        toggleBtn = null; // guards setBreaches on pages without the widget
        return;
    }

    toggleBtn.addEventListener('click', (event) => {
        event.stopPropagation();
        toggleDropdown();
    });
    document.addEventListener('click', (event) => {
        if (!dropdownEl.hidden && !dropdownEl.contains(event.target) && event.target !== toggleBtn) {
            dropdownEl.hidden = true;
        }
    });
    document.addEventListener('keydown', (event) => {
        if (event.key === 'Escape' && !dropdownEl.hidden) dropdownEl.hidden = true;
    });
    updateBadge();
}

export const Alerts = { init, setBreaches };
