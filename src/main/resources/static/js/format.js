/**
 * Small formatting helpers shared by every panel/table renderer
 * (dashboard.js, query-stats.js) so escaping/formatting rules stay
 * consistent wherever tabular data is rendered.
 */
export function humanize(fieldName) {
    return fieldName.replace(/_/g, ' ').replace(/\b\w/g, (c) => c.toUpperCase());
}

export function escapeHtml(value) {
    const div = document.createElement('div');
    div.textContent = value === null || value === undefined ? '' : String(value);
    return div.innerHTML;
}

export function formatCell(value) {
    if (value === null || value === undefined) return '<span style="opacity:.4">—</span>';
    if (typeof value === 'string' && /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}/.test(value)) {
        const d = new Date(value);
        if (!isNaN(d.getTime())) return escapeHtml(d.toLocaleString());
    }
    return escapeHtml(value);
}

export function formatRelativeTime(date) {
    if (!date) return '—';
    const seconds = Math.max(0, Math.round((Date.now() - date.getTime()) / 1000));
    if (seconds < 5) return 'just now';
    if (seconds < 60) return `${seconds}s ago`;
    const minutes = Math.round(seconds / 60);
    if (minutes < 60) return `${minutes}m ago`;
    const hours = Math.round(minutes / 60);
    return `${hours}h ago`;
}
