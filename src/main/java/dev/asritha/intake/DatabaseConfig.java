package dev.asritha.intake;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Properties;

/** Credentials remain outside JDBC URLs, logs, and source control. */
public final class DatabaseConfig {
    private final String url, user, password, schema;
    public DatabaseConfig(String url, String user, String password, String schema) {
        if (url == null || !url.startsWith("jdbc:postgresql:"))
            throw new IllegalArgumentException("Set DATABASE_URL to a PostgreSQL JDBC URL");
        if (schema == null || !schema.matches("[a-z][a-z0-9_]{0,62}"))
            throw new IllegalArgumentException("Invalid database schema name");
        this.url = url; this.user = user; this.password = password; this.schema = schema;
    }
    public static DatabaseConfig fromEnvironment() {
        return new DatabaseConfig(System.getenv("DATABASE_URL"),
            System.getenv().getOrDefault("DATABASE_USER", "jobs"),
            System.getenv().getOrDefault("DATABASE_PASSWORD", ""),
            System.getenv().getOrDefault("DATABASE_SCHEMA", "public"));
    }
    public DatabaseConfig withSchema(String name) { return new DatabaseConfig(url, user, password, name); }
    public Connection connect() throws SQLException {
        Properties properties = new Properties();
        properties.setProperty("user", user);
        properties.setProperty("password", password);
        properties.setProperty("currentSchema", schema);
        properties.setProperty("connectTimeout", "3");
        properties.setProperty("socketTimeout", "8");
        properties.setProperty("options", "-c statement_timeout=5000 -c lock_timeout=3000");
        Connection connection = DriverManager.getConnection(url, properties);
        connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
        return connection;
    }
}
