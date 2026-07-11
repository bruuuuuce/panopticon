package com.panopticon.it.support;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Stands in for the several independent microservices (user-service,
 * report/abuse-service, ticketing, payments, an async event worker) that
 * would really be writing to these tables in production, so the integration
 * suite can validate dashboards against a database under genuine concurrent
 * write load instead of a static fixture.
 *
 * <p>Each domain runs on its own thread with its own JDBC connection (never
 * through Panopticon's own HikariCP pool - in a real deployment Panopticon
 * only ever reads), ticking on a fixed delay and performing one create or
 * state-transition per tick. A caught {@link SQLException} (e.g. SQLITE_BUSY
 * while a test is deliberately holding an exclusive lock) is counted, not
 * fatal - real writers retry or skip a beat, they don't crash.
 */
public final class ProductionTrafficSimulator implements AutoCloseable {

    private final String jdbcUrl;
    private final Random random;
    private final ScheduledExecutorService executor = Executors.newScheduledThreadPool(5);
    private final AtomicBoolean paused = new AtomicBoolean(false);
    private final AtomicBoolean[] workerBusy = new AtomicBoolean[]{
            new AtomicBoolean(), new AtomicBoolean(), new AtomicBoolean(), new AtomicBoolean(), new AtomicBoolean()
    };

    private final AtomicLong usersCreated = new AtomicLong();
    private final AtomicLong reportsCreated = new AtomicLong();
    private final AtomicLong reportsTransitioned = new AtomicLong();
    private final AtomicLong ticketsCreated = new AtomicLong();
    private final AtomicLong ticketsTransitioned = new AtomicLong();
    private final AtomicLong transactionsCreated = new AtomicLong();
    private final AtomicLong transactionsTransitioned = new AtomicLong();
    private final AtomicLong eventsEnqueued = new AtomicLong();
    private final AtomicLong eventsProcessed = new AtomicLong();
    private final AtomicLong eventsDeadLettered = new AtomicLong();
    private final AtomicLong tickErrors = new AtomicLong();
    private final AtomicLong userSeq = new AtomicLong();

    public ProductionTrafficSimulator(String jdbcUrl, long seed) {
        this.jdbcUrl = jdbcUrl;
        this.random = new Random(seed);
    }

    public void start() {
        schedule(0, 150, this::userTick);
        schedule(1, 180, this::reportTick);
        schedule(2, 140, this::ticketTick);
        schedule(3, 130, this::transactionTick);
        schedule(4, 90, this::eventTick);
    }

    private void schedule(int workerIndex, long delayMs, Tick tick) {
        executor.scheduleWithFixedDelay(() -> {
            // busy is raised *before* the paused check (and only ever lowered in the finally
            // below), so pauseWrites()'s scan can never miss a tick that decided to proceed just
            // before paused flipped true - closing a real race that used to let one last write
            // slip in right as a test acquired the whole-database exclusive lock.
            AtomicBoolean busy = workerBusy[workerIndex];
            busy.set(true);
            try {
                if (paused.get()) {
                    return;
                }
                try (Connection conn = connect()) {
                    tick.run(conn);
                } catch (SQLException e) {
                    tickErrors.incrementAndGet();
                } catch (RuntimeException e) {
                    tickErrors.incrementAndGet();
                }
            } finally {
                busy.set(false);
            }
        }, delayMs, delayMs, TimeUnit.MILLISECONDS);
    }

    private Connection connect() throws SQLException {
        Connection conn = DriverManager.getConnection(jdbcUrl);
        conn.setAutoCommit(true);
        return conn;
    }

    /** Blocks until every worker has finished its current tick and none will start a new one, so a
     *  test can take an exact, non-racy snapshot of the database. Resume with {@link #resumeWrites()}. */
    public void pauseWrites() {
        paused.set(true);
        long deadline = System.currentTimeMillis() + 3000;
        while (System.currentTimeMillis() < deadline) {
            boolean anyBusy = false;
            for (AtomicBoolean busy : workerBusy) {
                if (busy.get()) {
                    anyBusy = true;
                    break;
                }
            }
            if (!anyBusy) {
                return;
            }
            sleep(10);
        }
        throw new IllegalStateException("Traffic simulator did not quiesce within 3s");
    }

    public void resumeWrites() {
        paused.set(false);
    }

    private void userTick(Connection conn) throws SQLException {
        long n = userSeq.incrementAndGet();
        String role = weighted(new String[]{"CUSTOMER", "AGENT", "ADMIN"}, new double[]{0.7, 0.2, 0.1});
        String status = weighted(new String[]{"ACTIVE", "SUSPENDED", "DELETED"}, new double[]{0.9, 0.08, 0.02});
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO users (username, email, role, status, created_at) VALUES (?, ?, ?, ?, ?)")) {
            String username = "user_" + n + "_" + Long.toHexString(random.nextLong());
            ps.setString(1, username);
            ps.setString(2, username + "@example.test");
            ps.setString(3, role);
            ps.setString(4, status);
            ps.setString(5, now());
            ps.executeUpdate();
        }
        usersCreated.incrementAndGet();
    }

    private void reportTick(Connection conn) throws SQLException {
        if (random.nextDouble() < 0.6) {
            Long reporterId = randomId(conn, "users");
            if (reporterId == null) {
                return;
            }
            String targetType = weighted(new String[]{"USER", "TICKET", "TRANSACTION", "CONTENT"},
                    new double[]{0.3, 0.3, 0.2, 0.2});
            String severity = weighted(new String[]{"LOW", "MEDIUM", "HIGH", "CRITICAL"},
                    new double[]{0.4, 0.35, 0.2, 0.05});
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO reports (reporter_user_id, target_type, target_id, reason, severity, status, created_at) " +
                            "VALUES (?, ?, ?, ?, ?, 'NEW', ?)")) {
                ps.setLong(1, reporterId);
                ps.setString(2, targetType);
                ps.setLong(3, reporterId);
                ps.setString(4, "Simulated " + targetType.toLowerCase(java.util.Locale.ROOT) + " report");
                ps.setString(5, severity);
                ps.setString(6, now());
                ps.executeUpdate();
            }
            reportsCreated.incrementAndGet();
        } else {
            Long id = randomIdWhere(conn, "reports", "status IN ('NEW','UNDER_REVIEW','ESCALATED')");
            if (id == null) {
                return;
            }
            String current = scalarString(conn, "SELECT status FROM reports WHERE id = ?", id);
            String next = switch (current) {
                case "NEW" -> "UNDER_REVIEW";
                case "UNDER_REVIEW" -> weighted(new String[]{"ESCALATED", "RESOLVED", "DISMISSED"}, new double[]{0.3, 0.5, 0.2});
                case "ESCALATED" -> "RESOLVED";
                default -> null;
            };
            if (next == null) {
                return;
            }
            boolean terminal = next.equals("RESOLVED") || next.equals("DISMISSED");
            try (PreparedStatement ps = conn.prepareStatement(
                    terminal ? "UPDATE reports SET status = ?, resolved_at = ? WHERE id = ?"
                             : "UPDATE reports SET status = ? WHERE id = ?")) {
                ps.setString(1, next);
                if (terminal) {
                    ps.setString(2, now());
                    ps.setLong(3, id);
                } else {
                    ps.setLong(2, id);
                }
                ps.executeUpdate();
            }
            reportsTransitioned.incrementAndGet();
        }
    }

    private void ticketTick(Connection conn) throws SQLException {
        if (random.nextDouble() < 0.55) {
            Long userId = randomId(conn, "users");
            if (userId == null) {
                return;
            }
            String priority = weighted(new String[]{"LOW", "MEDIUM", "HIGH", "CRITICAL"},
                    new double[]{0.4, 0.35, 0.2, 0.05});
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO tickets (user_id, subject, status, priority, created_at, updated_at) " +
                            "VALUES (?, ?, 'OPEN', ?, ?, ?)")) {
                ps.setLong(1, userId);
                ps.setString(2, "Simulated ticket " + System.nanoTime());
                ps.setString(3, priority);
                String ts = now();
                ps.setString(4, ts);
                ps.setString(5, ts);
                ps.executeUpdate();
            }
            ticketsCreated.incrementAndGet();
        } else {
            Long id = randomIdWhere(conn, "tickets", "status NOT IN ('RESOLVED','CLOSED')");
            if (id == null) {
                return;
            }
            String current = scalarString(conn, "SELECT status FROM tickets WHERE id = ?", id);
            String next = switch (current) {
                case "OPEN" -> "IN_PROGRESS";
                case "IN_PROGRESS" -> weighted(new String[]{"WAITING_CUSTOMER", "RESOLVED"}, new double[]{0.4, 0.6});
                case "WAITING_CUSTOMER" -> "IN_PROGRESS";
                default -> null;
            };
            if (next == null) {
                return;
            }
            boolean resolved = next.equals("RESOLVED");
            try (PreparedStatement ps = conn.prepareStatement(
                    resolved ? "UPDATE tickets SET status = ?, updated_at = ?, resolved_at = ? WHERE id = ?"
                             : "UPDATE tickets SET status = ?, updated_at = ? WHERE id = ?")) {
                ps.setString(1, next);
                String ts = now();
                ps.setString(2, ts);
                if (resolved) {
                    ps.setString(3, ts);
                    ps.setLong(4, id);
                } else {
                    ps.setLong(3, id);
                }
                ps.executeUpdate();
            }
            ticketsTransitioned.incrementAndGet();
            if (next.equals("RESOLVED") && random.nextDouble() < 0.5) {
                try (Statement st = conn.createStatement()) {
                    st.executeUpdate("UPDATE tickets SET status = 'CLOSED' WHERE id = " + id);
                }
            }
        }
    }

    private void transactionTick(Connection conn) throws SQLException {
        if (random.nextDouble() < 0.5) {
            Long userId = randomId(conn, "users");
            if (userId == null) {
                return;
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO transactions (user_id, amount_cents, currency, status, created_at, updated_at) " +
                            "VALUES (?, ?, 'EUR', 'PENDING', ?, ?)")) {
                ps.setLong(1, userId);
                ps.setLong(2, 500 + random.nextInt(50_000));
                String ts = now();
                ps.setString(3, ts);
                ps.setString(4, ts);
                ps.executeUpdate();
            }
            transactionsCreated.incrementAndGet();
        } else {
            Long id = randomIdWhere(conn, "transactions", "status NOT IN ('SETTLED','FAILED','REFUNDED')");
            if (id == null) {
                return;
            }
            String current = scalarString(conn, "SELECT status FROM transactions WHERE id = ?", id);
            String next = switch (current) {
                case "PENDING" -> weighted(new String[]{"AUTHORIZED", "FAILED"}, new double[]{0.9, 0.1});
                case "AUTHORIZED" -> "CAPTURED";
                case "CAPTURED" -> weighted(new String[]{"SETTLED", "REFUNDED"}, new double[]{0.95, 0.05});
                default -> null;
            };
            if (next == null) {
                return;
            }
            try (PreparedStatement ps = conn.prepareStatement("UPDATE transactions SET status = ?, updated_at = ? WHERE id = ?")) {
                ps.setString(1, next);
                ps.setString(2, now());
                ps.setLong(3, id);
                ps.executeUpdate();
            }
            transactionsTransitioned.incrementAndGet();
        }
    }

    private void eventTick(Connection conn) throws SQLException {
        if (random.nextBoolean()) {
            String[] aggregates = {"USER", "REPORT", "TICKET", "TRANSACTION"};
            String aggregateType = aggregates[random.nextInt(aggregates.length)];
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO event_outbox (aggregate_type, aggregate_id, event_type, status, attempts, max_attempts, created_at) " +
                            "VALUES (?, ?, ?, 'PENDING', 0, 5, ?)")) {
                ps.setString(1, aggregateType);
                ps.setLong(2, 1 + random.nextInt(1000));
                ps.setString(3, aggregateType.toLowerCase(java.util.Locale.ROOT) + ".changed");
                ps.setString(4, now());
                ps.executeUpdate();
            }
            eventsEnqueued.incrementAndGet();
        } else {
            Long id = randomIdWhere(conn, "event_outbox", "status IN ('PENDING','RETRY_SCHEDULED')");
            if (id == null) {
                return;
            }
            try (PreparedStatement claim = conn.prepareStatement(
                    "UPDATE event_outbox SET status = 'PROCESSING', locked_by = ?, locked_at = ? WHERE id = ?")) {
                claim.setString(1, "worker-" + Thread.currentThread().threadId());
                claim.setString(2, now());
                claim.setLong(3, id);
                claim.executeUpdate();
            }
            double outcome = random.nextDouble();
            if (outcome < 0.75) {
                try (PreparedStatement ps = conn.prepareStatement(
                        "UPDATE event_outbox SET status = 'PROCESSED', processed_at = ? WHERE id = ?")) {
                    ps.setString(1, now());
                    ps.setLong(2, id);
                    ps.executeUpdate();
                }
                eventsProcessed.incrementAndGet();
            } else if (outcome < 0.90) {
                int attempts = ((Number) scalarObject(conn, "SELECT attempts FROM event_outbox WHERE id = ?", id)).intValue() + 1;
                int maxAttempts = ((Number) scalarObject(conn, "SELECT max_attempts FROM event_outbox WHERE id = ?", id)).intValue();
                String next = attempts >= maxAttempts ? "DEAD_LETTER" : "RETRY_SCHEDULED";
                try (PreparedStatement ps = conn.prepareStatement(
                        "UPDATE event_outbox SET status = ?, attempts = ?, last_error = ? WHERE id = ?")) {
                    ps.setString(1, next);
                    ps.setInt(2, attempts);
                    ps.setString(3, "Simulated transient processing failure");
                    ps.setLong(4, id);
                    ps.executeUpdate();
                }
                if (next.equals("DEAD_LETTER")) {
                    eventsDeadLettered.incrementAndGet();
                }
            } else {
                try (PreparedStatement ps = conn.prepareStatement(
                        "UPDATE event_outbox SET status = 'FAILED', last_error = ? WHERE id = ?")) {
                    ps.setString(1, "Simulated permanent processing failure");
                    ps.setLong(2, id);
                    ps.executeUpdate();
                }
            }
        }
    }

    private Long randomId(Connection conn, String table) throws SQLException {
        return randomIdWhere(conn, table, "1=1");
    }

    private Long randomIdWhere(Connection conn, String table, String where) throws SQLException {
        // SQLite has no TABLESAMPLE; ORDER BY RANDOM() is fine at this data volume and keeps every
        // worker's "pick something to act on" logic identical regardless of table.
        String sql = "SELECT id FROM " + table + " WHERE " + where + " ORDER BY RANDOM() LIMIT 1";
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            return rs.next() ? rs.getLong(1) : null;
        }
    }

    private String scalarString(Connection conn, String sql, long id) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    private Object scalarObject(Connection conn, String sql, long id) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getObject(1) : null;
            }
        }
    }

    private String weighted(String[] values, double[] weights) {
        double r = random.nextDouble();
        double cumulative = 0;
        for (int i = 0; i < values.length; i++) {
            cumulative += weights[i];
            if (r <= cumulative) {
                return values[i];
            }
        }
        return values[values.length - 1];
    }

    private static String now() {
        return Instant.now().toString();
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public long usersCreated() {
        return usersCreated.get();
    }

    public long reportsCreated() {
        return reportsCreated.get();
    }

    public long reportsTransitioned() {
        return reportsTransitioned.get();
    }

    public long ticketsCreated() {
        return ticketsCreated.get();
    }

    public long ticketsTransitioned() {
        return ticketsTransitioned.get();
    }

    public long transactionsCreated() {
        return transactionsCreated.get();
    }

    public long transactionsTransitioned() {
        return transactionsTransitioned.get();
    }

    public long eventsEnqueued() {
        return eventsEnqueued.get();
    }

    public long eventsProcessed() {
        return eventsProcessed.get();
    }

    public long eventsDeadLettered() {
        return eventsDeadLettered.get();
    }

    public long tickErrors() {
        return tickErrors.get();
    }

    @Override
    public void close() {
        executor.shutdownNow();
        try {
            executor.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @FunctionalInterface
    private interface Tick {
        void run(Connection conn) throws SQLException;
    }
}
