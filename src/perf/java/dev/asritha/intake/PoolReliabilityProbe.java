package dev.asritha.intake;

import com.zaxxer.hikari.HikariDataSource;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * CI-only scenarios: bounded pool exhaustion, terminated session recovery,
 * and two independent Java processes with independent pools sharing one DB.
 */
public final class PoolReliabilityProbe {
    private PoolReliabilityProbe() {}

    static void check(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }

    static void isolationCheck() {
        PoolComparisonProbe.isolationCheck();
    }

    static void exhaustionAndRecovery() throws Exception {
        try (HikariDataSource pool = PoolComparisonProbe.pool(2)) {
            try (Connection first = pool.getConnection();
                 Connection second = pool.getConnection()) {
                long started = System.nanoTime();
                boolean exhausted = false;
                try (Connection unexpected = pool.getConnection()) {
                    throw new IllegalStateException("Pool supplied a third connection");
                } catch (java.sql.SQLTransientConnectionException expected) {
                    exhausted = true;
                }
                long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
                check(exhausted && elapsedMs >= 2_500 && elapsedMs < 15_000,
                        "Pool did not exhaust within configured acquisition timeout");
                check(pool.getHikariPoolMXBean().getTotalConnections() <= 2,
                        "Pool exceeded its two-connection maximum");
            }
            try (Connection recovered = pool.getConnection()) {
                check(recovered.isValid(2) && recovered.getAutoCommit(),
                        "Pool did not become reusable after returning connections");
                check(recovered.getTransactionIsolation() == Connection.TRANSACTION_READ_COMMITTED,
                        "Unexpected isolation after saturation");
            }
        }
    }

    static void lostBackendRecovery() throws Exception {
        try (HikariDataSource pool = PoolComparisonProbe.pool(1)) {
            long backendPid;
            try (Connection broken = pool.getConnection()) {
                try (var select = broken.createStatement();
                     var rows = select.executeQuery("SELECT pg_backend_pid()")) {
                    check(rows.next(), "Missing backend PID");
                    backendPid = rows.getLong(1);
                }
                try (Connection admin = DatabaseConfig.fromEnvironment().connect();
                     var kill = admin.prepareStatement("SELECT pg_terminate_backend(?)")) {
                    kill.setLong(1, backendPid);
                    try (var result = kill.executeQuery()) {
                        check(result.next() && result.getBoolean(1),
                                "Could not terminate owned ephemeral backend");
                    }
                }
                boolean connectionFailed = false;
                try (var query = broken.createStatement()) {
                    query.execute("SELECT 1");
                } catch (SQLException expected) {
                    connectionFailed = true;
                }
                check(connectionFailed, "Terminated backend remained usable unexpectedly");
            }
            boolean restored = false;
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(12);
            while (System.nanoTime() < deadline && !restored) {
                try (Connection next = pool.getConnection();
                     var select = next.createStatement();
                     var result = select.executeQuery("SELECT 1")) {
                    restored = result.next() && result.getInt(1) == 1 && next.getAutoCommit();
                } catch (SQLException retryable) {
                    Thread.sleep(150);
                }
            }
            check(restored, "Pool failed to replace terminated database backend");
            check(pool.getHikariPoolMXBean().getTotalConnections() <= 1,
                    "Replacement exceeded pool maximum");
        }
    }

    static void awaitFile(Path file, int seconds) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        while (!Files.exists(file) && System.nanoTime() < deadline) Thread.sleep(25);
        check(Files.exists(file), "Timed out at independent-JVM barrier");
    }

    static void child(String prefix, String side, Path ready, Path go, Path output) throws Exception {
        isolationCheck();
        check(side.equals("a") || side.equals("b"), "Unknown worker identity");
        try (HikariDataSource pool = PoolComparisonProbe.pool(2)) {
            var store = new PostgresJobStore(DatabaseConfig.fromEnvironment(), pool::getConnection, null);
            Files.writeString(ready, "ready", StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
            awaitFile(go, 15);
            String sharedKey = prefix + "-shared";
            List<String> sharedResponses = java.util.Collections.synchronizedList(new ArrayList<>());
            ArrayList<Callable<Void>> sharedTasks = new ArrayList<>();
            for (int index = 0; index < 8; index++) {
                sharedTasks.add(() -> {
                    var result = store.submit(sharedKey, "same-payload");
                    check((result.status() == 201 || result.status() == 200) && result.job() != null,
                            "Independent process broke idempotent acceptance");
                    sharedResponses.add(result.status() + "|" + result.job().id());
                    return null;
                });
            }
            AdmissionPhaseProbe.execute(4, sharedTasks);
            long created = sharedResponses.stream().filter(s -> s.startsWith("201|")).count();
            long replayed = sharedResponses.stream().filter(s -> s.startsWith("200|")).count();
            var identities = new HashSet<String>();
            for (String response : sharedResponses) identities.add(response.split("\\|", 2)[1]);
            check(identities.size() == 1 && created + replayed == 8,
                    "One process observed inconsistent shared job identity");

            AtomicInteger uniqueCreated = new AtomicInteger();
            AtomicInteger rejectedAtCapacity = new AtomicInteger();
            ArrayList<Callable<Void>> uniqueTasks = new ArrayList<>();
            for (int index = 0; index < 4; index++) {
                final int jobIndex = index;
                uniqueTasks.add(() -> {
                    var result = store.submit(prefix + "-" + side + "-" + jobIndex,
                            "unique-" + side + "-" + jobIndex);
                    if (result.status() == 201) uniqueCreated.incrementAndGet();
                    else if (result.status() == 503) rejectedAtCapacity.incrementAndGet();
                    else throw new IllegalStateException("Unexpected unique submission status");
                    return null;
                });
            }
            AdmissionPhaseProbe.execute(4, uniqueTasks);
            check(uniqueCreated.get() + rejectedAtCapacity.get() == 4,
                    "Missing unique request outcome");
            check(pool.getHikariPoolMXBean().getTotalConnections() <= 2,
                    "Independent process exceeded two DB connections");

            Properties outcome = new Properties();
            outcome.setProperty("shared_created", Long.toString(created));
            outcome.setProperty("shared_replayed", Long.toString(replayed));
            outcome.setProperty("shared_id", identities.iterator().next());
            outcome.setProperty("unique_created", Integer.toString(uniqueCreated.get()));
            outcome.setProperty("unique_rejected", Integer.toString(rejectedAtCapacity.get()));
            try (var writer = Files.newBufferedWriter(output, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW)) {
                outcome.store(writer, "temporary independent-JVM outcome (not uploaded)");
            }
        }
    }

    static Process launchChild(String prefix, String side, Path directory) throws Exception {
        return new ProcessBuilder(System.getProperty("java.home") + "/bin/java",
                "-cp", System.getProperty("java.class.path"),
                PoolReliabilityProbe.class.getName(), "child", prefix, side,
                directory.resolve(side + ".ready").toString(),
                directory.resolve("go").toString(),
                directory.resolve(side + ".properties").toString())
                .inheritIO().start();
    }

    static Properties readChild(Path file) throws Exception {
        Properties result = new Properties();
        try (var reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            result.load(reader);
        }
        return result;
    }

    static void independentJvmAndCapacity(Path directory) throws Exception {
        // This workflow starts with a fresh database; no existing rows may be changed.
        try (Connection connection = DatabaseConfig.fromEnvironment().connect();
             var statement = connection.createStatement();
             var results = statement.executeQuery("SELECT count(*) FROM jobs")) {
            check(results.next() && results.getLong(1) == 0,
                    "Multi-JVM scenario requires a fresh disposable jobs table");
        }
        try (Connection connection = DatabaseConfig.fromEnvironment().connect();
             var statement = connection.createStatement()) {
            check(statement.executeUpdate("UPDATE intake_config SET capacity = 5 WHERE singleton = TRUE") == 1,
                    "Expected one capacity configuration row");
        }
        String prefix = "resilience-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        Process first = launchChild(prefix, "a", directory);
        Process second = launchChild(prefix, "b", directory);
        try {
            awaitFile(directory.resolve("a.ready"), 15);
            awaitFile(directory.resolve("b.ready"), 15);
            Files.writeString(directory.resolve("go"), "go", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW);
            check(first.waitFor(35, TimeUnit.SECONDS) && first.exitValue() == 0,
                    "First independent JVM did not complete");
            check(second.waitFor(35, TimeUnit.SECONDS) && second.exitValue() == 0,
                    "Second independent JVM did not complete");
        } finally {
            if (first.isAlive()) first.destroyForcibly();
            if (second.isAlive()) second.destroyForcibly();
        }
        Properties a = readChild(directory.resolve("a.properties"));
        Properties b = readChild(directory.resolve("b.properties"));
        check(a.getProperty("shared_id").equals(b.getProperty("shared_id")),
                "Separate JVMs returned different IDs for one idempotency key");
        int sharedCreates = Integer.parseInt(a.getProperty("shared_created"))
                + Integer.parseInt(b.getProperty("shared_created"));
        int sharedReplays = Integer.parseInt(a.getProperty("shared_replayed"))
                + Integer.parseInt(b.getProperty("shared_replayed"));
        int newJobs = Integer.parseInt(a.getProperty("unique_created"))
                + Integer.parseInt(b.getProperty("unique_created"));
        int full = Integer.parseInt(a.getProperty("unique_rejected"))
                + Integer.parseInt(b.getProperty("unique_rejected"));
        check(sharedCreates == 1 && sharedReplays == 15,
                "Expected exactly one creation and 15 replays across processes");
        check(newJobs == 4 && full == 4,
                "Global capacity was not enforced across independent pools");

        try (Connection connection = DatabaseConfig.fromEnvironment().connect();
             var statement = connection.createStatement();
             var results = statement.executeQuery("SELECT count(*) FROM jobs")) {
            check(results.next() && results.getLong(1) == 5,
                    "Persisted jobs exceeded the shared five-job capacity");
        }
        var store = new PostgresJobStore(DatabaseConfig.fromEnvironment());
        var replay = store.submit(prefix + "-shared", "same-payload");
        check(replay.status() == 200 && replay.job().id().equals(a.getProperty("shared_id")),
                "Replay at capacity changed original job ID");
        check(store.submit(prefix + "-shared", "conflict").status() == 409,
                "Conflict at capacity must still return 409");
        check(store.submit(prefix + "-extra", "extra").status() == 503,
                "Unique submission at capacity must still return 503");
    }

    public static void main(String[] args) throws Exception {
        isolationCheck();
        if (args.length == 6 && "child".equals(args[0])) {
            child(args[1], args[2], Path.of(args[3]), Path.of(args[4]), Path.of(args[5]));
            return;
        }
        check(args.length == 1, "Usage: PoolReliabilityProbe REPORT.json");
        Path output = Path.of(args[0]).toAbsolutePath().normalize();
        Path root = Path.of(".local/pool-reliability").toAbsolutePath().normalize();
        check(output.startsWith(root) && !Files.exists(output), "Invalid or existing output");
        Files.createDirectories(root);
        Path childDirectory = root.resolve("children");
        Files.createDirectories(childDirectory);
        exhaustionAndRecovery();
        lostBackendRecovery();
        independentJvmAndCapacity(childDirectory);
        String report = "{\"schema_version\":1,\"source\":\"isolated_pool_resilience\","
                + "\"pool_exhaustion_and_release\":true,\"terminated_backend_recovered\":true,"
                + "\"independent_jvms\":2,\"pool_size_per_jvm\":2,"
                + "\"shared_key_creates\":1,\"shared_key_replays\":15,"
                + "\"unique_creates\":4,\"capacity_rejections\":4,"
                + "\"final_persisted_jobs\":5,\"capacity\":5,"
                + "\"replay_at_capacity\":true,\"conflict_at_capacity\":true}\n";
        Files.writeString(output, report, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        System.out.println("Passed isolated pool resilience and two-independent-JVM invariants.");
    }
}
