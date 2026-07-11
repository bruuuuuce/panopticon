/**
 * Time-of-day theme switch: light during the day, dark otherwise. Loaded as
 * a plain blocking script (not `type="module"`) and placed before the CSS
 * `<link>` in <head>, so `data-theme` is set on <html> before the
 * stylesheet is applied — no flash of the wrong theme on load.
 *
 * Re-checks on an interval so a session that spans the day/night boundary
 * (monitor mode is meant to run for hours on a wall display) switches
 * without a page reload. Chart colors (baked into canvas draw calls by
 * ECharts, not live CSS) catch up on each panel's next data refresh rather
 * than instantly — see charts.js.
 */
(function () {
    var DAY_START_HOUR = 7;  // 07:00 local time: light theme starts
    var DAY_END_HOUR = 19;   // 19:00 local time: dark theme starts
    var CHECK_INTERVAL_MS = 60_000;

    function computeTheme() {
        var hour = new Date().getHours();
        return (hour >= DAY_START_HOUR && hour < DAY_END_HOUR) ? 'light' : 'dark';
    }

    function applyTheme() {
        var theme = computeTheme();
        var root = document.documentElement;
        if (root.getAttribute('data-theme') !== theme) {
            root.setAttribute('data-theme', theme);
            window.dispatchEvent(new CustomEvent('panopticon:theme-changed', { detail: { theme: theme } }));
        }
    }

    applyTheme();
    setInterval(applyTheme, CHECK_INTERVAL_MS);
})();
