package com.panopticon.loader;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.panopticon.model.DashboardDefinition;
import com.panopticon.model.DataDefinition;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Reads dashboard-as-code JSON files from one or more locations, where each
 * location is either a single {@code .json} file or a directory of them.
 * Each file must contain exactly one definition; a file (not a whole
 * directory) is the unit of "a dashboard/data set" (keeps authoring simple:
 * one file per dashboard or data definition). Locations are processed in the
 * order given, and a directory's files in filename order - together this is
 * the order {@link com.panopticon.registry.DashboardRegistry} preserves for
 * monitor-mode rotation, so callers that care about rotation order should
 * pass locations in the order they want dashboards to appear.
 */
@Component
public class ConfigLoader {

    private final ObjectMapper objectMapper;

    public ConfigLoader(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public List<DataDefinition> loadDataDefinitions(List<String> locations) {
        return loadAll(locations, DataDefinition.class);
    }

    public List<DashboardDefinition> loadDashboards(List<String> locations) {
        return loadAll(locations, DashboardDefinition.class);
    }

    private <T> List<T> loadAll(List<String> locations, Class<T> type) {
        List<Path> files = resolveFiles(locations);
        List<T> result = new ArrayList<>();
        for (Path file : files) {
            try {
                result.add(objectMapper.readValue(file.toFile(), type));
            } catch (IOException e) {
                throw new ConfigLoadException(
                        "Failed to parse %s as %s: %s".formatted(file, type.getSimpleName(), e.getMessage()), e);
            }
        }
        return result;
    }

    /** Each location is either a directory (every *.json file inside it, filename order) or a
     *  single .json file (itself) - e.g. the --dashboards/--data startup arguments can mix both:
     *  {@code --dashboards=config/dashboards,extra/one-off-dashboard.json}. */
    private List<Path> resolveFiles(List<String> locations) {
        List<Path> files = new ArrayList<>();
        for (String location : locations) {
            Path path = Path.of(location);
            if (Files.isDirectory(path)) {
                files.addAll(listJsonFiles(path));
            } else if (Files.isRegularFile(path)) {
                files.add(path);
            } else {
                throw new ConfigLoadException(
                        "Config location does not exist (expected a .json file or a directory): " + path.toAbsolutePath());
            }
        }
        return files;
    }

    private List<Path> listJsonFiles(Path directory) {
        List<Path> files = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory, "*.json")) {
            stream.forEach(files::add);
        } catch (IOException e) {
            throw new ConfigLoadException("Failed to list " + directory.toAbsolutePath(), e);
        }
        files.sort(Comparator.comparing(Path::getFileName));
        return files;
    }
}
