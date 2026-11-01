package dev.asritha.intake;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Opt-in pool only: exercises HTTP semantics against one isolated PostgreSQL schema. */
public final class PooledPostgresIntegrationTest {
    private static final HttpClient CLIENT = HttpClient.newHttpClient();
    private static int assertions;
    private static void check(boolean condition, String message) {
        assertions++;
        if (!condition) throw new AssertionError(message);
    }
    private static HttpRequest post(JobIntakeServer server, String key, String payload) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + "/jobs"))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "text/plain")
                .header("Idempotency-Key", key)
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build();
    }
    private static HttpResponse<String> send(HttpRequest request) throws Exception {
        return CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
    }
    private static int get(JobIntakeServer server, String path) throws Exception {
        return send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + path))
                .timeout(Duration.ofSeconds(10)).GET().build()).statusCode();
    }
    private static void invalidSettings() {
        for (String invalid : new String[] {"", " ", "-1", "9", "100", "abc", " 2", "2 ", "2.0"}) {
            boolean rejected = false;
            try { PooledPostgresJobStore.parseSize(invalid); }
            catch (IllegalArgumentException expected) { rejected = true; }
            check(rejected, "Invalid pool size accepted: " + invalid);
        }
        check(PooledPostgresJobStore.parseSize(null) == 0, "Missing pool setting must keep default");
        check(PooledPostgresJobStore.parseSize("0") == 0, "Zero disables pool");
        check(PooledPostgresJobStore.parseSize("2") == 2, "Two connections enabled");
    }
    public static void main(String[] args) throws Exception {
        invalidSettings();
        DatabaseConfig admin = DatabaseConfig.fromEnvironment();
        String schema = "pool_test_" + UUID.randomUUID().toString().replace("-", "");
        try (var connection = admin.connect(); var statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA " + schema);
        }
        try {
            DatabaseConfig db = admin.withSchema(schema);
            Migrate.apply(db);
            PooledPostgresJobStore poolA = new PooledPostgresJobStore(db, 2);
            PooledPostgresJobStore poolB = new PooledPostgresJobStore(db, 2);
            try (var first = new JobIntakeServer(0, poolA);
                 var second = new JobIntakeServer(0, poolB)) {
                first.start();
                second.start();
                check(get(first, "/health") == 200, "Pooled API liveness");
                check(get(second, "/ready") == 200, "Pooled database readiness");
                check(poolA.maximumPoolSize() == 2 && poolB.maximumPoolSize() == 2,
                        "Bounded pool sizes");

                var requests = new ArrayList<CompletableFuture<HttpResponse<String>>>();
                for (int index = 0; index < 20; index++) {
                    requests.add(CLIENT.sendAsync(post(index % 2 == 0 ? first : second, "shared", "same"),
                            HttpResponse.BodyHandlers.ofString()));
                }
                CompletableFuture.allOf(requests.toArray(CompletableFuture[]::new)).join();
                check(requests.stream().filter(r -> r.join().statusCode() == 201).count() == 1,
                        "Only one create across pooled instances");
                check(requests.stream().filter(r -> r.join().statusCode() == 200).count() == 19,
                        "All other submissions must replay");
                check(requests.stream().map(r -> r.join().body()).distinct().count() == 1,
                        "One durable job identity across pooled instances");
                check(send(post(second, "shared", "conflict")).statusCode() == 409,
                        "Conflicting request must return 409");

                boolean invalidRejected = false;
                try { poolA.submit("invalid-large", "x".repeat(8193)); }
                catch (java.sql.SQLException expected) {
                    invalidRejected = "23514".equals(expected.getSQLState());
                }
                check(invalidRejected, "Database constraint failure must propagate");
                check(send(post(first, "post-rollback", "valid")).statusCode() == 201,
                        "Failed SQL transaction must not poison pool");

                try (var connection = db.connect(); var statement = connection.createStatement()) {
                    statement.executeUpdate("UPDATE intake_config SET capacity=4");
                }
                var unique = new ArrayList<CompletableFuture<HttpResponse<String>>>();
                for (int index = 0; index < 6; index++) {
                    unique.add(CLIENT.sendAsync(post(index % 2 == 0 ? first : second,
                            "unique-" + index, "payload-" + index),
                            HttpResponse.BodyHandlers.ofString()));
                }
                CompletableFuture.allOf(unique.toArray(CompletableFuture[]::new)).join();
                check(unique.stream().filter(r -> r.join().statusCode() == 201).count() == 2,
                        "Shared capacity admits only two more jobs");
                check(unique.stream().filter(r -> r.join().statusCode() == 503).count() == 4,
                        "Shared capacity rejects remaining unique jobs");
                check(send(post(first, "shared", "same")).statusCode() == 200,
                        "Replay at capacity");
                check(send(post(second, "shared", "conflict")).statusCode() == 409,
                        "Conflict at capacity");
                try (var connection = db.connect();
                     var query = connection.createStatement();
                     var rows = query.executeQuery("SELECT count(*) FROM jobs")) {
                    check(rows.next() && rows.getLong(1) == 4,
                            "Exactly four durable rows at capacity");
                }
            }
            check(poolA.isClosed() && poolB.isClosed(),
                    "HTTP shutdown must release owned pools");

            PooledPostgresJobStore unavailable = new PooledPostgresJobStore(
                    admin.withSchema(schema + "_absent"), 1);
            try (var offline = new JobIntakeServer(0, unavailable)) {
                offline.start();
                check(get(offline, "/health") == 200, "Liveness independent of missing tables");
                check(get(offline, "/ready") == 503, "Missing schema must fail readiness");
                check(send(post(offline, "unavailable", "text")).statusCode() == 503,
                        "Missing schema must fail closed for writes");
            }
            check(unavailable.isClosed(), "Offline pool closed after shutdown");
            System.out.println("Passed " + assertions
                    + " pooled PostgreSQL integration assertions.");
        } finally {
            try (var connection = admin.connect(); var statement = connection.createStatement()) {
                statement.execute("DROP SCHEMA " + schema + " CASCADE");
            }
        }
    }
}
