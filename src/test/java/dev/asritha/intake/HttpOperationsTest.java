package dev.asritha.intake;

import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/** Real sockets verify overload, slow-client cleanup, metrics, and bounded shutdown. */
public final class HttpOperationsTest {
    private static int assertions;
    private static void check(boolean condition, String message) {
        assertions++;
        if (!condition) throw new AssertionError(message);
    }
    private static void until(BooleanSupplier condition) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() >= end) throw new AssertionError("Timed out awaiting server state");
            Thread.sleep(10);
        }
    }
    private static double metric(JobIntakeServer server, String name) {
        return server.metrics().lines().filter(line -> line.startsWith(name + " "))
            .mapToDouble(line -> Double.parseDouble(line.substring(name.length() + 1))).findFirst().orElseThrow();
    }
    private static Socket request(int port, String text) throws Exception {
        Socket socket = new Socket("127.0.0.1", port);
        socket.setSoTimeout(8000);
        socket.getOutputStream().write(text.getBytes(StandardCharsets.US_ASCII));
        socket.getOutputStream().flush();
        return socket;
    }
    private static String read(Socket socket) throws Exception {
        return new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    }
    private static boolean disconnected(Socket socket) throws Exception {
        try { return socket.getInputStream().read() == -1; }
        catch (SocketException reset) { return true; }
    }
    private static String get(int port, String path) throws Exception {
        try (Socket socket = request(port, "GET " + path + " HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n")) {
            return read(socket);
        }
    }
    public static void main(String[] args) throws Exception {
        System.setProperty("intake.http.requestSeconds", "2");
        CountDownLatch entered = new CountDownLatch(2), release = new CountDownLatch(1);
        var unavailable = new java.util.concurrent.atomic.AtomicBoolean();
        JobStore blocked = new JobStore() {
            private final JobStore delegate = new InMemoryJobStore(10);
            public Submission submit(String key, String payload) throws SQLException { return delegate.submit(key, payload); }
            public Job find(String id) throws SQLException { return delegate.find(id); }
            public boolean ready() throws SQLException {
                entered.countDown();
                try { if (!release.await(10, TimeUnit.SECONDS)) throw new SQLException("test deadline"); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new SQLException(interrupted); }
                if (unavailable.get()) throw new SQLException("private database details");
                return true;
            }
        };
        var sockets = new ArrayList<Socket>();
        try (var server = new JobIntakeServer(0, blocked, 2, 2)) {
            server.start();
            String ready = "GET /ready HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n";
            try {
                sockets.add(request(server.port(), ready)); sockets.add(request(server.port(), ready));
                check(entered.await(5, TimeUnit.SECONDS), "two requests occupy both threads");
                sockets.add(request(server.port(), ready)); sockets.add(request(server.port(), ready));
                until(() -> metric(server, "http_executor_queued") == 2);
                try (Socket excess = request(server.port(), ready)) {
                    check(disconnected(excess), "overload closes excess connection without hanging");
                }
                check(metric(server, "http_executor_rejections_total") == 1, "rejection counted");
                check(metric(server, "http_executor_active") == 2 && metric(server, "http_executor_queued") == 2, "thread and queue bounds held");
            } finally { release.countDown(); }
            for (Socket socket : sockets) check(read(socket).startsWith("HTTP/1.1 200"), "admitted request completes");
            until(() -> metric(server, "http_requests_in_flight") == 0);
            check(get(server.port(), "/health").startsWith("HTTP/1.1 200"), "server recovers from overload");
            unavailable.set(true);
            String failed = get(server.port(), "/ready");
            check(failed.startsWith("HTTP/1.1 503") && !failed.contains("private database"), "storage errors are safe HTTP responses");
            check(get(server.port(), "/unknown-secret-path").startsWith("HTTP/1.1 404"), "client error observed");
            check(metric(server, "http_storage_errors_total") == 1, "storage errors counted separately");
            check(metric(server, "http_responses_total{class=\"5xx\"}") == 1 && metric(server, "http_responses_total{class=\"4xx\"}") == 1, "bounded response class counters");
            // Send a declared body, but never finish it. No job may be accepted.
            try (Socket slow = request(server.port(), "POST /jobs HTTP/1.1\r\nHost: localhost\r\nContent-Type: text/plain\r\nIdempotency-Key: slow\r\nContent-Length: 5\r\n\r\na")) {
                check(disconnected(slow), "incomplete body times out");
            }
            until(() -> metric(server, "http_requests_in_flight") == 0);
            check(metric(server, "jobs_created_total") == 0 && metric(server, "http_io_errors_total") >= 1, "timed-out body creates no job and releases handler");
            try (Socket headers = request(server.port(), "GET /health HTTP/1.1\r\nHost:")) {
                check(disconnected(headers), "incomplete headers time out before handler");
            }
            try (Socket invalid = request(server.port(), "POST /jobs HTTP/1.1\r\nHost: localhost\r\nContent-Length: 8000\r\n\r\n")) {
                check(read(invalid).startsWith("HTTP/1.1 400"), "invalid request rejects without draining missing body");
            }
            String metrics = get(server.port(), "/metrics");
            check(metrics.startsWith("HTTP/1.1 200") && metrics.contains("http_executor_rejections_total 1"), "metrics available after recovery");
            check(!metrics.contains("unknown-secret-path") && !metrics.contains("private database"), "metrics contain no unbounded client data");
            check(metric(server, "http_handler_duration_seconds_count") > 0 && metric(server, "http_handler_duration_seconds_sum") > 0, "handler duration recorded");
        } finally { release.countDown(); for (Socket socket : sockets) socket.close(); }
        // An in-flight request is allowed to finish during the five-second stop grace period.
        CountDownLatch running = new CountDownLatch(1), finish = new CountDownLatch(1);
        JobStore draining = new JobStore() {
            public Submission submit(String k, String p) { throw new UnsupportedOperationException(); }
            public Job find(String id) { return null; }
            public boolean ready() throws SQLException {
                running.countDown();
                try { finish.await(); return true; }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new SQLException(e); }
            }
        };
        var server = new JobIntakeServer(0, draining);
        server.start();
        try (Socket active = request(server.port(), "GET /ready HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n")) {
            check(running.await(5, TimeUnit.SECONDS), "request entered before shutdown");
            CountDownLatch stopStarted = new CountDownLatch(1);
            var stopping = java.util.concurrent.CompletableFuture.runAsync(() -> { stopStarted.countDown(); server.close(); });
            check(stopStarted.await(5, TimeUnit.SECONDS), "shutdown task started");
            Thread.sleep(100);
            check(!stopping.isDone(), "shutdown allows in-flight request a grace period");
            finish.countDown();
            check(read(active).startsWith("HTTP/1.1 200"), "in-flight response completes during shutdown");
            stopping.get(7, TimeUnit.SECONDS);
        } finally { finish.countDown(); server.close(); }
        CountDownLatch stuckEntered = new CountDownLatch(1), cancelled = new CountDownLatch(1);
        JobStore stuck = new JobStore() {
            public Submission submit(String k, String p) { throw new UnsupportedOperationException(); }
            public Job find(String id) { return null; }
            public boolean ready() throws SQLException {
                stuckEntered.countDown();
                try { new CountDownLatch(1).await(); return true; }
                catch (InterruptedException e) { cancelled.countDown(); Thread.currentThread().interrupt(); throw new SQLException(e); }
            }
        };
        var boundedStop = new JobIntakeServer(0, stuck);
        boundedStop.start();
        try (Socket active = request(boundedStop.port(), "GET /ready HTTP/1.1\r\nHost: localhost\r\n\r\n")) {
            check(stuckEntered.await(5, TimeUnit.SECONDS), "stuck request entered");
            var stopping = java.util.concurrent.CompletableFuture.runAsync(boundedStop::close);
            stopping.get(7, TimeUnit.SECONDS);
            check(disconnected(active), "grace expiry closes stuck exchange");
            check(cancelled.await(2, TimeUnit.SECONDS), "grace expiry interrupts executor task");
        } finally { boundedStop.close(); }
        System.out.println("Passed " + assertions + " HTTP operations assertions.");
    }
}
