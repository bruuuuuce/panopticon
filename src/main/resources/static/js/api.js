/**
 * Thin fetch wrappers around the Panopticon REST API. Every call throws a
 * plain object ({error, message}) on non-2xx so callers can render the
 * ApiError body the backend returns instead of a generic network error.
 */
const Api = (() => {
    async function request(url, options) {
        const response = await fetch(url, options);
        if (!response.ok) {
            let body;
            try {
                body = await response.json();
            } catch {
                body = { error: 'http_error', message: response.statusText };
            }
            throw body;
        }
        return response.json();
    }

    return {
        listDashboards: () => request('/api/dashboards'),
        getDashboard: (dashboardId) => request(`/api/dashboards/${encodeURIComponent(dashboardId)}`),
        getPanelData: (dashboardId, panelId) =>
            request(`/api/dashboards/${encodeURIComponent(dashboardId)}/panels/${encodeURIComponent(panelId)}/data`),
        getRuntimePanels: () => request('/api/runtime/panels'),
    };
})();
