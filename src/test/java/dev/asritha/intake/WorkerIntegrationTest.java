package dev.asritha.intake;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Tests real PostgreSQL and a child process killed immediately after claiming work. */
public final class WorkerIntegrationTest {
    private static int assertions;
    private static void check(boolean value, String message) {
        assertions++;
        if (!value) throw new AssertionError(message);
    }
    private static void sql(DatabaseConfig config, String statement) throws Exception {
        try (var connection = config.connect(); var query = connection.createStatement()) { query.execute(statement); }
    }
    private static void expire(DatabaseConfig config, String id) throws Exception {
        sql(config, "UPDATE jobs SET lease_until=clock_timestamp()-INTERVAL '1 second' WHERE id='" + UUID.fromString(id) + "'");
    }
    private static void due(DatabaseConfig config, String id) throws Exception {
        sql(config, "UPDATE jobs SET next_attempt_at=clock_timestamp()-INTERVAL '1 second' WHERE id='" + UUID.fromString(id) + "'");
    }
    private static Process child(String schema, String className, String... args) throws Exception {
        var command = new java.util.ArrayList<String>();
        command.add(System.getProperty("java.home") + "/bin/java"); command.add("-cp");
        command.add(System.getProperty("java.class.path")); command.add(className);
        command.addAll(java.util.List.of(args));
        var builder = new ProcessBuilder(command).inheritIO();
        builder.environment().put("DATABASE_SCHEMA", schema);
        return builder.start();
    }
    private static void await(Process process) throws Exception {
        if (!process.waitFor(15, TimeUnit.SECONDS)) { process.destroyForcibly().waitFor(); throw new AssertionError("Child timed out"); }
        check(process.exitValue() == 0, "child exit status");
    }
    public static void main(String[] args) throws Exception {
        if (args.length == 1 && args[0].equals("claim-and-halt")) {
            var claim = new WorkerStore(DatabaseConfig.fromEnvironment()).claim(30);
            if (claim == null) throw new AssertionError("No job to abandon");
            Runtime.getRuntime().halt(0); // No shutdown hook or completion write, like a crashed worker.
        }
        DatabaseConfig admin = DatabaseConfig.fromEnvironment();
        String schema = "worker_test_" + UUID.randomUUID().toString().replace("-", "");
        DatabaseConfig config = admin.withSchema(schema);
        sql(admin, "CREATE SCHEMA " + schema);
        try {
            // Simulate an existing database from the previous release, without a migration ledger.
            sql(config, Files.readString(Path.of("db/migrations/001_job_intake.sql")));
            String legacy = UUID.randomUUID().toString();
            sql(config, "INSERT INTO jobs(id,idempotency_key,payload) VALUES ('" + legacy + "','legacy','hello')");
            Migrate.apply(config);
            Migrate.apply(config);
            PostgresJobStore jobs = new PostgresJobStore(config);
            WorkerStore store = new WorkerStore(config);
            check(jobs.find(legacy).status().equals("accepted"), "upgrade retains existing job");
            await(child(schema, JobWorker.class.getName(), "--once"));
            var completed = jobs.find(legacy);
            check(completed.status().equals("succeeded") && completed.attempts() == 1, "worker process completes legacy job");
            check(completed.resultSha256().equals("2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824"), "known SHA-256 result");
            check(jobs.submit("legacy", "hello").job().status().equals("succeeded"), "retry returns current completed state");
            check(jobs.submit("legacy", "changed").status() == 409, "completion does not change key ownership");
            check(store.claim(30) == null, "terminal job not claimed again");

            // Concurrent workers must obtain separate rows, not the same pending job.
            var one = jobs.submit("parallel-1", "one").job();
            var two = jobs.submit("parallel-2", "two").job();
            CountDownLatch start = new CountDownLatch(1);
            var claims = new java.util.ArrayList<CompletableFuture<WorkerStore.Claim>>();
            for (int i=0; i<2; i++) claims.add(CompletableFuture.supplyAsync(() -> {
                try { start.await(); return new WorkerStore(config).claim(30); }
                catch (Exception failure) { throw new RuntimeException(failure); }
            }));
            start.countDown();
            var first = claims.get(0).get(10, TimeUnit.SECONDS);
            var second = claims.get(1).get(10, TimeUnit.SECONDS);
            check(first != null && second != null && !first.id().equals(second.id()), "distinct concurrent claims");
            check(store.claim(30) == null, "active leases exclude other workers");
            check(store.succeed(first, JobWorker.sha256(first.payload())), "first completion accepted");
            check(store.succeed(second, JobWorker.sha256(second.payload())), "second completion accepted");
            check(!store.fail(first), "terminal job rejects late failure");

            var crash = jobs.submit("crash", "recover me").job();
            await(child(schema, WorkerIntegrationTest.class.getName(), "claim-and-halt"));
            check(jobs.find(crash.id()).status().equals("running"), "crashed process leaves running lease");
            check(store.claim(30) == null, "unexpired abandoned lease is retained");
            expire(config, crash.id());
            var recovered = store.claim(30);
            check(recovered.id().equals(crash.id()) && recovered.attempt() == 2, "expired lease is recovered");
            check(store.succeed(recovered, JobWorker.sha256(recovered.payload())), "recovered job finishes");

            var stale = jobs.submit("stale", "fenced").job();
            var old = store.claim(30);
            expire(config, stale.id());
            check(!store.succeed(old, JobWorker.sha256("fenced")), "expired token cannot complete even before reclaim");
            var newer = store.claim(30);
            check(!store.succeed(old, JobWorker.sha256("fenced")), "replaced token cannot overwrite newer attempt");
            check(!store.fail(old), "replaced token cannot reschedule newer attempt");
            check(store.succeed(newer, JobWorker.sha256("fenced")), "current owner completes");

            var failure = jobs.submit("failure", "synthetic").job();
            JobWorker broken = new JobWorker(store, payload -> { throw new Exception("private exception text"); });
            check(broken.runOnce(), "failed processor attempted work");
            var pending = jobs.find(failure.id());
            check(pending.status().equals("accepted") && pending.attempts() == 1, "failure requeues job");
            check(pending.lastError().equals("execution_failed"), "exception details not persisted");
            // Check the persisted scheduling deadline; force it far forward for a non-timing-sensitive skip assertion.
            try (var connection = config.connect(); var query = connection.createStatement();
                 var result = query.executeQuery("SELECT next_attempt_at > created_at FROM jobs WHERE id='" + failure.id() + "'")) {
                result.next(); check(result.getBoolean(1), "failure schedules a retry delay");
            }
            sql(config, "UPDATE jobs SET next_attempt_at=clock_timestamp()+INTERVAL '1 hour' WHERE id='" + failure.id() + "'");
            check(store.claim(30) == null, "future retry not claimed early");
            due(config, failure.id()); broken.runOnce();
            due(config, failure.id()); broken.runOnce();
            var exhausted = jobs.find(failure.id());
            check(exhausted.status().equals("failed") && exhausted.attempts() == 3, "retry budget ends at three attempts");
            check(store.claim(30) == null, "permanent failure not retried");

            var abandoned = jobs.submit("abandoned", "three crashes").job();
            for (int i=0; i<3; i++) { check(store.claim(30) != null, "claim crash attempt"); expire(config, abandoned.id()); }
            check(store.claim(30) == null, "expired final attempt is not executed again");
            check(jobs.find(abandoned.id()).status().equals("failed") && jobs.find(abandoned.id()).lastError().equals("lease_expired"), "crash exhaustion is terminal");
            check(jobs.submit("abandoned", "three crashes").job().attempts() == 3, "client retries do not reset execution budget");
            Migrate.apply(config);
            check(jobs.find(legacy).status().equals("succeeded"), "migration rerun preserves result");
            sql(config, "UPDATE schema_migrations SET sha256='tampered' WHERE name='002_worker_lifecycle.sql'");
            try { Migrate.apply(config); throw new AssertionError("Changed migration accepted"); }
            catch (java.sql.SQLException expected) { check(true, "checksum drift rejected"); }
            System.out.println("Passed " + assertions + " worker integration assertions, including abrupt child-process exit.");
        } finally { sql(admin, "DROP SCHEMA " + schema + " CASCADE"); }
    }
}
