# Panopticon

Panopticon is a self-contained **operational dashboard-as-code** tool for AM/support
teams, not a generic BI tool: dashboards, panels and queries are JSON-defined
artifacts, not user-built reports. No JPA/Hibernate, no server-side chart
rendering, no frontend framework/build step — a plain Spring Boot MVC app serving
static assets, backed by JdbcTemplate + HikariCP.

The split is deliberate and enforced by what each layer is allowed to know:

| Backend owns | Frontend owns |
|---|---|
| Dashboard definitions | Rendering |
| Query catalog | Layout |
| Datasource registry | Chart visualization (ECharts) |
| SQL execution | Refresh polling |
| Validation | Monitor rotation UX |
| Runtime state | |
| Result caching | |

The frontend never talks SQL or datasources — it only ever calls
`GET /api/dashboards/{id}/panels/{panelId}/data` and renders whatever tabular
JSON comes back. All query/dashboard/datasource logic, and the query result
cache, live entirely in the backend.

## Requirements

- Java 21
- Maven 3.9+

## Run it

```bash
mvn spring-boot:run
```

or build a jar and run it directly:

```bash
mvn clean package
java -jar target/panopticon.jar
```

The app starts on **http://localhost:8080** (override with `--server.port=<port>`
if that's taken). No external database is required — it ships with an in-memory
H2 "mock" datasource, seeded with ~80 synthetic support tickets on startup, so the
two sample dashboards work out of the box:

- `http://localhost:8080/` — dashboard picker + live panels
- `http://localhost:8080/monitor.html` — fullscreen rotation between dashboards

## Configuration model

Three things are configured independently, and wired together by reference —
SQL is never duplicated inside a dashboard/panel definition:

| What | Where | Referenced by |
|---|---|---|
| Datasources | `application.yml` (`panopticon.datasources.*`) | `QueryDefinition.datasource` |
| Queries | `config/queries/*.json` (one query per file) | `PanelDefinition.queryRef` |
| Dashboards | `config/dashboards/*.json` (one dashboard per file) | — |

Both `config/queries` and `config/dashboards` directories are read from disk (not
the classpath) at startup — paths are configurable via
`panopticon.config.dashboards-path` / `panopticon.config.queries-path`, relative
to the working directory by default. **If the config is invalid the app fails to
start** (unknown `queryRef`, unknown datasource, duplicate ids, rejected SQL,
etc.) rather than starting half-broken; the startup log names the problem.

### Adding a datasource

Add an entry under `panopticon.datasources` in `application.yml`. Only the `mock`
H2 datasource uses `init-schema`/`init-data` (they populate the in-memory demo
schema once at startup) — a real datasource just needs the JDBC connection info:

```yaml
panopticon:
  datasources:
    reporting:
      driver-class-name: oracle.jdbc.OracleDriver
      url: "jdbc:oracle:thin:@//host:1521/service"
      username: report_ro
      password: "${REPORTING_DB_PASSWORD}"
      max-pool-size: 10
```

Use a **read-only** database account. The SQL guard (below) is defense in depth,
not a substitute for real permissions.

### Adding a query

One JSON file per query under `config/queries/`:

```json
{
  "id": "kpi-open-tickets",
  "name": "Open ticket count",
  "datasource": "mock",
  "sql": "SELECT COUNT(*) AS open_tickets FROM tickets WHERE status <> 'CLOSED'",
  "timeoutSeconds": 5,
  "maxRows": 1,
  "cacheTtlSeconds": 10
}
```

`timeoutSeconds` (default `10`), `maxRows` (default `1000`) and
`cacheTtlSeconds` (default `10`) are all optional.

Only `SELECT`/`WITH` statements are allowed, and only a single statement. The
guard also rejects `insert`, `update`, `delete`, `merge`, `drop`, `alter`,
`truncate`, `create`, `grant`, `revoke`, `execute`/`exec`, `call`, `begin`,
`commit`, `rollback` appearing as SQL keywords anywhere in the statement (string
literals and comments are excluded from the check). Column aliases are lower-cased
by the engine before reaching the frontend, so panel `options` can always refer to
them in lower case regardless of how the underlying database folds identifiers.

Query results are cached **by query id** for `cacheTtlSeconds` (default `10`,
`0` disables caching for that query). This is a backend concern, not a frontend
one: the cache key is the query id, so if two panels — even on different
dashboards — reference the same `queryRef`, a burst of concurrent refreshes
collapses into a single datasource hit. `generatedAt` in the response reflects
when the query actually ran, so the frontend always shows true data age even on
a cache hit. Set a lower `cacheTtlSeconds` (or `0`) for a query backing
something that must never lag, e.g. an alert-driving KPI.

### Adding a dashboard

One JSON file per dashboard under `config/dashboards/`. `grid`/`gridColumns` follow
CSS Grid's 1-based row/column numbering; `refresh` controls how often the frontend
re-fetches that panel's data:

```json
{
  "id": "support-ops",
  "title": "Support Operations Overview",
  "gridColumns": 12,
  "rotation": { "durationSeconds": 20, "enabled": true },
  "panels": [
    {
      "id": "kpi-open",
      "title": "Open Tickets",
      "type": "KPI",
      "queryRef": "kpi-open-tickets",
      "grid": { "row": 1, "col": 1, "rowSpan": 1, "colSpan": 3 },
      "refresh": { "intervalSeconds": 30, "enabled": true },
      "options": { "valueField": "open_tickets", "format": "number" }
    }
  ]
}
```

`options` is deliberately a loose map — it's the one place panel-type-specific
rendering hints live, since each chart type only needs 2-3 fields:

| Panel type | Relevant `options` keys |
|---|---|
| `KPI` | `valueField`, `format` (`number`\|`decimal`), `unit` |
| `TABLE` | `columns` (array; omit to show every column the query returns) |
| `BAR_CHART` | `xField`, `yField`, `seriesName` |
| `LINE_CHART` | `xField`, `yField`, `seriesName` |
| `DONUT_CHART` | `labelField`, `valueField` |

Validate a config edit without restarting the app:

```bash
curl -X POST http://localhost:8080/api/config/validate
```

This is a **dry run** — it re-reads `config/` from disk and reports errors, but
does not hot-swap the running dashboards/queries (restart the app to pick up
changes). It exists for authoring feedback, not live reload.

## REST API

| Method | Path | Purpose |
|---|---|---|
| GET | `/api/dashboards` | List dashboards (summary: id/title/description/panel count) |
| GET | `/api/dashboards/{id}` | Full dashboard definition (panels, grid, refresh policy) |
| GET | `/api/dashboards/{id}/panels/{panelId}/data` | Execute the panel's query, return tabular JSON |
| GET | `/api/runtime/panels` | Last refresh outcome (status/time/duration) per panel |
| POST | `/api/config/validate` | Dry-run validation of `config/` as it sits on disk |

Panel data responses are shaped as:

```json
{
  "columns": [{ "name": "priority", "type": "CHARACTER VARYING" }],
  "rows": [{ "priority": "HIGH" }],
  "generatedAt": "2026-07-10T21:08:33.118Z",
  "executionTimeMs": 7,
  "rowCount": 4
}
```

## Project structure

```
src/main/java/com/panopticon/
├── model/       Dashboard/panel/query domain records
├── config/      Datasource + config-path properties, HikariCP + registry wiring
├── loader/      JSON config loading and cross-reference validation
├── registry/    In-memory lookups (dashboards, queries, datasources)
├── query/       SQL execution + the read-only guard
├── runtime/     Per-panel refresh-health tracking
└── api/         REST controllers + error handling

src/main/resources/
├── static/      Frontend (index.html, monitor.html, css/, js/)
└── db/mock/     H2 demo schema + seed data

config/
├── dashboards/  Dashboard JSON (sample: support-ops, team-workload)
└── queries/     Query JSON (sample: 9 queries against the mock H2 schema)
```

## Frontend

Plain HTML/CSS/JS (no build step) served from `static/`, using
[Apache ECharts](https://echarts.apache.org/) (vendored locally in
`static/js/vendor/`, no CDN dependency) for bar/line/donut charts and CSS Grid for
layout. `js/dashboard.js` is the shared rendering engine used by both the picker
page and monitor mode; each panel fetches and re-renders independently on its own
`refresh.intervalSeconds`, with a status dot (ok/error/pending) and a relative
"updated Xs ago" timestamp per panel.

Monitor mode (`/monitor.html`) rotates through dashboards whose
`rotation.enabled` is true, showing each for its own `rotation.durationSeconds`,
with pause/prev/next controls (also bindable via space/←/→) and a progress bar.

## Known limitations (by design, for this MVP)

- No hot config reload — `/api/config/validate` is dry-run only, restart to apply changes.
- No auth — this is meant to run inside a trusted network/VPN for an internal team.
- The SQL guard is a keyword blacklist plus a `SELECT`/`WITH`-only allowlist; it
  is defense in depth, not a substitute for read-only DB credentials.
