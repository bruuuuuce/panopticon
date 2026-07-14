/**
 * The global "Adaptive" toggle: on/off, persisted in localStorage, same
 * pattern as theme.js's mode persistence but binary instead of 3-way.
 * dashboard.js reads `isEnabled()` on every panel refresh; flipping the
 * toggle dispatches `panopticon:adaptive-mode-changed` so already-mounted
 * panels re-evaluate immediately from their cached last result, without
 * waiting for the next poll (same event-driven re-render dashboard.js
 * already does for `panopticon:theme-changed`).
 */
const STORAGE_KEY = 'panopticon-adaptive-mode';

function isEnabled() {
    try {
        return localStorage.getItem(STORAGE_KEY) === 'on';
    } catch (e) {
        return false; // storage unavailable (private mode, file://) — adaptive stays off
    }
}

function setEnabled(enabled) {
    try {
        localStorage.setItem(STORAGE_KEY, enabled ? 'on' : 'off');
    } catch (e) {
        // storage unavailable: the click still works for this page's lifetime via
        // updateButtons()/the dispatched event, it just won't survive a reload.
    }
}

function updateButtons() {
    const enabled = isEnabled();
    document.querySelectorAll('.adaptive-toggle').forEach((btn) => {
        btn.textContent = `⚡ Adaptive: ${enabled ? 'on' : 'off'}`;
        btn.classList.toggle('active', enabled);
        btn.title = 'Toggle statistical anomaly detection on stat panels (mean ± stddev of recent history).';
    });
}

function toggle() {
    setEnabled(!isEnabled());
    updateButtons();
    window.dispatchEvent(new CustomEvent('panopticon:adaptive-mode-changed', { detail: { enabled: isEnabled() } }));
}

function init() {
    document.querySelectorAll('.adaptive-toggle').forEach((btn) => btn.addEventListener('click', toggle));
    updateButtons();
}

export const Adaptive = { init, isEnabled };
