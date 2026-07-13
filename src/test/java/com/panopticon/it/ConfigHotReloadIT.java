package com.panopticon.it;

import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * POST /api/config/reload completes the dashboard-as-code loop: edit JSON on
 * disk, reload, see it live - no restart. The suite boots against a mutable
 * temp copy of one bundled dashboard (the shared it-config tree must never
 * be mutated), then adds a file, reloads, and finally proves an invalid
 * on-disk config is rejected with 400 while the running config survives
 * untouched. Ordered methods: each step builds on the previous one's disk
 * state against the single shared context.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ConfigHotReloadIT {

    private static final Path dashboardsDir = createMutableDashboardsDir();

    @DynamicPropertySource
    static void overrideDashboardLocations(DynamicPropertyRegistry registry) {
        registry.add("dashboards", dashboardsDir::toString);
        // --data left at its default (config/data), same as production.

        // This context and DashboardStartupLocationOverrideIT/DatasourceIdentificationIT
        // all boot the real application.yml datasources in the same JVM. Their named
        // in-memory databases (h2:mem:panopticon, sqlite shared-cache) would otherwise
        // be SHARED between the cached contexts, and the seed scripts would collide
        // with each other's rows - so every such context gets its own database names.
        registry.add("panopticon.datasources.demo-h2.jdbc-url",
                () -> "jdbc:h2:mem:panopticon-reload-it;DB_CLOSE_DELAY=-1");
        registry.add("panopticon.datasources.local-sqlite.jdbc-url",
                () -> "jdbc:sqlite:file:reload-it?mode=memory&cache=shared");
        registry.add("panopticon.datasources.secondary-sqlite.jdbc-url",
                () -> "jdbc:sqlite:file:reload-it-secondary?mode=memory&cache=shared");
    }

    private static Path createMutableDashboardsDir() {
        try {
            Path dir = Files.createTempDirectory("panopticon-reload-dashboards-");
            Files.copy(Path.of("config/dashboards/db-overview.json"), dir.resolve("db-overview.json"),
                    StandardCopyOption.REPLACE_EXISTING);
            return dir;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Autowired
    private TestRestTemplate rest;

    @Test
    @Order(1)
    void startsWithJustTheInitiallyPresentDashboard() {
        assertThat(rest.getForEntity("/api/dashboards/db-overview", String.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(rest.getForEntity("/api/dashboards/support-ops", String.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @Order(2)
    void reloadPicksUpADashboardAddedOnDisk() throws IOException {
        Files.copy(Path.of("config/dashboards/support-ops.json"), dashboardsDir.resolve("support-ops.json"),
                StandardCopyOption.REPLACE_EXISTING);

        ResponseEntity<String> reload = rest.postForEntity("/api/config/reload", null, String.class);
        assertThat(reload.getStatusCode()).isEqualTo(HttpStatus.OK);

        assertThat(rest.getForEntity("/api/dashboards/support-ops", String.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    @Order(3)
    void invalidConfigOnDiskIsRejectedWith400AndTheRunningConfigSurvives() throws IOException {
        Path broken = dashboardsDir.resolve("broken.json");
        Files.writeString(broken,
                """
                {"id": "broken", "title": "Broken", "panels": [
                  {"id": "p1", "title": "P", "type": "stat", "dataRef": "no-such-data-id",
                   "grid": {"row": 1, "col": 1, "rowSpan": 1, "colSpan": 3},
                   "options": {"valueField": "x"}}
                ]}
                """,
                StandardCharsets.UTF_8);
        try {
            ResponseEntity<String> reload = rest.postForEntity("/api/config/reload", null, String.class);
            assertThat(reload.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(reload.getBody()).contains("no-such-data-id");

            // Nothing was swapped: both previously loaded dashboards still serve, the broken one doesn't exist.
            assertThat(rest.getForEntity("/api/dashboards/db-overview", String.class).getStatusCode())
                    .isEqualTo(HttpStatus.OK);
            assertThat(rest.getForEntity("/api/dashboards/support-ops", String.class).getStatusCode())
                    .isEqualTo(HttpStatus.OK);
            assertThat(rest.getForEntity("/api/dashboards/broken", String.class).getStatusCode())
                    .isEqualTo(HttpStatus.NOT_FOUND);
        } finally {
            Files.deleteIfExists(broken);
        }
    }
}
