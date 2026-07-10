/**
 * Monitor (rotation) mode: cycles through dashboards whose RotationPolicy
 * has enabled=true, one at a time, for that dashboard's own
 * rotation.durationSeconds, with pause/prev/next controls.
 */
(function () {
    const gridEl = document.getElementById('panel-grid');
    const titleEl = document.getElementById('current-dashboard-title');
    const progressBar = document.getElementById('progress-bar');
    const pauseBtn = document.getElementById('btn-pause');
    const prevBtn = document.getElementById('btn-prev');
    const nextBtn = document.getElementById('btn-next');

    const TICK_MS = 200;

    let dashboards = [];
    let index = 0;
    let controller = null;
    let paused = false;
    let tickTimer = null;
    let elapsedMs = 0;
    let durationMs = 20000;

    async function init() {
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
        try {
            const dashboard = await Api.getDashboard(summary.id);
            titleEl.textContent = dashboard.title;
            durationMs = ((dashboard.rotation && dashboard.rotation.durationSeconds) || 20) * 1000;
            elapsedMs = 0;
            progressBar.style.width = '0%';
            if (controller) controller.destroy();
            controller = Dashboard.mount(gridEl, dashboard);
        } catch (err) {
            gridEl.innerHTML = `<div class="panel-error" style="padding:20px">${(err && err.message) || 'Failed to load dashboard'}</div>`;
        }
    }

    function startTicking() {
        if (tickTimer) clearInterval(tickTimer);
        tickTimer = setInterval(() => {
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
    }

    nextBtn.addEventListener('click', goNext);
    prevBtn.addEventListener('click', goPrev);
    pauseBtn.addEventListener('click', togglePause);

    document.addEventListener('keydown', (e) => {
        if (e.key === 'ArrowRight') goNext();
        else if (e.key === 'ArrowLeft') goPrev();
        else if (e.key === ' ') {
            e.preventDefault();
            togglePause();
        }
    });

    init();
})();
