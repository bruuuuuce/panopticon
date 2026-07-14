# Creating dashboards

Panopticon dashboards are versionable JSON artifacts. A panel references a
data definition; the data definition references a datasource. Keeping these
three layers separate lets several panels and dashboards share the same query
and lets data retrieval remain independent from rendering.

```text
application.yml datasource -> data definition -> dashboard panel
```

This guide uses a JDBC example, then shows the dashboard JSON that renders
it. Put every real database behind a read-only account: the SQL guard is
defence in depth, not the security boundary.

## 1. Define a datasource

Datasources live in `src/main/resources/application.yml` under
`panopticon.datasources`. Add a production datasource through environment-
specific configuration rather than committing its password.

```yaml
panopticon:
  datasources:
    reporting:
      provider: jdbc
      display-name: "Reporting warehouse"
      driver-class-name: oracle.jdbc.OracleDriver
      jdbc-url: "jdbc:oracle:thin:@//db.example.com:1521/reporting"
      username: panopticon_report_ro
      password: "${REPORTING_DB_PASSWORD}"
      dialect: oracle
      read-only: true
      max-pool-size: 5
```

The built-in JDBC provider supports `generic`, `h2`, `oracle`, and `sqlite`
dialects. A datasource `provider` must match a registered provider type:
`jdbc` or `jira` in the bundled application.

## 2. Create a data definition

Create one JSON file per definition in `config/data/`. Its `id` is the stable
reference used by panels and its `provider`/`datasource` must match configured
values.

`config/data/kpi-open-orders.json`:

```json
{
  "id": "kpi-open-orders",
  "name": "Open order count",
  "provider": "jdbc",
  "datasource": "reporting",
  "sql": "SELECT COUNT(*) AS open_orders FROM orders WHERE status <> 'CLOSED'",
  "timeoutMs": 5000,
  "maxRows": 1,
  "cacheTtlSeconds": 30
}
```

For JDBC definitions:

- Only one `SELECT` or `WITH` statement is accepted.
- `timeoutMs` defaults to `10000`, `maxRows` to `1000`, and
  `cacheTtlSeconds` to `10`; set all three explicitly for production data.
- Column names are exposed to the frontend in lower case. Use lower-case
  field names in panel options, even if a database returns upper-case aliases.
- A cache TTL of `0` disables the result cache. Failures are cached too, which
  prevents an unavailable datasource being retried by every browser refresh.

For Jira definitions, use `provider: "jira"`, a Jira datasource, and one of
`issue-search`, `issue-count`, or `issue-count-by-field` as `operation`.
The bundled Jira provider is mocked; it does not call a real Jira server yet.

## 3. Create a dashboard and panel

Create one JSON file per dashboard in `config/dashboards/`.

`config/dashboards/order-monitoring.json`:

```json
{
  "id": "order-monitoring",
  "title": "Order Monitoring",
  "accentColor": "#3987e5",
  "gridColumns": 12,
  "rotation": { "enabled": true, "durationSeconds": 20 },
  "panels": [
    {
      "id": "open-orders",
      "title": "Open orders",
      "type": "stat",
      "dataRef": "kpi-open-orders",
      "grid": { "row": 1, "col": 1, "rowSpan": 1, "colSpan": 3 },
      "refresh": { "enabled": true, "intervalSeconds": 30 },
      "options": { "valueField": "open_orders", "format": "number" }
    }
  ]
}
```

`dataRef` is the current reference field. `queryRef` remains supported as a
fallback for existing configuration, but new dashboards should use `dataRef`.

The grid uses one-based row and column positions. A panel must remain within
`gridColumns`; invalid positions are rejected during validation.

## Panel types and options

| Type | Required options | Optional options |
|---|---|---|
| `stat` | `valueField` | `format` (`number` or `decimal`), `unit` |
| `table` | none | `columns` |
| `bar` | `xField`, `yField` | `seriesName` |
| `line` | `xField`, `yField` | `seriesName` |
| `donut` | `labelField`, `valueField` | none |

The options name fields returned by the data definition. A `table` without
`columns` displays every returned column.

## Add thresholds

Thresholds are optional and apply to any panel type. They are evaluated in
the browser each time fresh or cached data is rendered.

```json
"thresholds": [
  {
    "field": "open_orders",
    "label": "Open orders",
    "warning": 100,
    "critical": 250,
    "direction": "above"
  }
]
```

Use `direction: "above"` (the default) for values that are unhealthy when
large, and `"below"` for values such as availability or stock levels. At
least one of `warning` and `critical` is required.

The UI also offers adaptive thresholds for `stat` panels. They are based on a
recent in-memory value history, require no JSON configuration, and reset when
the application restarts.

## Validate and reload safely

With the application running, check files before applying them:

```bash
curl -X POST http://localhost:8080/api/config/validate
```

Apply valid changes without a process restart:

```bash
curl -X POST http://localhost:8080/api/config/reload
```

Reload is atomic: Panopticon first loads and validates every effective data
definition and dashboard. On failure it returns HTTP 400 and leaves the live
registries and cache untouched. On success it swaps both registries and clears
the result cache so changed definitions are fetched immediately.

## Authoring checklist

- Give datasource, data definition, dashboard, and panel IDs unique,
  stable names.
- Use reporting views and read-only database credentials.
- Keep result sets small with aggregation, a bounded time filter, and
  `maxRows`.
- Set a timeout and a cache TTL that fit the panel refresh interval.
- Refer to returned fields in lower case in `options` and `thresholds`.
- Call `/api/config/validate` before `/api/config/reload` or deployment.
