package dev.asritha.intake;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.SQLException;
import java.util.HexFormat;

/** Executes a bounded, deterministic SHA-256 task. Never runs payload text as commands. */
public final class JobWorker {
    @FunctionalInterface public interface Processor { String process(String payload) throws Exception; }
    private final WorkerStore store;
    private final Processor processor;
    public JobWorker(WorkerStore store, Processor processor) { this.store = store; this.processor = processor; }
    public static String sha256(String payload) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload.getBytes(StandardCharsets.UTF_8)));
    }
    public boolean runOnce() throws SQLException {
        WorkerStore.Claim claim = store.claim(30);
        if (claim == null) return false;
        String result;
        try { result = processor.process(claim.payload()); }
        catch (Exception failure) {
            store.fail(claim);
            return true;
        }
        // A failed completion write leaves the lease for recovery, rather than inventing success.
        store.succeed(claim, result);
        return true;
    }
    public static void main(String[] args) throws Exception {
        if (args.length > 1 || (args.length == 1 && !args[0].equals("--once")))
            throw new IllegalArgumentException("Usage: worker.sh [--once]");
        JobWorker worker = new JobWorker(new WorkerStore(DatabaseConfig.fromEnvironment()), JobWorker::sha256);
        if (args.length == 1) { worker.runOnce(); return; }
        Thread main = Thread.currentThread();
        Runtime.getRuntime().addShutdownHook(new Thread(main::interrupt));
        System.out.println("SHA-256 worker started; polling PostgreSQL for accepted jobs.");
        while (!Thread.currentThread().isInterrupted()) {
            try { if (worker.runOnce()) continue; }
            catch (SQLException unavailable) { System.err.println("Worker storage unavailable; retrying polling."); }
            try { Thread.sleep(1000); }
            catch (InterruptedException stop) { Thread.currentThread().interrupt(); }
        }
    }
}
