package dev.asritha.intake;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;

/** All submissions serialize on the configuration row; reads remain concurrent. */
public final class PostgresJobStore implements JobStore {
    private final DatabaseConfig database;
    public PostgresJobStore(DatabaseConfig database) { this.database = database; }

    @Override public Submission submit(String key, String payload) throws SQLException {
        try (Connection connection = database.connect()) {
            connection.setAutoCommit(false);
            try {
                int capacity;
                try (var statement = connection.prepareStatement("SELECT capacity FROM intake_config WHERE singleton = TRUE FOR UPDATE");
                     var result = statement.executeQuery()) {
                    if (!result.next()) throw new SQLException("Missing store configuration");
                    capacity = result.getInt(1);
                }
                // This statement sees a fresh READ COMMITTED snapshot after obtaining the lock.
                try (var statement = connection.prepareStatement("SELECT id, payload, status, attempts, result_sha256, last_error FROM jobs WHERE idempotency_key = ?")) {
                    statement.setString(1, key);
                    try (var result = statement.executeQuery()) {
                        if (result.next()) {
                            Job existing = new Job(result.getString(1), result.getString(2), result.getString(3), result.getInt(4), result.getString(5), result.getString(6));
                            connection.commit();
                            return existing.payload().equals(payload)
                                ? new Submission(200, existing) : new Submission(409, null);
                        }
                    }
                }
                try (var statement = connection.prepareStatement("SELECT count(*) FROM jobs");
                     var result = statement.executeQuery()) {
                    result.next();
                    if (result.getLong(1) >= capacity) {
                        connection.commit();
                        return new Submission(503, null);
                    }
                }
                Job job = new Job(UUID.randomUUID().toString(), payload);
                try (var statement = connection.prepareStatement("INSERT INTO jobs(id, idempotency_key, payload) VALUES (?, ?, ?)")) {
                    statement.setObject(1, UUID.fromString(job.id()));
                    statement.setString(2, key);
                    statement.setString(3, payload);
                    statement.executeUpdate();
                }
                connection.commit(); // Never return acceptance before PostgreSQL commits.
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
