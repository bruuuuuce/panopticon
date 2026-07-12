package com.panopticon.it;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.panopticon.loader.ConfigLoadException;
import com.panopticon.loader.ConfigLoader;
import com.panopticon.model.DashboardDefinition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ConfigLoader.loadDashboards/loadDataDefinitions now take a list of
 * locations, each either a single .json file or a directory of them (backing
 * the --dashboards/--data startup arguments - see DashboardConfigLocations).
 * No Spring context needed: ConfigLoader only depends on an ObjectMapper.
 */
class ConfigLoaderMultiLocationTest {

    private final ConfigLoader loader = new ConfigLoader(new ObjectMapper());

    @Test
    void loadsFromASingleDirectory_filenameOrder(@TempDir Path dir) throws IOException {
        writeDashboard(dir.resolve("b.json"), "b");
        writeDashboard(dir.resolve("a.json"), "a");

        List<DashboardDefinition> loaded = loader.loadDashboards(List.of(dir.toString()));

        assertThat(loaded).extracting(DashboardDefinition::id).containsExactly("a", "b");
    }

    @Test
    void loadsFromASingleFile(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("only.json");
        writeDashboard(file, "only-one");

        List<DashboardDefinition> loaded = loader.loadDashboards(List.of(file.toString()));

        assertThat(loaded).extracting(DashboardDefinition::id).containsExactly("only-one");
    }

    @Test
    void mixesFilesAndDirectoriesInGivenOrder(@TempDir Path dir) throws IOException {
        Path subdir = Files.createDirectory(dir.resolve("subdir"));
        writeDashboard(subdir.resolve("x.json"), "from-dir-x");
        writeDashboard(subdir.resolve("y.json"), "from-dir-y");
        Path standalone = dir.resolve("standalone.json");
        writeDashboard(standalone, "standalone");

        // Directory listed first, then a standalone file - locations are processed in this order,
        // a directory's own files in filename order within that slot.
        List<DashboardDefinition> loaded = loader.loadDashboards(List.of(subdir.toString(), standalone.toString()));

        assertThat(loaded).extracting(DashboardDefinition::id)
                .containsExactly("from-dir-x", "from-dir-y", "standalone");
    }

    @Test
    void emptyDirectoryYieldsNoDashboardsWithoutError(@TempDir Path dir) throws IOException {
        Path emptyDir = Files.createDirectory(dir.resolve("empty"));

        List<DashboardDefinition> loaded = loader.loadDashboards(List.of(emptyDir.toString()));

        assertThat(loaded).isEmpty();
    }

    @Test
    void missingLocationFailsWithAClearError() {
        assertThatThrownBy(() -> loader.loadDashboards(List.of("this/path/does/not/exist")))
                .isInstanceOf(ConfigLoadException.class)
                .hasMessageContaining("this/path/does/not/exist");
    }

    @Test
    void oneMissingLocationAmongOthersStillFailsTheWholeLoad(@TempDir Path dir) throws IOException {
        Path good = dir.resolve("good.json");
        writeDashboard(good, "good");

        assertThatThrownBy(() -> loader.loadDashboards(List.of(good.toString(), "still/missing")))
                .isInstanceOf(ConfigLoadException.class);
    }

    private void writeDashboard(Path file, String id) throws IOException {
        Files.writeString(file, """
                {
                  "id": "%s",
                  "title": "%s",
                  "gridColumns": 12,
                  "panels": []
                }
                """.formatted(id, id));
    }
}
