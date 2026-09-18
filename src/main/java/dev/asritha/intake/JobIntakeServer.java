package dev.asritha.intake;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Bounded, single-process job intake demonstrating atomic idempotency. */
public final class JobIntakeServer implements AutoCloseable {
    private record Job(String id, String payload) {
        String json() { return "{\"id\":\"" + id + "\",\"status\":\"accepted\"}"; }
    }
    private record Submission(int status, Job job) {}
    private final HttpServer server;
    private final ExecutorService executor = Executors.newFixedThreadPool(8);
    private final Map<String, Job> byKey = new HashMap<>();
    private final Map<String, Job> byId = new HashMap<>();
    private final int capacity;
    private long created, replayed, conflicts, full;

    public JobIntakeServer(int port, int capacity) throws IOException {
        if (capacity < 1) throw new IllegalArgumentException("Capacity must be positive");
        this.capacity = capacity;
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 64);
        server.createContext("/", this::handle);
        server.setExecutor(executor);
    }
    public void start() { server.start(); }
    public int port() { return server.getAddress().getPort(); }

    private synchronized Submission submit(String key, String payload) {
        Job existing = byKey.get(key);
        if (existing != null) {
            if (!existing.payload().equals(payload)) { conflicts++; return new Submission(409, null); }
            replayed++;
            return new Submission(200, existing);
        }
        if (byId.size() >= capacity) { full++; return new Submission(503, null); }
        Job job = new Job(UUID.randomUUID().toString(), payload);
        byKey.put(key, job);
        byId.put(job.id(), job);
        created++;
        return new Submission(201, job);
    }
    private synchronized Job find(String id) { return byId.get(id); }
    private synchronized String metrics() {
        return "jobs_created_total " + created + "\n"
            + "jobs_replayed_total " + replayed + "\n"
            + "jobs_conflicts_total " + conflicts + "\n"
            + "jobs_capacity_rejections_total " + full + "\n";
    }
    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            String path = exchange.getRequestURI().getPath();
            String method = exchange.getRequestMethod();
            if (path.equals("/health")) {
                if (!method.equals("GET")) { methodNotAllowed(exchange, "GET"); return; }
                respond(exchange, 200, "application/json", "{\"status\":\"ok\"}");
            } else if (path.equals("/metrics")) {
                if (!method.equals("GET")) { methodNotAllowed(exchange, "GET"); return; }
                respond(exchange, 200, "text/plain; charset=utf-8", metrics());
            } else if (path.equals("/jobs")) {
                if (!method.equals("POST")) { methodNotAllowed(exchange, "POST"); return; }
                accept(exchange);
            } else if (path.matches("/jobs/[a-f0-9-]{36}")) {
                if (!method.equals("GET")) { methodNotAllowed(exchange, "GET"); return; }
                Job job = find(path.substring(6));
                if (job == null) error(exchange, 404, "job_not_found");
                else respond(exchange, 200, "application/json", job.json());
            } else error(exchange, 404, "route_not_found");
        }
    }
    private void accept(HttpExchange exchange) throws IOException {
        String key = exchange.getRequestHeaders().getFirst("Idempotency-Key");
        if (key == null || !key.matches("[A-Za-z0-9._-]{1,80}")) {
            error(exchange, 400, "invalid_idempotency_key"); return;
        }
        String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
        if (contentType == null || !contentType.split(";", 2)[0].trim().equalsIgnoreCase("text/plain")) {
            error(exchange, 415, "expected_text_plain"); return;
        }
        byte[] body = exchange.getRequestBody().readNBytes(8193);
        if (body.length > 8192) { error(exchange, 413, "payload_too_large"); return; }
        String payload;
        try {
            payload = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(body)).toString();
        } catch (CharacterCodingException invalid) { error(exchange, 400, "invalid_utf8"); return; }
        if (payload.isBlank()) { error(exchange, 400, "empty_payload"); return; }
        Submission result = submit(key, payload);
        if (result.status() == 409) { error(exchange, 409, "idempotency_conflict"); return; }
        if (result.status() == 503) { error(exchange, 503, "capacity_exhausted"); return; }
        exchange.getResponseHeaders().set("Location", "/jobs/" + result.job().id());
        respond(exchange, result.status(), "application/json", result.job().json());
    }
    private static void methodNotAllowed(HttpExchange exchange, String method) throws IOException {
        exchange.getResponseHeaders().set("Allow", method);
        error(exchange, 405, "method_not_allowed");
    }
    private static void error(HttpExchange exchange, int status, String error) throws IOException {
        respond(exchange, status, "application/json", "{\"error\":\"" + error + "\"}");
    }
    private static void respond(HttpExchange exchange, int status, String type, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", type);
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }
    @Override public void close() {
        server.stop(0);
        executor.shutdownNow();
    }
    public static void main(String[] args) throws IOException {
        int port = args.length == 0 ? 8080 : Integer.parseInt(args[0]);
        JobIntakeServer application = new JobIntakeServer(port, 1000);
        Runtime.getRuntime().addShutdownHook(new Thread(application::close));
        application.start();
        System.out.println("Job intake listening on http://127.0.0.1:" + application.port());
    }
}
