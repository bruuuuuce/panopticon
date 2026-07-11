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
 * Reads dashboard-as-code JSON files from a directory. Each file must
 * contain exactly one definition; the directory is the unit of "a
 * dashboard/data set", not the file (keeps authoring simple: one file per
 * dashboard or data definition).
 */
@Component
public class ConfigLoader {

    private final ObjectMapper objectMapper;

    public ConfigLoader(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public List<DataDefinition> loadDataDefinitions(Path directory) {
        return loadAll(directory, DataDefinition.class);
    }

    public List<DashboardDefinition> loadDashboards(Path directory) {
        return loadAll(directory, DashboardDefinition.class);
    }

    private <T> List<T> loadAll(Path directory, Class<T> type) {
        List<Path> files = listJsonFiles(directory);
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

    private List<Path> listJsonFiles(Path directory) {
        if (!Files.isDirectory(directory)) {
            throw new ConfigLoadException("Config directory does not exist: " + directory.toAbsolutePath());
        }
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
