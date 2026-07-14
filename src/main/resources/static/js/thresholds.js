/**
 * Pure evaluation of a panel's alerting rules against its latest fetched
 * data — both the fixed, JSON-declared bounds (PanelDefinition.thresholds)
 * and, since v2, adaptive/statistical anomaly detection driven by the
 * backend-tracked history attached as DataResult.adaptiveBaseline (see
 * AdaptiveThresholdTracker). No DOM, no fetch — dashboard.js calls these
 * after every successful refresh and feeds the result into rendering (dot
 * color, KPI color, table cell highlighting) and into the alerts bell
 * aggregator, which is exactly why both evaluators return breaches in the
 * same shape.
 */

const ADAPTIVE_WARNING_SIGMA = 2;
const ADAPTIVE_CRITICAL_SIGMA = 3;

const LEVEL_RANK = { warning: 1, critical: 2 };

function levelFor(rule, value) {
    if (rule.direction === 'below') {
        if (rule.critical !== null && rule.critical !== undefined && value <= rule.critical) return 'critical';
        if (rule.warning !== null && rule.warning !== undefined && value <= rule.warning) return 'warning';
    } else {
        if (rule.critical !== null && rule.critical !== undefined && value >= rule.critical) return 'critical';
        if (rule.warning !== null && rule.warning !== undefined && value >= rule.warning) return 'warning';
    }
    return null;
}

/**
 * Evaluates every rule in `panel.thresholds` against every row of `result.rows`.
 * Non-numeric or missing values are silently skipped, same defensive spirit as
 * renderKpi's value formatting. Returns a flat list of breaches, one per
 * (rule, row) pair that crossed a bound: { field, label, rowIndex, value, level }.
 */
export function evaluateThresholds(panel, result) {
    const rules = (panel && panel.thresholds) || [];
    if (rules.length === 0 || !result || !result.rows) return [];

    const breaches = [];
    for (const rule of rules) {
        result.rows.forEach((row, rowIndex) => {
            const raw = row[rule.field];
            if (raw === null || raw === undefined || raw === '') return;
            const value = Number(raw);
            if (Number.isNaN(value)) return;
            const level = levelFor(rule, value);
            if (level) {
                breaches.push({ field: rule.field, label: rule.label || rule.field, rowIndex, value, level, source: 'fixed' });
            }
        });
    }
    return breaches;
}

/**
 * Two-sided z-score anomaly check for 'stat' panels: flags a value that's
 * unusually far from its own recent mean, in EITHER direction — unlike fixed
 * thresholds there's no declared "high is bad"/"low is bad" direction here
 * (nothing in the JSON says so), so "unusual" itself is the signal. Requires
 * `result.adaptiveBaseline` (null until the backend has recorded enough
 * samples, or if this isn't a 'stat' panel) and a non-zero stddev — a
 * perfectly constant series never breaches, since every value IS the mean.
 */
export function evaluateAdaptive(panel, result) {
    if (!panel || panel.type !== 'stat' || !result || !result.adaptiveBaseline) return [];
    const opts = panel.options || {};
    const field = opts.valueField;
    if (!field) return [];

    const row = (result.rows && result.rows[0]) || {};
    const raw = row[field];
    if (raw === null || raw === undefined || raw === '') return [];
    const value = Number(raw);
    if (Number.isNaN(value)) return [];

    const { mean, stddev } = result.adaptiveBaseline;
    if (!stddev || stddev <= 0) return [];

    const z = (value - mean) / stddev;
    const absZ = Math.abs(z);
    const level = absZ >= ADAPTIVE_CRITICAL_SIGMA ? 'critical' : absZ >= ADAPTIVE_WARNING_SIGMA ? 'warning' : null;
    if (!level) return [];

    return [{ field, label: `${field} (adaptive)`, rowIndex: 0, value, level, source: 'adaptive', mean, stddev, z }];
}

/** Worst level across a list of breaches, or null if there are none. */
export function worstLevel(breaches) {
    let worst = null;
    for (const b of breaches) {
        if (!worst || LEVEL_RANK[b.level] > LEVEL_RANK[worst]) worst = b.level;
    }
    return worst;
}
