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

**Use a genuinely read-only database account, and preferably point queries at
dedicated reporting views rather than raw tables.** The SQL guard (below) is a
basic safety net — it blocks obviously dangerous statements by keyword, but it
is not a complete SQL security system, and string-based checks can in
principle be worked around by SQL it doesn't anticipate. The guard is defense
in depth on top of real database permissions, never a substitute for them.

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

Only `SELECT`/`WITH` statements pass the read-only guard — see
[SQL read-only guard](#sql-read-only-guard) below for exactly what's rejected
and why it's not a substitute for real database permissions. Column aliases
are lower-cased by the engine before reaching the frontend, so panel `options`
can always refer to them in lower case regardless of how the underlying
database folds identifiers.

Query results are cached **by query id** for `cacheTtlSeconds` (default `10`,
`0` disables caching for that query). This is a backend concern, not a frontend
one: the cache key is the query id, so if two panels — even on different
dashboards — reference the same `queryRef`, a burst of concurrent refreshes
collapses into a single datasource hit. `generatedAt` in the response reflects
when the query actually ran, so the frontend always shows true data age even on
a cache hit. Set a lower `cacheTtlSeconds` (or `0`) for a query backing
something that must never lag, e.g. an alert-driving KPI.

**A failing query is cached too**, for the same TTL as a success. Without
this, a datasource outage would defeat the entire point of caching: every
viewer's refresh would re-hit the already-struggling datasource instead of
being collapsed the way a healthy query is. Every request during that window
gets the same error immediately (no repeated timeouts piling up), and the
next attempt after the TTL expires gets to try again for real.

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
      "type": "stat",
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

| Panel type | Required `options` keys | Optional |
|---|---|---|
| `stat` | `valueField` | `format` (`number`\|`decimal`), `unit` |
| `table` | — | `columns` (array; omit to show every column the query returns) |
| `bar` | `xField`, `yField` | `seriesName` |
| `line` | `xField`, `yField` | `seriesName` |
| `donut` | `labelField`, `valueField` | — |

`/api/config/validate` (and startup) reject a panel missing its type's required
`options` keys, or a `grid` position that's out of bounds for the dashboard's
`gridColumns`.

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
| GET | `/api/runtime/panels` | Last success/failure, duration, row count per panel |
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

`/api/runtime/panels` returns one entry per panel that has been requested at
least once since the process started:

```json
[
  {
    "dashboardId": "support-ops",
    "panelId": "kpi-open",
    "queryRef": "kpi-open-tickets",
    "lastSuccess": "2026-07-11T14:32:23.587Z",
    "lastFailure": null,
    "lastDurationMs": 7,
    "lastError": null,
    "rowCount": 1
  }
]
```

`lastSuccess`/`lastFailure` are independent and both "sticky": a success
doesn't clear a prior failure's `lastError`, and a failure doesn't clear
`lastDurationMs`/`rowCount` from the last successful run — so a panel that's
currently erroring still shows what it looked like when it last worked,
alongside what's wrong now.

## Project structure

```
src/main/java/com/panopticon/
├── model/       Dashboard/panel/query domain records
├── config/      Datasource + config-path properties, HikariCP + registry wiring
├── loader/      JSON config loading and cross-reference validation
├── registry/    In-memory lookups (dashboards, queries, datasources)
├── query/       QueryEngine (execute-by-id, timeout/maxRows, caching) + the read-only guard
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

## Query engine

`QueryEngine` (`com.panopticon.query`) is the single path any SQL execution goes
through — there is no endpoint that accepts SQL from the frontend, only
`GET /api/dashboards/{id}/panels/{panelId}/data`, which resolves the panel's
`queryRef` to a query id and calls `QueryEngine.execute(queryId)`. For every
execution it:

1. Resolves the query id via the `QueryRegistry` (404 `UnknownQueryException` if not found).
2. Runs the SQL guard (`SqlGuard.assertReadOnly`) — see below.
3. Serves from `QueryResultCache` if a fresh entry exists, otherwise runs the
   query with `JdbcTemplate`, enforcing `timeoutSeconds` (`Statement.setQueryTimeout`)
   and `maxRows` (`Statement.setMaxRows`, backstopped by an application-level cap
   in the row loop).
4. Returns a `QueryResult` carrying `columns` (name + JDBC type), `rows`,
   `generatedAt`, `executionTimeMs`, and `rowCount`.

Every panel data request also updates that panel's `PanelRuntimeState` in
`PanelRuntimeTracker` (`lastSuccess`/`lastFailure`/`lastDurationMs`/
`lastError`/`rowCount` — see the response shape above), exposed via
`GET /api/runtime/panels`. So both an individual panel's health (the
frontend's own per-panel status dot, driven by its own fetch) and the
overall fleet of panels (this endpoint, useful for ops/alerting) are
observable. Updates go through an atomic compute rather than read-then-write,
since multiple viewers can refresh the same panel at effectively the same time.

### SQL read-only guard

`SqlGuard` rejects any statement that isn't a single `SELECT`/`WITH`, and
separately rejects the keywords `insert`, `update`, `delete`, `merge`, `drop`,
`alter`, `truncate`, `grant`, `execute`, `call`, `begin`, `commit`, `rollback`
(plus a few more: `create`, `revoke`, `exec`) appearing anywhere in the
statement as SQL keywords — string literals and comments are stripped before
the keyword scan so they don't cause false positives/negatives.

**This is a basic safety layer, not a complete SQL security system.** It is a
keyword blacklist plus a `SELECT`/`WITH`-only allowlist, checked against
straightforwardly-formatted SQL — it is not a full parser and could in
principle be defeated by SQL it doesn't anticipate. Real deployments must
still connect through a genuinely **read-only database user**, and preferably
point queries at **dedicated reporting views** rather than raw tables, so that
even a guard bypass has nothing destructive or sensitive to reach. The guard
exists to catch authoring mistakes early and add defense in depth — never as a
substitute for real database permissions.

## Known limitations (by design, for this MVP)

- No hot config reload — `/api/config/validate` is dry-run only, restart to apply changes.
- No auth — this is meant to run inside a trusted network/VPN for an internal team.
