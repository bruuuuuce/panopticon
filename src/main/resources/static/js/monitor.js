/**
 * Monitor (rotation) mode: cycles through dashboards whose RotationPolicy
 * has enabled=true, one at a time, for that dashboard's own
 * rotation.durationSeconds, with position/countdown indicators and
 * pause/prev/next/fullscreen controls.
 */
import { Api } from './api.js';
import { Dashboard } from './dashboard.js';
import { Alerts } from './alerts.js';

const gridEl = document.getElementById('panel-grid');
const titleEl = document.getElementById('current-dashboard-title');
const positionEl = document.getElementById('dashboard-position');
const countdownEl = document.getElementById('countdown');
const progressBar = document.getElementById('progress-bar');
const pauseBtn = document.getElementById('btn-pause');
const prevBtn = document.getElementById('btn-prev');
const nextBtn = document.getElementById('btn-next');
const fullscreenBtn = document.getElementById('btn-fullscreen');

const TICK_MS = 200;

let dashboards = [];
let index = 0;
let controller = null;
let paused = false;
let tickTimer = null;
let elapsedMs = 0;
let durationMs = 20000;

async function init() {
    Alerts.init();
    try {
        const summaries = await Api.listDashboards();
        dashboards = summaries.filter((d) => !d.rotation || d.rotation.enabled !== false);
        if (dashboards.length === 0) dashboards = summaries;
    } catch (err) {
        titleEl.textContent = 'Failed to load dashboards';
        return;
    }
    if (dashboards.length === 0) {
        titleEl.textContent = 'No dashboards configured';
        return;
    }
    await showCurrent();
    startTicking();
}

async function showCurrent() {
    const summary = dashboards[index];
    titleEl.textContent = summary.title;
    positionEl.textContent = `${index + 1} of ${dashboards.length}`;
    try {
        const dashboard = await Api.getDashboard(summary.id);
        titleEl.textContent = dashboard.title;
        durationMs = ((dashboard.rotation && dashboard.rotation.durationSeconds) || 20) * 1000;
        elapsedMs = 0;
        progressBar.style.width = '0%';
        updateCountdown();
        if (controller) controller.destroy();
        controller = Dashboard.mount(gridEl, dashboard, { fillHeight: true, onAlertsChanged: Alerts.setBreaches });
    } catch (err) {
        // The grid is being replaced by the error box, so the previous
        // dashboard's timers and chart instances must be torn down here too,
        // or every failed rotation step would leak them against detached DOM.
        if (controller) {
            controller.destroy();
            controller = null;
        }
        const box = document.createElement('div');
        box.className = 'panel-error monitor-load-error';
        box.textContent = (err && err.message) || 'Failed to load dashboard';
        gridEl.replaceChildren(box);
    }
}

function updateCountdown() {
    if (dashboards.length <= 1) {
        countdownEl.textContent = '';
    } else if (paused) {
        countdownEl.textContent = 'Paused';
    } else {
        const remainingSeconds = Math.max(0, Math.ceil((durationMs - elapsedMs) / 1000));
        countdownEl.textContent = `Next in ${remainingSeconds}s`;
    }
}

function startTicking() {
    if (tickTimer) clearInterval(tickTimer);
    tickTimer = setInterval(() => {
        // Nobody is watching a hidden tab: freeze rotation instead of cycling
        // (and fetching) dashboards in the background for hours.
        if (document.hidden) return;
        updateCountdown();
        if (paused || dashboards.length <= 1) return;
        elapsedMs += TICK_MS;
        const pct = Math.min(100, (elapsedMs / durationMs) * 100);
        progressBar.style.width = pct + '%';
        if (elapsedMs >= durationMs) {
            goNext();
        }
    }, TICK_MS);
}

function goNext() {
    index = (index + 1) % dashboards.length;
    showCurrent();
}

function goPrev() {
    index = (index - 1 + dashboards.length) % dashboards.length;
    showCurrent();
}

function togglePause() {
    paused = !paused;
    pauseBtn.textContent = paused ? 'Resume' : 'Pause';
    pauseBtn.classList.toggle('active', paused);
    updateCountdown();
}

function isFullscreen() {
    return document.fullscreenElement != null;
}

function updateFullscreenButton() {
    fullscreenBtn.textContent = isFullscreen() ? 'Exit fullscreen' : 'Fullscreen';
    fullscreenBtn.classList.toggle('active', isFullscreen());
}

async function toggleFullscreen() {
    try {
        if (isFullscreen()) {
            await document.exitFullscreen();
        } else {
            await document.documentElement.requestFullscreen();
        }
    } catch {
        // Fullscreen can be denied (e.g. no user-gesture context, or the
        // browser/embedder disallows it) — nothing useful to do beyond
        // leaving the button in its current, honest state.
    }
}

nextBtn.addEventListener('click', goNext);
prevBtn.addEventListener('click', goPrev);
pauseBtn.addEventListener('click', togglePause);
fullscreenBtn.addEventListener('click', toggleFullscreen);
document.addEventListener('fullscreenchange', updateFullscreenButton);

document.addEventListener('keydown', (e) => {
    if (e.key === 'ArrowRight') goNext();
    else if (e.key === 'ArrowLeft') goPrev();
    else if (e.key === 'f' || e.key === 'F') toggleFullscreen();
    else if (e.key === ' ') {
        e.preventDefault();
        togglePause();
    }
});

init();
