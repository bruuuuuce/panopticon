/**
 * Theme switching. Three modes, cycled by the header's theme button and
 * persisted in localStorage: 'auto' (the default — light during the day,
 * dark otherwise, re-checked on an interval so a wall display spanning the
 * day/night boundary switches without a reload), or a manual 'light'/'dark'
 * override for users whose room disagrees with the clock.
 *
 * Loaded as a plain blocking script (not `type="module"`) and placed before
 * the CSS `<link>` in <head>, so `data-theme` is set on <html> before the
 * stylesheet is applied — no flash of the wrong theme on load. Chart colors
 * (baked into canvas draw calls by ECharts, not live CSS) are re-rendered by
 * dashboard.js on the `panopticon:theme-changed` event.
 */
(function () {
    var DAY_START_HOUR = 7;  // 07:00 local time: light theme starts
    var DAY_END_HOUR = 19;   // 19:00 local time: dark theme starts
    var CHECK_INTERVAL_MS = 60_000;
    var STORAGE_KEY = 'panopticon-theme-mode';
    var MODES = ['auto', 'light', 'dark'];

    function mode() {
        try {
            var stored = localStorage.getItem(STORAGE_KEY);
            return MODES.indexOf(stored) >= 0 ? stored : 'auto';
        } catch (e) {
            return 'auto'; // storage unavailable (private mode, file://) — auto only
        }
    }

    function timeOfDayTheme() {
        var hour = new Date().getHours();
        return (hour >= DAY_START_HOUR && hour < DAY_END_HOUR) ? 'light' : 'dark';
    }

    function computeTheme() {
        var m = mode();
        return m === 'auto' ? timeOfDayTheme() : m;
    }

    function applyTheme() {
        var theme = computeTheme();
        var root = document.documentElement;
        if (root.getAttribute('data-theme') !== theme) {
            root.setAttribute('data-theme', theme);
            window.dispatchEvent(new CustomEvent('panopticon:theme-changed', { detail: { theme: theme } }));
        }
    }

    function cycleMode() {
        var next = MODES[(MODES.indexOf(mode()) + 1) % MODES.length];
        try {
            localStorage.setItem(STORAGE_KEY, next);
        } catch (e) {
            // storage unavailable: the click still works for this page's lifetime? No -
            // mode() can't read it back, so silently staying on 'auto' is the honest outcome.
        }
        applyTheme();
        updateButtons();
    }

    function updateButtons() {
        var m = mode();
        var label = m === 'auto' ? 'Theme: auto' : m === 'light' ? 'Theme: light' : 'Theme: dark';
        document.querySelectorAll('.theme-toggle').forEach(function (btn) {
            btn.textContent = label;
            btn.title = 'Cycle theme (auto → light → dark). Auto follows time of day.';
        });
    }

    function wireButtons() {
        document.querySelectorAll('.theme-toggle').forEach(function (btn) {
            btn.addEventListener('click', cycleMode);
        });
        updateButtons();
    }

    applyTheme();
    setInterval(applyTheme, CHECK_INTERVAL_MS);
    // This script runs in <head>, before the buttons exist in the DOM.
    document.addEventListener('DOMContentLoaded', wireButtons);
})();
