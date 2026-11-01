package dev.asritha.intake;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;

/**
 * Offline measurement wrapper. Samples pg_stat_activity while an explicit
 * benchmark child process runs, never modifies job tables or application code.
 */
public final class PgWaitSampler {
    private PgWaitSampler() {}

    static String category(String type, String event, String query) {
        if (type == null) return "not_waiting";
        if ("Lock".equals(type) && query != null
                && query.contains("SELECT capacity FROM intake_config WHERE singleton = TRUE FOR UPDATE"))
            return "capacity_lock_wait";
        return switch (type) {
            case "Lock" -> "other_lock_wait";
            case "IO" -> "io_wait";
            case "Client" -> "client_wait";
            case "LWLock" -> "lightweight_lock_wait";
            default -> "other_wait";
        };
    }

    static String jsonString(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    static String report(Map<String,Long> counts, long samples, long backendObservations,
                         long errors, long elapsedMillis, int childStatus) {
        StringBuilder out = new StringBuilder("{\n  \"schema_version\": 1,\n");
        out.append("  \"source\": \"pg_stat_activity_sampling\",\n");
        out.append("  \"sample_interval_ms\": 10,\n");
        out.append("  \"sample_rounds\": ").append(samples).append(",\n");
        out.append("  \"backend_observations\": ").append(backendObservations).append(",\n");
        out.append("  \"sampler_errors\": ").append(errors).append(",\n");
        out.append("  \"elapsed_ms\": ").append(elapsedMillis).append(",\n");
        out.append("  \"benchmark_exit_code\": ").append(childStatus).append(",\n");
        out.append("  \"observations\": {");
        boolean first = true;
        for (var entry : counts.entrySet()) {
            if (!first) out.append(",");
            out.append("\n    ").append(jsonString(entry.getKey())).append(": ").append(entry.getValue());
            first = false;
        }
        if (!counts.isEmpty()) out.append("\n  ");
        out.append("},\n  \"limitations\": \"Sampling does not measure lock duration, exact time attribution or a causal bottleneck; missed short waits are possible.\"\n}\n");
        return out.toString();
    }

    public static void main(String[] args) throws Exception {
        if (!"true".equals(System.getenv("GITHUB_ACTIONS"))
                || !"isolated-postgres".equals(System.getenv("RUN_INTAKE_PERF_CI"))) {
            throw new IllegalArgumentException("Only isolated GitHub Actions performance jobs are supported");
        }
        if (args.length < 3 || !"--".equals(args[1])) {
            throw new IllegalArgumentException("Usage: PgWaitSampler OUTPUT.json -- COMMAND [ARGS...]");
        }
        Path output = Path.of(args[0]);
        if (!output.toAbsolutePath().normalize().startsWith(
                Path.of(".local/performance-ci").toAbsolutePath().normalize())) {
            throw new IllegalArgumentException("Output must be within .local/performance-ci");
        }
        if (Files.exists(output)) throw new IllegalArgumentException("Output already exists");
        if (!"jdbc:postgresql://127.0.0.1:5432/jobs".equals(System.getenv("DATABASE_URL"))
                || !"public".equals(System.getenv().getOrDefault("DATABASE_SCHEMA", "public"))) {
            throw new IllegalArgumentException("Unexpected database endpoint");
        }
        // Restrict the command to the known bounded local benchmark, not arbitrary shell.
        if (!"python3".equals(args[2]) || args.length < 5
                || !"scripts/performance_baseline.py".equals(args[3])) {
            throw new IllegalArgumentException("Only the bounded performance baseline client is permitted");
        }

        String sql = "SELECT wait_event_type, wait_event, query FROM pg_stat_activity "
                + "WHERE datname = current_database() AND usename = current_user "
                + "AND pid <> pg_backend_pid() AND backend_type = 'client backend'";
        Map<String,Long> counts = new TreeMap<>();
        long rounds = 0, observations = 0, failures = 0;
        long start = System.nanoTime();
        int childStatus;
        try (Connection connection = DatabaseConfig.fromEnvironment().connect();
             var statement = connection.prepareStatement(sql)) {
            connection.setReadOnly(true);
            var command = java.util.Arrays.asList(args).subList(2, args.length);
            Process child = new ProcessBuilder(command).inheritIO().start();
            try {
                while (child.isAlive()) {
                    try (var rows = statement.executeQuery()) {
                        rounds++;
                        while (rows.next()) {
                            observations++;
                            counts.merge(category(rows.getString(1), rows.getString(2), rows.getString(3)),
                                    1L, Long::sum);
                        }
                    } catch (java.sql.SQLException failure) {
                        failures++;
                    }
                    Thread.sleep(10);
                }
                childStatus = child.waitFor();
            } catch (InterruptedException interruption) {
                child.destroy();
                if (!child.waitFor(3, TimeUnit.SECONDS)) child.destroyForcibly();
                Thread.currentThread().interrupt();
                throw interruption;
            }
        }
        String outputText = report(counts, rounds, observations, failures,
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start), childStatus);
        Files.writeString(output, outputText, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        if (childStatus != 0 || failures != 0 || rounds == 0) {
            throw new IllegalStateException("Sampler or benchmark failed: exit=" + childStatus
                    + ", sample_errors=" + failures + ", rounds=" + rounds);
        }
    }
}
