package dev.asritha.intake;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Isolated benchmark only: compares fresh connections and 4/8-connection
 * Hikari pools using the unchanged PostgresJobStore submission transaction.
 */
public final class PoolComparisonProbe {
    private PoolComparisonProbe() {}

    static String modeName(String raw) {
        return switch (raw) {
            case "fresh", "pool4", "pool8" -> raw;
            default -> throw new IllegalArgumentException("Unsupported pool mode");
        };
    }

    static void isolationCheck() {
        AdmissionPhaseProbe.requireIsolation();
        if (!"isolated_ci_password".equals(System.getenv("DATABASE_PASSWORD"))) {
            throw new IllegalArgumentException("Wrong disposable CI database credentials");
        }
    }

    static HikariDataSource pool(int size) throws Exception {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(System.getenv("DATABASE_URL"));
        config.setUsername(System.getenv("DATABASE_USER"));
        config.setPassword(System.getenv("DATABASE_PASSWORD"));
        config.setMaximumPoolSize(size);
        config.setMinimumIdle(size);
        config.setConnectionTimeout(3_000);
        config.setInitializationFailTimeout(10_000);
        config.setAutoCommit(true);
        config.setTransactionIsolation("TRANSACTION_READ_COMMITTED");
        config.setPoolName("isolated-phase-pool");
        config.addDataSourceProperty("currentSchema", "public");
        config.addDataSourceProperty("connectTimeout", "3");
        config.addDataSourceProperty("socketTimeout", "8");
        config.addDataSourceProperty("options", "-c statement_timeout=5000 -c lock_timeout=3000");
        HikariDataSource source = new HikariDataSource(config);
        // Prewarm physical connections outside measured work; document this advantage.
        List<Connection> borrowed = new ArrayList<>();
        try {
            for (int index = 0; index < size; index++) borrowed.add(source.getConnection());
        } finally {
            for (Connection connection : borrowed) connection.close();
        }
        return source;
    }

    static String run(int clients, String mode, int jobs) throws Exception {
        AdmissionPhaseProbe.Recorder recorder = new AdmissionPhaseProbe.Recorder();
        DatabaseConfig database = DatabaseConfig.fromEnvironment();
        int poolSize = mode.equals("pool4") ? 4 : mode.equals("pool8") ? 8 : 0;
        try (HikariDataSource source = poolSize == 0 ? null : pool(poolSize)) {
            PostgresJobStore.ConnectionOpener opener =
                    source == null ? database::connect : source::getConnection;
            PostgresJobStore store = new PostgresJobStore(database, opener, recorder);

            String prefix = "pool-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
            ArrayList<String> keys = new ArrayList<>();
            for (int index = 0; index < jobs; index++) keys.add(prefix + "-" + index);

            List<Long> createLatencies = Collections.synchronizedList(new ArrayList<>());
            ArrayList<Callable<Void>> createTasks = new ArrayList<>();
            for (int index = 0; index < jobs; index++) {
                final int jobIndex = index;
                createTasks.add(() -> {
                    long start = System.nanoTime();
                    var result = store.submit(keys.get(jobIndex), "pool-compare-payload-" + jobIndex);
                    createLatencies.add(System.nanoTime() - start);
                    if (result.status() != 201 || result.job() == null)
                        throw new IllegalStateException("Create was not 201");
                    return null;
                });
            }
            long start = System.nanoTime();
            AdmissionPhaseProbe.execute(clients, createTasks);
            long createWall = System.nanoTime() - start;
            String create = metrics(recorder, createLatencies, createWall, jobs);

            recorder.clear();
            List<Long> replayLatencies = Collections.synchronizedList(new ArrayList<>());
            ArrayList<Callable<Void>> replayTasks = new ArrayList<>();
            for (int index = 0; index < jobs / 2; index++) {
                final int jobIndex = index;
                replayTasks.add(() -> {
                    long itemStart = System.nanoTime();
                    var result = store.submit(keys.get(jobIndex), "pool-compare-payload-" + jobIndex);
                    replayLatencies.add(System.nanoTime() - itemStart);
                    if (result.status() != 200 || result.job() == null)
                        throw new IllegalStateException("Replay was not 200");
                    return null;
                });
            }
            start = System.nanoTime();
            AdmissionPhaseProbe.execute(clients, replayTasks);
            long replayWall = System.nanoTime() - start;
            String replay = metrics(recorder, replayLatencies, replayWall, jobs / 2);

            // Conflicting reuse must remain 409 with no extra accepted row.
            if (store.submit(keys.get(0), "conflicting-body").status() != 409)
                throw new IllegalStateException("Idempotency conflict contract broken");
            // Inspect transaction state after a 409, including pooled reuse.
            if (store.submit(keys.get(0), "pool-compare-payload-0").status() != 200)
                throw new IllegalStateException("Connection not reusable after conflict");
            boolean sqlRejected = false;
            try {
                store.submit(prefix + "-invalid key", "invalid SQL constraint input");
            } catch (java.sql.SQLException expected) {
                sqlRejected = true;
            }
            if (!sqlRejected) throw new IllegalStateException("Database constraint must reject invalid key");
            // Both strategies must roll back the failed transaction and remain usable.
            if (store.submit(prefix + "-after-failure", "recovered").status() != 201)
                throw new IllegalStateException("Connection not reusable after failed SQL transaction");
            if (source != null) {
                try (Connection connection = source.getConnection()) {
                    if (!connection.getAutoCommit()
                            || connection.getTransactionIsolation() != Connection.TRANSACTION_READ_COMMITTED)
                        throw new IllegalStateException("Pooled connection leaked transaction state");
                    if (!connection.isValid(2)) throw new IllegalStateException("Invalid pooled connection");
                }
            }
            return "{\"schema_version\":1,\"source\":\"isolated_connection_comparison\","
                    + "\"mode\":\"" + mode + "\",\"pool_size\":" + poolSize
                    + ",\"clients\":" + clients + ",\"create\":" + create
                    + ",\"replay\":" + replay + ",\"conflict_check\":true,"
                    + "\"transaction_state_check\":true,\"sql_rollback_check\":true}\n";
        }
    }

    static String metrics(AdmissionPhaseProbe.Recorder recorder, List<Long> latencies,
                          long elapsed, int operations) {
        ArrayList<Long> sorted = new ArrayList<>(latencies);
        Collections.sort(sorted);
        if (sorted.size() != operations) throw new IllegalStateException("Incomplete operations");
        double p95 = AdmissionPhaseProbe.percentile(sorted, .95) / 1_000_000.0;
        double throughput = operations * 1_000_000_000.0 / elapsed;
        // The phase recorder is reset between create and replay phases.
        String phaseMetrics = AdmissionPhaseProbe.metrics(recorder, operations, elapsed);
        return "{\"operations\":" + operations + ",\"throughput_per_second\":" + throughput
                + ",\"p95_ms\":" + p95 + ",\"phase_metrics\":" + phaseMetrics + "}";
    }

    public static void main(String[] args) throws Exception {
        isolationCheck();
        if (args.length != 4) throw new IllegalArgumentException("MODE CLIENTS JOBS REPORT required");
        String mode = modeName(args[0]);
        int clients = Integer.parseInt(args[1]);
        int jobs = Integer.parseInt(args[2]);
        if ((clients != 1 && clients != 4 && clients != 8) || jobs != 20)
            throw new IllegalArgumentException("Only 1/4/8 clients and 20 jobs are allowed");
        Path destination = Path.of(args[3]).toAbsolutePath().normalize();
        if (!destination.startsWith(Path.of(".local/connection-compare").toAbsolutePath().normalize())
                || Files.exists(destination))
            throw new IllegalArgumentException("Invalid or existing output destination");
        String data = run(clients, mode, jobs);
        Files.writeString(destination, data, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        System.out.println("Verified " + mode + " at " + clients + " clients");
    }
}
