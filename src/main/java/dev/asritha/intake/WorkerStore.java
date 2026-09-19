package dev.asritha.intake;

import java.sql.SQLException;
import java.util.UUID;

/** Short database transactions grant time-limited ownership; execution happens outside them. */
public final class WorkerStore {
    public record Claim(String id, String payload, UUID token, int attempt) {}
    private final DatabaseConfig database;
    public WorkerStore(DatabaseConfig database) { this.database = database; }

    public Claim claim(int leaseSeconds) throws SQLException {
        if (leaseSeconds < 1 || leaseSeconds > 300) throw new IllegalArgumentException("Lease must be 1–300 seconds");
        try (var connection = database.connect()) {
            connection.setAutoCommit(false);
            try {
                String select = "SELECT id, payload, attempts, max_attempts FROM jobs WHERE "
                    + "(status = 'accepted' AND next_attempt_at <= clock_timestamp()) "
                    + "OR (status = 'running' AND lease_until <= clock_timestamp()) "
                    + "ORDER BY next_attempt_at, created_at, id LIMIT 1 FOR UPDATE SKIP LOCKED";
                try (var statement = connection.prepareStatement(select); var row = statement.executeQuery()) {
                    if (!row.next()) { connection.commit(); return null; }
                    UUID id = (UUID) row.getObject(1);
                    String payload = row.getString(2);
                    int attempts = row.getInt(3);
                    if (attempts >= row.getInt(4)) {
                        try (var update = connection.prepareStatement("UPDATE jobs SET status='failed', lease_token=NULL, lease_until=NULL, last_error='lease_expired' WHERE id=?")) {
                            update.setObject(1, id); update.executeUpdate();
                        }
                        connection.commit();
                        return null;
                    }
                    UUID token = UUID.randomUUID();
                    try (var update = connection.prepareStatement("UPDATE jobs SET status='running', attempts=attempts+1, lease_token=?, lease_until=clock_timestamp() + (? * INTERVAL '1 second') WHERE id=?")) {
                        update.setObject(1, token); update.setInt(2, leaseSeconds); update.setObject(3, id); update.executeUpdate();
                    }
                    connection.commit();
                    return new Claim(id.toString(), payload, token, attempts + 1);
                }
            } catch (SQLException failure) {
                try { connection.rollback(); } catch (SQLException rollback) { failure.addSuppressed(rollback); }
                throw failure;
            }
        }
    }
    public boolean succeed(Claim claim, String sha256) throws SQLException {
        if (sha256 == null || !sha256.matches("[a-f0-9]{64}")) throw new IllegalArgumentException("Invalid SHA-256 result");
        return finish(claim, "status='succeeded', result_sha256=?, last_error=NULL", sha256);
    }
    public boolean fail(Claim claim) throws SQLException {
        return finish(claim, "status=CASE WHEN attempts >= max_attempts THEN 'failed' ELSE 'accepted' END, "
            + "last_error='execution_failed', next_attempt_at=clock_timestamp() + (LEAST(30, power(2, attempts-1)) * INTERVAL '1 second')", null);
    }
    private boolean finish(Claim claim, String changes, String result) throws SQLException {
        String sql = "UPDATE jobs SET " + changes + ", lease_token=NULL, lease_until=NULL "
            + "WHERE id=? AND status='running' AND lease_token=? AND lease_until > clock_timestamp()";
        try (var connection = database.connect(); var statement = connection.prepareStatement(sql)) {
            int index = 1;
            if (result != null) statement.setString(index++, result);
            statement.setObject(index++, UUID.fromString(claim.id()));
            statement.setObject(index, claim.token());
            return statement.executeUpdate() == 1;
        }
    }
}
