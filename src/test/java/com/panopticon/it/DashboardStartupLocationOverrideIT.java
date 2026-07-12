package com.panopticon.it;

import com.panopticon.registry.DashboardRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end proof that the {@code --dashboards}/{@code --data} startup
 * arguments (see {@link com.panopticon.config.DashboardConfigLocations})
 * actually drive which dashboards load, against the app's real default
 * datasources (demo-h2/local-sqlite/demo-jira from application.yml) - not the
 * production-like SQLite fixtures the rest of this package uses. Uses
 * {@code @DynamicPropertySource} rather than literal {@code @SpringBootTest(args=...)}
 * only because the subset directory's path isn't known until test run time
 * (a real command line would just pass {@code --dashboards=<dir>,<file>}
 * directly); the property Spring ends up binding is identical either way.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class DashboardStartupLocationOverrideIT {

    @DynamicPropertySource
    static void overrideDashboardLocations(DynamicPropertyRegistry registry) {
        Path subsetDir = createSubsetDirWithTwoOfTheBundledDashboards();
        String standaloneFile = "config/dashboards/payments-monitoring.json";
        // A directory (2 dashboards) plus one standalone file, deliberately excluding the other
        // bundled dashboards (provider-showcase, query-performance, team-workload) - proves the
        // override actually narrows the set rather than just adding to the default.
        registry.add("dashboards", () -> subsetDir + "," + standaloneFile);
        // --data is left at its default (config/data): the two flags are independent knobs.
    }

    private static Path createSubsetDirWithTwoOfTheBundledDashboards() {
        try {
            Path dir = Files.createTempDirectory("panopticon-dashboards-subset-");
            Files.copy(Path.of("config/dashboards/db-overview.json"), dir.resolve("db-overview.json"), StandardCopyOption.REPLACE_EXISTING);
            Files.copy(Path.of("config/dashboards/support-ops.json"), dir.resolve("support-ops.json"), StandardCopyOption.REPLACE_EXISTING);
            return dir;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Autowired
    private DashboardRegistry dashboardRegistry;

    @Test
    void onlyTheDashboardsFromTheOverriddenLocationsLoad() {
        assertThat(dashboardRegistry.all()).extracting(d -> d.id())
                .containsExactlyInAnyOrder("db-overview", "support-ops", "payments-monitoring");
    }
}
