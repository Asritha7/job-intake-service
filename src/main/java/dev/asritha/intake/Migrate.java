package dev.asritha.intake;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.SQLException;
import java.util.HexFormat;

/** Applies ordered, checksum-verified SQL migrations in a single transaction. */
public final class Migrate {
    private static final String[] FILES = {"001_job_intake.sql", "002_worker_lifecycle.sql"};
    public static void apply(DatabaseConfig config) throws Exception {
        try (var connection = config.connect()) {
            connection.setAutoCommit(false);
            try (var statement = connection.createStatement()) {
                statement.execute("SELECT pg_advisory_xact_lock(18741, 1)");
                statement.execute("CREATE TABLE IF NOT EXISTS schema_migrations (name text PRIMARY KEY, sha256 text NOT NULL, applied_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP)");
                for (String file : FILES) {
                    byte[] sql = Files.readAllBytes(Path.of("db/migrations", file));
                    String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(sql));
                    try (var query = connection.prepareStatement("SELECT sha256 FROM schema_migrations WHERE name = ?")) {
                        query.setString(1, file);
                        try (var result = query.executeQuery()) {
                            if (result.next()) {
                                if (!hash.equals(result.getString(1))) throw new SQLException("Migration checksum mismatch");
                                continue;
                            }
                        }
                    }
                    statement.execute(new String(sql, java.nio.charset.StandardCharsets.UTF_8));
                    try (var record = connection.prepareStatement("INSERT INTO schema_migrations(name, sha256) VALUES (?, ?)")) {
                        record.setString(1, file); record.setString(2, hash); record.executeUpdate();
                    }
                }
                connection.commit();
            } catch (Exception failure) {
                try { connection.rollback(); } catch (SQLException rollback) { failure.addSuppressed(rollback); }
                throw failure;
            }
        }
    }
    public static void main(String[] args) throws Exception {
        try { apply(DatabaseConfig.fromEnvironment()); }
        catch (SQLException failure) {
            System.err.println("Migration failed. Check connectivity, permissions and migration checksums.");
            System.exit(1);
        }
        System.out.println("Migrations verified and applied; existing jobs preserved.");
    }
}
