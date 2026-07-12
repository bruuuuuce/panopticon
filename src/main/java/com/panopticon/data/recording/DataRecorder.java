package com.panopticon.data.recording;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.panopticon.config.RecordingSettings;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Appends every fresh provider execution (see the {@link com.panopticon.data.DataEngine}
 * hook — cache hits are replays, not executions, and are never recorded) as
 * one {@link RecordedExecution} JSON line to a daily-rolling file
 * {@code <directory>/panopticon-YYYY-MM-DD.jsonl}.
 *
 * <p>Writes happen on a single background thread so a slow disk can never
 * add latency to a panel refresh; within that thread each line is flushed
 * immediately, so a crash loses at most the lines still queued, never a
 * torn/partial line. Disabled (the default) this is a pure no-op.
 *
 * <p>The file is meant to be re-usable data, not just a log: import it into
 * a database table with {@link RecordingImporter} / the {@code --import-recording}
 * startup mode (DDL under {@code db/recording/}).
 */
@Component
public class DataRecorder implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(DataRecorder.class);

    private final boolean enabled;
    private final Path directory;
    private final ObjectMapper objectMapper;
    private final ExecutorService writerThread;

    // Only ever touched from writerThread, so no synchronization needed.
    private LocalDate openDay;
    private BufferedWriter out;

    public DataRecorder(RecordingSettings settings, ObjectMapper objectMapper) {
        this.enabled = settings.enabled();
        this.directory = settings.directory();
        this.objectMapper = objectMapper;
        this.writerThread = enabled
                ? Executors.newSingleThreadExecutor(r -> {
                    Thread t = new Thread(r, "panopticon-recorder");
                    t.setDaemon(true);
                    return t;
                })
                : null;
        if (enabled) {
            log.info("Recording data executions to {}", directory.toAbsolutePath());
        }
    }

    public boolean enabled() {
        return enabled;
    }

    public void record(RecordedExecution execution) {
        if (!enabled) {
            return;
        }
        writerThread.submit(() -> writeLine(execution));
    }

    /** Blocks until every line queued so far is on disk. Intended for tests and shutdown. */
    public void flush() {
        if (!enabled) {
            return;
        }
        try {
            writerThread.submit(() -> { }).get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException("Recorder flush did not complete", e);
        }
    }

    private void writeLine(RecordedExecution execution) {
        try {
            LocalDate day = LocalDate.ofInstant(execution.recordedAt(), ZoneId.systemDefault());
            if (out == null || !day.equals(openDay)) {
                roll(day);
            }
            out.write(objectMapper.writeValueAsString(execution));
            out.newLine();
            out.flush();
        } catch (IOException e) {
            // Recording is an observability side-channel: failing to write must never
            // break panel serving, so log-and-continue is the only sane behavior here.
            log.warn("Failed to record execution of '{}': {}", execution.dataId(), e.getMessage());
        }
    }

    private void roll(LocalDate day) throws IOException {
        if (out != null) {
            out.close();
        }
        Files.createDirectories(directory);
        Path file = directory.resolve("panopticon-%s.jsonl".formatted(day));
        out = Files.newBufferedWriter(file, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        openDay = day;
    }

    @Override
    @PreDestroy
    public void close() {
        if (!enabled) {
            return;
        }
        writerThread.shutdown();
        try {
            if (!writerThread.awaitTermination(10, TimeUnit.SECONDS)) {
                log.warn("Recorder writer thread did not drain within 10s; some lines may be lost");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        try {
            if (out != null) {
                out.close();
            }
        } catch (IOException e) {
            log.warn("Failed to close recording file: {}", e.getMessage());
        }
    }
}
