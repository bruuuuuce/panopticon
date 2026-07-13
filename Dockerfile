# Build stage: full JDK + Maven. Tests are skipped here on purpose - the
# image build should be reproducible packaging, not the CI gate (CI runs
# `mvn verify`, see .github/workflows/ci.yml).
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B -q package -DskipTests

# Runtime stage: JRE only.
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /build/target/panopticon.jar panopticon.jar
# Dashboard-as-code config is mounted, not baked in: -v ./config:/app/config
# (or point elsewhere with --dashboards/--data). Recordings, if enabled with
# --recording=/app/recordings, belong on a volume too.
COPY config ./config
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "panopticon.jar"]
