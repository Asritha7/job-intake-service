package dev.asritha.intake;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;
import java.util.function.BiConsumer;

/** All submissions serialize on the configuration row; reads remain concurrent. */
public final class PostgresJobStore implements JobStore {
    @FunctionalInterface
    interface ConnectionOpener { Connection open() throws SQLException; }
    private final DatabaseConfig database;
    private final ConnectionOpener submissionConnections;
    private final BiConsumer<String, Long> phaseObserver;
    public PostgresJobStore(DatabaseConfig database) { this(database, null); }
    // Internal, test-only observation; normal runtime has no observer.
    PostgresJobStore(DatabaseConfig database, BiConsumer<String, Long> observer) {
        this(database, database::connect, observer);
    }
    // Only isolated tests may supply an alternate connection source.
    PostgresJobStore(DatabaseConfig database, ConnectionOpener submissionConnections,
                     BiConsumer<String, Long> observer) {
        this.database = java.util.Objects.requireNonNull(database);
        this.submissionConnections = java.util.Objects.requireNonNull(submissionConnections);
        this.phaseObserver = observer;
    }
    private long tick() { return phaseObserver == null ? 0L : System.nanoTime(); }
    private void observe(String stage, long start) {
        if (phaseObserver != null) phaseObserver.accept(stage, System.nanoTime() - start);
    }

    @Override public Submission submit(String key, String payload) throws SQLException {
        long phaseStarted = tick();
        try (Connection connection = submissionConnections.open()) {
            observe("jdbc_connect", phaseStarted);
            connection.setAutoCommit(false);
            try {
                int capacity;
                phaseStarted = tick();
                try (var statement = connection.prepareStatement("SELECT capacity FROM intake_config WHERE singleton = TRUE FOR UPDATE");
                     var result = statement.executeQuery()) {
                    if (!result.next()) throw new SQLException("Missing store configuration");
                    capacity = result.getInt(1);
                }
                observe("capacity_lock", phaseStarted);
                // This statement sees a fresh READ COMMITTED snapshot after obtaining the lock.
                phaseStarted = tick();
                try (var statement = connection.prepareStatement("SELECT id, payload, status, attempts, result_sha256, last_error FROM jobs WHERE idempotency_key = ?")) {
                    statement.setString(1, key);
                    try (var result = statement.executeQuery()) {
                        if (result.next()) {
                            observe("key_lookup", phaseStarted);
                            Job existing = new Job(result.getString(1), result.getString(2), result.getString(3), result.getInt(4), result.getString(5), result.getString(6));
                            long commitStarted = tick();
                            connection.commit();
                            observe("commit", commitStarted);
                            return existing.payload().equals(payload)
                                ? new Submission(200, existing) : new Submission(409, null);
                        }
                    }
                }
                observe("key_lookup", phaseStarted);
                phaseStarted = tick();
                try (var statement = connection.prepareStatement("SELECT count(*) FROM jobs");
                     var result = statement.executeQuery()) {
                    result.next();
                    observe("capacity_count", phaseStarted);
                    if (result.getLong(1) >= capacity) {
                        long commitStarted = tick();
                        connection.commit();
                        observe("commit", commitStarted);
                        return new Submission(503, null);
                    }
                }
                Job job = new Job(UUID.randomUUID().toString(), payload);
                phaseStarted = tick();
                try (var statement = connection.prepareStatement("INSERT INTO jobs(id, idempotency_key, payload) VALUES (?, ?, ?)")) {
                    statement.setObject(1, UUID.fromString(job.id()));
                    statement.setString(2, key);
                    statement.setString(3, payload);
                    statement.executeUpdate();
                }
                observe("insert", phaseStarted);
                long commitStarted = tick();
                connection.commit(); // Never return acceptance before PostgreSQL commits.
                observe("commit", commitStarted);
                return new Submission(201, job);
            } catch (SQLException failure) {
                try { connection.rollback(); } catch (SQLException rollback) { failure.addSuppressed(rollback); }
                throw failure;
            }
        }
    }
    @Override public Job find(String id) throws SQLException {
        final UUID uuid;
        try { uuid = UUID.fromString(id); } catch (IllegalArgumentException invalid) { return null; }
        try (var connection = database.connect();
             var statement = connection.prepareStatement("SELECT id, payload, status, attempts, result_sha256, last_error FROM jobs WHERE id = ?")) {
            statement.setObject(1, uuid);
            try (var result = statement.executeQuery()) {
                return result.next() ? new Job(result.getString(1), result.getString(2), result.getString(3), result.getInt(4), result.getString(5), result.getString(6)) : null;
            }
        }
    }
    @Override public boolean ready() throws SQLException {
        try (var connection = database.connect();
             var statement = connection.prepareStatement("SELECT capacity, (SELECT count(attempts) FROM jobs WHERE FALSE) FROM intake_config WHERE singleton = TRUE");
             var result = statement.executeQuery()) {
            return result.next();
        }
    }
}
