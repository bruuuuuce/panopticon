package com.panopticon.config;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.nio.file.Path;

/**
 * Whether (and where) executed data results get recorded as JSON Lines by
 * {@link com.panopticon.data.recording.DataRecorder}. Off by default:
 * enable with {@code panopticon.recording.enabled: true} (directory from
 * {@code panopticon.recording.directory}, default {@code recordings/}), or
 * with the root-level {@code --recording=<dir>} startup flag, which both
 * enables recording and sets the directory in one short argument — the same
 * root-level Binder convention as {@link DashboardConfigLocations}, so it
 * also works as YAML ({@code recording: <dir>}) or an env var.
 */
@Component
public class RecordingSettings {

    private final boolean enabled;
    private final Path directory;

    @Autowired // disambiguates from the private test-factory constructor below
    public RecordingSettings(Environment environment) {
        String override = Binder.get(environment).bind("recording", String.class).orElse(null);
        if (override != null && !override.isBlank()) {
            this.enabled = true;
            this.directory = Path.of(override);
        } else {
            this.enabled = environment.getProperty("panopticon.recording.enabled", Boolean.class, false);
            this.directory = Path.of(environment.getProperty("panopticon.recording.directory", "recordings"));
        }
    }

    private RecordingSettings(boolean enabled, Path directory) {
        this.enabled = enabled;
        this.directory = directory;
    }

    public static RecordingSettings disabled() {
        return new RecordingSettings(false, Path.of("recordings"));
    }

    public static RecordingSettings enabledAt(Path directory) {
        return new RecordingSettings(true, directory);
    }

    public boolean enabled() {
        return enabled;
    }

    public Path directory() {
        return directory;
    }
}
