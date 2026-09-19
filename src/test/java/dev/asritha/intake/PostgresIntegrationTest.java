package dev.asritha.intake;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** Real PostgreSQL only: tests own a unique schema and never truncate an existing one. */
public final class PostgresIntegrationTest {
    private static final HttpClient CLIENT = HttpClient.newHttpClient();
    private static int assertions;
    private static void check(boolean condition, String message) {
        assertions++;
        if (!condition) throw new AssertionError(message);
    }
    private static HttpRequest post(String base, String key, String text) {
        return HttpRequest.newBuilder(URI.create(base + "/jobs")).timeout(Duration.ofSeconds(15))
            .header("Idempotency-Key", key).header("Content-Type", "text/plain")
            .POST(HttpRequest.BodyPublishers.ofString(text)).build();
    }
    private static HttpResponse<String> send(HttpRequest request) throws Exception {
        return CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
    }
    private static HttpResponse<String> get(String url) throws Exception {
        return send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(15)).GET().build());
    }
    private static String base(JobIntakeServer server) { return "http://127.0.0.1:" + server.port(); }
    private static final class Child implements AutoCloseable {
        private final Process process;
        final String base;
        Child(String schema) throws Exception {
            var builder = new ProcessBuilder(System.getProperty("java.home") + "/bin/java", "-cp",
                System.getProperty("java.class.path"), JobIntakeServer.class.getName(), "0");
            builder.environment().put("DATABASE_SCHEMA", schema);
            builder.environment().remove("JOB_STORE");
            builder.redirectError(ProcessBuilder.Redirect.INHERIT);
            process = builder.start();
            try {
                var reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
                String line = CompletableFuture.supplyAsync(() -> {
                    try { return reader.readLine(); } catch (Exception error) { throw new RuntimeException(error); }
                }).get(10, TimeUnit.SECONDS);
                if (line == null || !line.startsWith("Job intake listening on ")) throw new AssertionError("Child did not start");
                base = line.substring("Job intake listening on ".length());
            } catch (Exception | AssertionError failure) { close(); throw failure; }
        }
        public void close() {
            process.destroy();
            try { if (!process.waitFor(5, TimeUnit.SECONDS)) process.destroyForcibly().waitFor(); }
            catch (InterruptedException interrupted) { process.destroyForcibly(); Thread.currentThread().interrupt(); }
        }
    }
    public static void main(String[] args) throws Exception {
        DatabaseConfig admin = DatabaseConfig.fromEnvironment();
        String schema = "intake_test_" + UUID.randomUUID().toString().replace("-", "");
        DatabaseConfig isolated = admin.withSchema(schema);
        try (var connection = admin.connect(); var statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA " + schema);
        }
        try {
            Migrate.apply(isolated);
            Migrate.apply(isolated); // Bootstrap is safe to repeat without clearing jobs/configuration.
            try (var first = new JobIntakeServer(0, new PostgresJobStore(isolated));
                 var second = new JobIntakeServer(0, new PostgresJobStore(isolated))) {
                first.start(); second.start();
                check(get(base(first) + "/ready").statusCode() == 200, "database readiness");
                var requests = new ArrayList<CompletableFuture<HttpResponse<String>>>();
                for (int i = 0; i < 24; i++) requests.add(CLIENT.sendAsync(
                    post(base(i % 2 == 0 ? first : second), "shared", "durable request"), HttpResponse.BodyHandlers.ofString()));
                CompletableFuture.allOf(requests.toArray(CompletableFuture[]::new)).join();
                check(requests.stream().filter(f -> f.join().statusCode() == 201).count() == 1, "one creation across two instances");
                check(requests.stream().filter(f -> f.join().statusCode() == 200).count() == 23, "23 replays across instances");
                check(requests.stream().map(f -> f.join().body()).distinct().count() == 1, "one stable ID");
                check(send(post(base(second), "shared", "changed")).statusCode() == 409, "conflicting payload");
                var a = CLIENT.sendAsync(post(base(first), "race", "A"), HttpResponse.BodyHandlers.ofString());
                var b = CLIENT.sendAsync(post(base(second), "race", "B"), HttpResponse.BodyHandlers.ofString());
                check(java.util.Set.of(a.join().statusCode(), b.join().statusCode()).equals(java.util.Set.of(201, 409)), "conflicting concurrent writers");
                String special = "quotes ' and emoji ☀\nSQL text: DROP TABLE jobs;";
                check(send(post(base(first), "quoted", special)).statusCode() == 201, "parameterized payload storage");
                check(send(post(base(second), "quoted", special)).statusCode() == 200, "exact Unicode payload replay");
                check(send(post(base(first), "nul", "bad\u0000text")).statusCode() == 400, "reject PostgreSQL-incompatible NUL");
                check(get(base(second) + "/jobs/------------------------------------").statusCode() == 404, "invalid UUID is not storage error");
                // Force a constraint failure after locking; the transaction must release its lock and insert nothing.
                try { new PostgresJobStore(isolated).submit("rollback", "x".repeat(8193)); throw new AssertionError("Constraint did not reject"); }
                catch (java.sql.SQLException expected) { check("23514".equals(expected.getSQLState()), "database length constraint"); }
                check(send(post(base(second), "rollback", "valid")).statusCode() == 201, "failed transaction rolled back");
                try (var connection = isolated.connect(); var statement = connection.createStatement()) {
                    try {
                        statement.execute("INSERT INTO jobs(id,idempotency_key,payload) VALUES ('00000000-0000-0000-0000-000000000001','shared','duplicate')");
                        throw new AssertionError("Unique constraint missing");
                    } catch (java.sql.SQLException expected) { check("23505".equals(expected.getSQLState()), "database key uniqueness"); }
                }
                // Four jobs exist. Only one of two distinct submissions can occupy the fifth slot.
                try (var connection = isolated.connect(); var statement = connection.createStatement()) {
                    statement.executeUpdate("UPDATE intake_config SET capacity = 5");
                }
                var c = CLIENT.sendAsync(post(base(first), "last-a", "a"), HttpResponse.BodyHandlers.ofString());
                var d = CLIENT.sendAsync(post(base(second), "last-b", "b"), HttpResponse.BodyHandlers.ofString());
                check(java.util.Set.of(c.join().statusCode(), d.join().statusCode()).equals(java.util.Set.of(201, 503)), "shared capacity under contention");
                check(send(post(base(first), "shared", "durable request")).statusCode() == 200, "replay at capacity");
                check(send(post(base(first), "shared", "different")).statusCode() == 409, "conflict at capacity");
            }
            String body, location;
            try (var child = new Child(schema)) {
                var response = send(post(child.base, "shared", "durable request"));
                check(response.statusCode() == 200, "new JVM sees existing acceptance");
                body = response.body(); location = response.headers().firstValue("Location").orElseThrow();
            }
            Migrate.apply(isolated);
            try (var child = new Child(schema)) {
                var response = send(post(child.base, "shared", "durable request"));
                check(response.statusCode() == 200 && response.body().equals(body), "stable ID after complete JVM restart");
                check(get(child.base + location).body().equals(body), "lookup after restart");
                check(send(post(child.base, "another", "full")).statusCode() == 503, "migration and restart preserve capacity");
            }
            try (var unavailable = new JobIntakeServer(0, new PostgresJobStore(admin.withSchema(schema + "_absent")))) {
                unavailable.start();
                check(get(base(unavailable) + "/health").statusCode() == 200, "liveness independent of storage");
                check(get(base(unavailable) + "/ready").statusCode() == 503, "unmigrated schema is not ready");
                var response = send(post(base(unavailable), "test", "private payload"));
                check(response.statusCode() == 503 && response.body().equals("{\"error\":\"storage_unavailable\"}"), "sanitized storage error");
            }
            System.out.println("Passed " + assertions + " PostgreSQL integration assertions, including child JVM restarts.");
        } finally {
            try (var connection = admin.connect(); var statement = connection.createStatement()) {
                statement.execute("DROP SCHEMA " + schema + " CASCADE");
            }
        }
    }
}
