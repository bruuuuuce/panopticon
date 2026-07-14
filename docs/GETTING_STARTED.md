# Getting started

This guide starts Panopticon locally with the sample dashboards that are
included in the repository. No external database is needed: the application
initializes its demo H2 and SQLite databases and uses a mocked Jira provider.

## Prerequisites

- Java 21 or newer
- Maven 3.9 or newer
- Docker (optional)

Confirm that Maven, not only your shell, is using Java 21:

```bash
mvn -version
```

If it reports an older JVM, set `JAVA_HOME` to a Java 21 installation before
running Maven. For example, with SDKMAN:

```bash
sdk use java 21.0.11-amzn
export JAVA_HOME="$HOME/.sdkman/candidates/java/21.0.11-amzn"
```

## Run locally

From the repository root:

```bash
mvn spring-boot:run
```

Open these pages once startup completes:

- `http://localhost:8080/` — dashboard picker and live dashboards
- `http://localhost:8080/monitor.html` — full-screen rotating monitor mode
- `http://localhost:8080/query-stats.html` — execution and cache statistics
- `http://localhost:8080/actuator/health` — health endpoint

To build and run the executable JAR instead:

```bash
mvn clean package
java -jar target/panopticon.jar
```

Use another port when `8080` is already occupied:

```bash
java -jar target/panopticon.jar --server.port=8081
```

## Run with Docker

```bash
docker build -t panopticon .
docker run --rm -p 8080:8080 -v ./config:/app/config panopticon
```

The bind mount makes the local `config/` directory the running dashboard
configuration. Keep production credentials out of this repository; provide
them through your deployment environment and use read-only database users.

## Run the tests

```bash
mvn test
mvn verify
```

`mvn test` runs the unit suite. `mvn verify` also runs the integration suite,
which starts Spring Boot with temporary SQLite databases and exercises reload,
recording, caching, fault handling, and concurrent traffic.

## Configuration locations

By default, Panopticon loads:

```text
config/dashboards/*.json
config/data/*.json
```

Point a deployment at different directories or individual JSON files with
comma-separated locations:

```bash
java -jar target/panopticon.jar \
  --dashboards=/srv/panopticon/dashboards \
  --data=/srv/panopticon/data,extra/one-off-data.json
```

Validate a configuration edit before applying it to a running instance:

```bash
curl -X POST http://localhost:8080/api/config/validate
curl -X POST http://localhost:8080/api/config/reload
```

`reload` changes the active dashboard/data registries only if the complete
configuration is valid. See [Creating dashboards](CREATING_DASHBOARDS.md) for
the authoring workflow and field reference.

## Production checklist

- Put the service behind the authentication and network controls appropriate
  for your organization. Panopticon does not include application-level auth.
- Configure every real JDBC datasource with a dedicated read-only user and
  `read-only: true`.
- Give each data definition deliberate `timeoutMs` and `maxRows` limits.
- Mount dashboard configuration and, if enabled, recordings as durable
  volumes.
- Monitor `/actuator/health` and the `panopticon.data.execution` and
  `panopticon.cache` metrics.
