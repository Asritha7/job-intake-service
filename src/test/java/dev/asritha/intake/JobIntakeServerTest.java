package dev.asritha.intake;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;

/** Integration tests exercise real HTTP requests; no third-party test dependency. */
public final class JobIntakeServerTest {
    private static final HttpClient CLIENT = HttpClient.newHttpClient();
    private static int assertions;
    private static void check(boolean condition, String message) {
        assertions++;
        if (!condition) throw new AssertionError(message);
    }
    private static HttpRequest post(String base, String key, String body) {
        return HttpRequest.newBuilder(URI.create(base + "/jobs")).timeout(Duration.ofSeconds(5))
            .header("Idempotency-Key", key).header("Content-Type", "text/plain")
            .POST(HttpRequest.BodyPublishers.ofString(body)).build();
    }
    private static HttpResponse<String> send(HttpRequest request) throws Exception {
        return CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
    }
    private static HttpResponse<String> get(String url) throws Exception {
        return send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(5)).GET().build());
    }
    public static void main(String[] args) throws Exception {
        try (JobIntakeServer server = new JobIntakeServer(0, 2)) {
            server.start();
            String base = "http://127.0.0.1:" + server.port();
            check(get(base + "/health").statusCode() == 200, "health");
            var created = send(post(base, "job-1", "send daily report"));
            check(created.statusCode() == 201, "create");
            check(send(post(base, "job-1", "send daily report")).body().equals(created.body()), "stable replay ID");
            check(send(post(base, "job-1", "send daily report")).statusCode() == 200, "replay status");
            check(send(post(base, "job-1", "different payload")).statusCode() == 409, "conflict");
            check(get(base + created.headers().firstValue("Location").orElseThrow()).body().equals(created.body()), "lookup");
            check(send(post(base, "", "a")).statusCode() == 400, "missing key");
            check(send(post(base, "blank", " ")).statusCode() == 400, "blank body");
            check(send(post(base, "oversize", "x".repeat(8193))).statusCode() == 413, "body bound");
            var invalidUtf8 = HttpRequest.newBuilder(URI.create(base + "/jobs"))
                .header("Idempotency-Key", "utf8").header("Content-Type", "text/plain")
                .POST(HttpRequest.BodyPublishers.ofByteArray(new byte[]{(byte) 0xff})).build();
            check(send(invalidUtf8).statusCode() == 400, "UTF-8 validation");
            check(send(HttpRequest.newBuilder(URI.create(base + "/jobs")).header("Idempotency-Key", "json")
                .POST(HttpRequest.BodyPublishers.ofString("{}")).build()).statusCode() == 415, "media type");
            check(get(base + "/jobs").statusCode() == 405, "method");
            check(get(base + "/unknown").statusCode() == 404, "unknown route");
            check(get(base + "/jobs/00000000-0000-0000-0000-000000000000").statusCode() == 404, "unknown ID");

            var requests = new ArrayList<CompletableFuture<HttpResponse<String>>>();
            for (int i = 0; i < 24; i++) requests.add(CLIENT.sendAsync(post(base, "concurrent", "same"), HttpResponse.BodyHandlers.ofString()));
            CompletableFuture.allOf(requests.toArray(CompletableFuture[]::new)).join();
            check(requests.stream().filter(f -> f.join().statusCode() == 201).count() == 1, "exactly one concurrent creation");
            check(requests.stream().filter(f -> f.join().statusCode() == 200).count() == 23, "all other requests replay");
            check(requests.stream().map(f -> f.join().body()).distinct().count() == 1, "same concurrent ID");
            check(send(post(base, "third", "full")).statusCode() == 503, "capacity");
            check(send(post(base, "job-1", "send daily report")).statusCode() == 200, "replays work when full");
            check(get(base + "/metrics").body().contains("jobs_created_total 2\n"), "creation metric");
            check(get(base + "/metrics").body().contains("jobs_capacity_rejections_total 1\n"), "capacity metric");
        }
        System.out.println("Passed " + assertions + " HTTP integration assertions.");
    }
}
