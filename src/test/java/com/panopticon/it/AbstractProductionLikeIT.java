package com.panopticon.it;

import com.panopticon.it.support.ProductionTrafficSimulator;
import com.panopticon.it.support.RawDb;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.awaitility.Awaitility.await;

/**
 * Base for the com.panopticon.it production-like integration suite: one
 * Spring context, one temp-file SQLite database seeded with production-like
 * tables (see db/production-like/schema.sql), one {@link ProductionTrafficSimulator}
 * writing to it continuously for the life of the whole test run - shared by
 * every concrete test class in this package, not one-per-class.
 *
 * <p>That sharing is deliberate, not an oversight: a static field declared on
 * this abstract class has exactly one storage location for the whole JVM
 * regardless of how many subclasses touch it, and every subclass here uses
 * identical {@code @SpringBootTest}/{@code @DynamicPropertySource} config, so
 * Spring's test context cache reuses the same {@code ApplicationContext}
 * (and therefore the same database/traffic simulator) across all of them -
 * one context boot and one schema/seed run instead of five, closer to "one
 * persistent staging-like environment exercised by several focused suites"
 * than "N independent throwaway databases." Every test that mutates shared
 * state (schema-drift rename, table create/drop) restores it in a
 * {@code finally} block so classes stay independent of run order.
 *
 * <p>Startup is lazy and idempotent, triggered from {@link #ensureTrafficRunning()}
 * (an instance {@code @BeforeEach}) rather than a static {@code @BeforeAll}:
 * under JUnit's default lifecycle, Spring only loads the context (running
 * initSchema/initData) when a test instance is prepared, which happens
 * *after* {@code @BeforeAll} but always before any instance method like
 * {@code @BeforeEach} - so this is the earliest point the schema is
 * guaranteed to already exist.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.config.location=classpath:/application-it.yml")
public abstract class AbstractProductionLikeIT {

    private static final Path dbFile = createTempDbFile();
    /**
     * Recording is enabled for the whole shared context (see RecordingRoundTripIT
     * for the round-trip assertions) - a static-final path, like dbFile, so every
     * subclass registers the identical value and the context stays cacheable.
     */
    protected static final Path recordingDir = createTempRecordingDir();
    private static final Object INIT_LOCK = new Object();

    protected static volatile ProductionTrafficSimulator simulator;
    protected static volatile RawDb rawDb;

    private static Path createTempDbFile() {
        try {
            Path file = Files.createTempFile("panopticon-it-", ".db");
            Files.deleteIfExists(file); // sqlite creates the file itself on first connect
            return file;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Path createTempRecordingDir() {
        try {
            return Files.createTempDirectory("panopticon-it-recordings-");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("panopticon.datasources.prod-like-sqlite.jdbc-url", AbstractProductionLikeIT::jdbcUrl);
        registry.add("panopticon.recording.enabled", () -> "true");
        registry.add("panopticon.recording.directory", () -> recordingDir.toString());
    }

    @BeforeEach
    void ensureTrafficRunning() {
        if (simulator != null) {
            return;
        }
        synchronized (INIT_LOCK) {
            if (simulator != null) {
                return;
            }
            RawDb db = new RawDb(jdbcUrl());
            ProductionTrafficSimulator sim = new ProductionTrafficSimulator(jdbcUrl(), 42L);
            sim.start();
            Runtime.getRuntime().addShutdownHook(new Thread(sim::close));
            // Let a handful of ticks land before any test runs so tables aren't empty at T0 -
            // reports/tickets/transactions/events all depend on at least a few users existing.
            await().atMost(Duration.ofSeconds(15)).until(() ->
                    sim.usersCreated() >= 5 && sim.ticketsCreated() >= 3 && sim.transactionsCreated() >= 3);
            rawDb = db;
            simulator = sim;
        }
    }

    static String jdbcUrl() {
        // busy_timeout is read by sqlite-jdbc straight off the URL query string and applies to
        // every connection opened against it - the app's Hikari pool included - so contention
        // (ordinary concurrent traffic, or the deliberate BEGIN EXCLUSIVE lock in
        // LockedDatabaseStressIT) reliably surfaces as a timely SQLITE_BUSY instead of hanging.
        // Deliberately plain rollback-journal mode (SQLite's default), not WAL: WAL lets readers
        // and writers proceed concurrently, which is usually desirable but defeats the one test
        // that specifically wants a held write transaction to also block readers - BEGIN EXCLUSIVE
        // reliably does that under rollback-journal mode without extra pragma gymnastics.
        return "jdbc:sqlite:" + dbFile.toAbsolutePath() + "?busy_timeout=2000";
    }
}
