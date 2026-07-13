package com.panopticon.data.plan;

import com.panopticon.data.jdbc.SqlDialect;

import java.util.List;
import java.util.Map;

/**
 * Best-effort execution plan for a data definition, captured on demand (see
 * {@link ExplainCapable}). {@code planRows} is the raw, dialect-shaped
 * {@code EXPLAIN} output (one row per plan step) so a viewer can see exactly
 * what the driver said; {@code steps} is that same output parsed into
 * structured access paths (table/index touched, scan vs. search) — the
 * "complexity statistics" this view exists to surface, since raw plan text
 * alone (especially H2's, which is just the rewritten SQL with comments)
 * isn't self-explanatory. {@code warnings} are simple, algorithmic heuristics
 * derived from {@code steps} (e.g. a detected full table scan) — not a query
 * optimizer, just a first pointer at where to look.
 */
public record QueryPlanResult(
        SqlDialect dialect,
        boolean supported,
        List<Map<String, Object>> planRows,
        List<PlanStep> steps,
        List<String> warnings,
        String unsupportedReason
) {
    public static QueryPlanResult unsupported(SqlDialect dialect, String reason) {
        return new QueryPlanResult(dialect, false, List.of(), List.of(), List.of(), reason);
    }

    public static QueryPlanResult of(SqlDialect dialect, List<Map<String, Object>> planRows, List<PlanStep> steps, List<String> warnings) {
        return new QueryPlanResult(dialect, true, planRows, steps, warnings, null);
    }
}
