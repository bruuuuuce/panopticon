/**
 * Pure evaluation of a panel's fixed alerting thresholds (see
 * PanelDefinition.thresholds on the backend) against its latest fetched
 * data. No DOM, no fetch — dashboard.js calls this after every successful
 * refresh and feeds the result into rendering (dot color, KPI color, table
 * cell highlighting) and into the alerts bell aggregator.
 */

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
                breaches.push({ field: rule.field, label: rule.label || rule.field, rowIndex, value, level });
            }
        });
    }
    return breaches;
}

/** Worst level across a list of breaches, or null if there are none. */
export function worstLevel(breaches) {
    let worst = null;
    for (const b of breaches) {
        if (!worst || LEVEL_RANK[b.level] > LEVEL_RANK[worst]) worst = b.level;
    }
    return worst;
}
