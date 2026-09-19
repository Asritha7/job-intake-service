package dev.asritha.intake;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;

/** Explicit, transactional bootstrap for migration 001 only. */
public final class Migrate {
    public static void apply(DatabaseConfig config) throws Exception {
        String sql = Files.readString(Path.of("db/migrations/001_job_intake.sql"));
        try (var connection = config.connect()) {
            connection.setAutoCommit(false);
            try (var statement = connection.createStatement()) {
                // Serialize concurrent bootstrap attempts in this database.
                statement.execute("SELECT pg_advisory_xact_lock(18741, 1)");
                statement.execute(sql);
                connection.commit();
            } catch (SQLException error) {
                connection.rollback();
                throw error;
            }
        }
    }
    public static void main(String[] args) throws Exception {
        try { apply(DatabaseConfig.fromEnvironment()); }
        catch (SQLException failure) {
            System.err.println("Migration failed. Check database availability, credentials and schema permissions.");
            System.exit(1);
        }
        System.out.println("Migration 001 applied; existing jobs and configured capacity preserved.");
    }
}
