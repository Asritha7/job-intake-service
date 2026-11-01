package dev.asritha.intake;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.SQLException;

/**
 * Explicitly enabled, bounded JDBC connection reuse for the HTTP API only.
 * The default application path still uses fresh JDBC connections.
 */
public final class PooledPostgresJobStore implements JobStore, AutoCloseable {
    private final HikariDataSource source;
    private final PostgresJobStore delegate;

    static int parseSize(String value) {
        if (value == null || value.isBlank() || "0".equals(value)) return 0;
        if (!value.matches("[1-8]"))
            throw new IllegalArgumentException("JOB_DB_POOL_SIZE must be 0 (disabled) or 1..8");
        return Integer.parseInt(value);
    }

    public PooledPostgresJobStore(DatabaseConfig database, int maximumConnections) {
        if (maximumConnections < 1 || maximumConnections > 8)
            throw new IllegalArgumentException("Pool size must be 1..8");
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(database.jdbcUrl());
        config.setDataSourceProperties(database.connectionProperties());
        config.setPoolName("job-intake-api");
        config.setMaximumPoolSize(maximumConnections);
        config.setMinimumIdle(0);
        config.setConnectionTimeout(3_000);
        config.setValidationTimeout(1_000);
        // Preserve the existing liveness contract when PostgreSQL is temporarily down.
        // /ready and storage-backed calls still fail closed until the DB recovers.
        config.setInitializationFailTimeout(-1);
        config.setAutoCommit(true);
        config.setTransactionIsolation("TRANSACTION_READ_COMMITTED");
        source = new HikariDataSource(config);
        delegate = new PostgresJobStore(database, source::getConnection, null);
    }

    @Override public Submission submit(String key, String payload) throws SQLException {
        return delegate.submit(key, payload);
    }
    @Override public Job find(String id) throws SQLException { return delegate.find(id); }
    @Override public boolean ready() throws SQLException { return delegate.ready(); }

    boolean isClosed() { return source.isClosed(); }
    int maximumPoolSize() { return source.getMaximumPoolSize(); }

    @Override public void close() { source.close(); }
}
