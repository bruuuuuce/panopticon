package com.panopticon.data.plan;

import com.panopticon.data.DataExecutionContext;
import com.panopticon.data.DataProvider;

/**
 * Optional capability a {@link DataProvider} may implement: producing a
 * best-effort execution plan for a data definition, on demand (never on the
 * hot execution path — see the "query stats" ops view). "Execution plan" is
 * fundamentally a SQL concept; providers with no such notion (e.g. Jira)
 * simply don't implement this interface, rather than the base
 * {@link DataProvider} contract carrying a method most providers can't
 * honor.
 */
public interface ExplainCapable {
    QueryPlanResult explain(DataExecutionContext context);
}
