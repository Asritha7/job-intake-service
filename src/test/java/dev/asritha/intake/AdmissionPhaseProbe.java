package dev.asritha.intake;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

/** CI-only direct-store phase probe; never part of the deployed HTTP server. */
public final class AdmissionPhaseProbe {
    private AdmissionPhaseProbe() {}

    static void requireIsolation() {
        if (!"true".equals(System.getenv("GITHUB_ACTIONS"))
                || !"isolated-postgres".equals(System.getenv("RUN_INTAKE_PERF_CI"))
                || !"jdbc:postgresql://127.0.0.1:5432/jobs".equals(System.getenv("DATABASE_URL"))
                || !"public".equals(System.getenv().getOrDefault("DATABASE_SCHEMA", "public"))
                || !"jobs".equals(System.getenv("DATABASE_USER"))) {
            throw new IllegalArgumentException("Phase probe runs only in disposable CI PostgreSQL");
        }
    }

    static final class Recorder implements BiConsumer<String,Long> {
        private final Map<String,ArrayList<Long>> phases = new TreeMap<>();
        @Override public synchronized void accept(String stage, Long nanos) {
            if (nanos < 0) throw new IllegalArgumentException("Negative elapsed time");
            phases.computeIfAbsent(stage, ignored -> new ArrayList<>()).add(nanos);
        }
        synchronized Map<String,ArrayList<Long>> snapshot() {
            Map<String,ArrayList<Long>> copy = new TreeMap<>();
            phases.forEach((key, values) -> copy.put(key, new ArrayList<>(values)));
            return copy;
        }
        synchronized void clear() { phases.clear(); }
    }

    static long percentile(ArrayList<Long> sorted, double fraction) {
        return sorted.get(Math.max(0, (int)Math.ceil(sorted.size() * fraction) - 1));
    }

    static String metrics(Recorder recorder, int operations, long elapsedNanos) {
        StringBuilder json = new StringBuilder("{\"operations\":" + operations
                + ",\"elapsed_ms\":" + (elapsedNanos / 1_000_000.0) + ",\"stages\":{");
        boolean first = true;
        for (var entry : recorder.snapshot().entrySet()) {
            ArrayList<Long> samples = entry.getValue();
            Collections.sort(samples);
            if (!first) json.append(",");
            first = false;
            json.append("\"").append(entry.getKey()).append("\":{\"count\":")
                .append(samples.size()).append(",\"p50_ms\":")
                .append(percentile(samples, .50) / 1_000_000.0)
                .append(",\"p95_ms\":").append(percentile(samples, .95) / 1_000_000.0)
                .append(",\"sum_ms\":")
                .append(samples.stream().mapToLong(Long::longValue).sum() / 1_000_000.0)
                .append("}");
        }
        return json.append("}}").toString();
    }

    static void execute(int concurrency, ArrayList<Callable<Void>> work) throws Exception {
        try (var executor = Executors.newFixedThreadPool(concurrency)) {
            ArrayList<Future<Void>> futures = new ArrayList<>();
            for (var operation : work) futures.add(executor.submit(operation));
            for (var future : futures) future.get();
        }
    }

    public static void main(String[] args) throws Exception {
        requireIsolation();
        if (args.length != 3) throw new IllegalArgumentException("Usage: AdmissionPhaseProbe CLIENTS JOBS REPORT");
        int concurrency = Integer.parseInt(args[0]);
        int jobs = Integer.parseInt(args[1]);
        if (!(concurrency == 1 || concurrency == 4 || concurrency == 8) || jobs != 30)
            throw new IllegalArgumentException("Only bounded 1/4/8 clients, 30 jobs is supported");
        Path output = Path.of(args[2]).toAbsolutePath().normalize();
        if (!output.startsWith(Path.of(".local/performance-ci").toAbsolutePath().normalize()))
            throw new IllegalArgumentException("Report must be under .local/performance-ci");
        if (Files.exists(output)) throw new IllegalArgumentException("Report already exists");

        Recorder recorder = new Recorder();
        PostgresJobStore store = new PostgresJobStore(DatabaseConfig.fromEnvironment(), recorder);
        String prefix = "phase-" + UUID.randomUUID().toString().replace("-", "").substring(0,12) + "-";
        ArrayList<Callable<Void>> creates = new ArrayList<>();
        for (int index = 0; index < jobs; index++) {
            final String key = prefix + index;
            final String body = "phase-probe-" + index;
            creates.add(() -> {
                var result = store.submit(key, body);
                if (result.status() != 201) throw new IllegalStateException("Expected create 201");
                return null;
            });
        }
        long started = System.nanoTime();
        execute(concurrency, creates);
        String createMetrics = metrics(recorder, jobs, System.nanoTime() - started);

        recorder.clear();
        ArrayList<Callable<Void>> replays = new ArrayList<>();
        for (int index = 0; index < 15; index++) {
            final String key = prefix + index;
            final String body = "phase-probe-" + index;
            replays.add(() -> {
                var result = store.submit(key, body);
                if (result.status() != 200) throw new IllegalStateException("Expected replay 200");
                return null;
            });
        }
        started = System.nanoTime();
        execute(concurrency, replays);
        String replayMetrics = metrics(recorder, replays.size(), System.nanoTime() - started);

        String json = "{\"schema_version\":1,\"source\":\"instrumented_real_postgres_store\","
                + "\"client_threads\":" + concurrency + ",\"new\":" + createMetrics
                + ",\"replay\":" + replayMetrics
                + ",\"limitations\":\"Direct-store phase observations, not HTTP request attribution; instrumentation adds overhead, no control comparison or production SLA.\"}\n";
        Files.writeString(output, json, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        System.out.println("Phase timing probe completed: " + concurrency + " clients");
    }
}
