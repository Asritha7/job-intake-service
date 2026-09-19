package dev.asritha.intake;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicLong;
import dev.asritha.intake.JobStore.Job;
import dev.asritha.intake.JobStore.Submission;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** HTTP job acceptance with a pluggable durable store. */
public final class JobIntakeServer implements AutoCloseable {
    private final HttpServer server;
    private final ExecutorService executor = Executors.newFixedThreadPool(8);
    private final JobStore store;
    private final AtomicLong created = new AtomicLong(), replayed = new AtomicLong();
    private final AtomicLong conflicts = new AtomicLong(), full = new AtomicLong();

    public JobIntakeServer(int port, int capacity) throws IOException {
        this(port, new InMemoryJobStore(capacity));
    }
    public JobIntakeServer(int port, JobStore store) throws IOException {
        this.store = java.util.Objects.requireNonNull(store);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 64);
        server.createContext("/", this::handle);
        server.setExecutor(executor);
    }
    public void start() { server.start(); }
    public int port() { return server.getAddress().getPort(); }

    private String metrics() {
        return "jobs_created_total " + created + "\n"
            + "jobs_replayed_total " + replayed + "\n"
            + "jobs_conflicts_total " + conflicts + "\n"
            + "jobs_capacity_rejections_total " + full + "\n";
    }
    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            String path = exchange.getRequestURI().getPath();
            String method = exchange.getRequestMethod();
            try {
            if (path.equals("/ready")) {
                if (!method.equals("GET")) { methodNotAllowed(exchange, "GET"); return; }
                if (store.ready()) respond(exchange, 200, "application/json", "{\"status\":\"ready\"}");
                else error(exchange, 503, "storage_unavailable");
            } else if (path.equals("/health")) {
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
                Job job = store.find(path.substring(6));
                if (job == null) error(exchange, 404, "job_not_found");
                else respond(exchange, 200, "application/json", job.json());
            } else error(exchange, 404, "route_not_found");
            } catch (SQLException unavailable) {
                // Do not disclose JDBC URLs, credentials, payloads or driver error text.
                error(exchange, 503, "storage_unavailable");
            }
        }
    }
    private void accept(HttpExchange exchange) throws IOException, SQLException {
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
        if (payload.indexOf(0) >= 0) { error(exchange, 400, "invalid_text"); return; }
        if (payload.isBlank()) { error(exchange, 400, "empty_payload"); return; }
        Submission result = store.submit(key, payload);
        switch (result.status()) {
            case 201 -> created.incrementAndGet();
            case 200 -> replayed.incrementAndGet();
            case 409 -> conflicts.incrementAndGet();
            case 503 -> full.incrementAndGet();
            default -> throw new IllegalStateException("Unexpected store status");
        }
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
        JobStore store = "memory".equals(System.getenv("JOB_STORE"))
            ? new InMemoryJobStore(1000) : new PostgresJobStore(DatabaseConfig.fromEnvironment());
        JobIntakeServer application = new JobIntakeServer(port, store);
        Runtime.getRuntime().addShutdownHook(new Thread(application::close));
        application.start();
        System.out.println("Job intake listening on http://127.0.0.1:" + application.port());
    }
}
