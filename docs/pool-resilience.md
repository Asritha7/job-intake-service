# Connection pool resilience and independent-JVM correctness

This is the follow-up to the [isolated Hikari comparison](connection-pool-comparison.md). The earlier PR measured a large difference between fresh connections and prewarmed bounded Hikari pools on a CI-local workload. This PR **does not turn pooling on** in the normal Java API or worker. It verifies failure and multi-process properties that must hold before a future production pool decision.

## Disposable-only execution

The test-only `PoolReliabilityProbe` lives in `src/perf/`. It uses the already-pinned HikariCP and SLF4J libraries from the comparison workflow and requires exact GitHub Actions, benchmark marker, loopback PostgreSQL URL, CI database user, schema and disposable password. A new GitHub Actions service container supplies PostgreSQL 17.11 for this workflow; no local Docker volume, production URL or persistent database is accessed.

The runner calls `scripts/run_pool_comparison_ci.sh --dependencies-only` to prepare checksum-verified libraries without running the previous 567-job benchmark. The standard Java compilation remains free of Hikari dependencies. It then compiles the test-only probe, verifies migrations and runs three scenarios.

## Verified scenarios

1. **Pool exhaustion and bounded recovery.** Borrow both physical connections from a two-connection pool, request a third and expect the configured approximately three-second acquisition timeout. Verify the pool never exceeds size two. Return the borrowed connections and verify the pool becomes usable again with clean auto-commit and isolation settings.
2. **Database backend termination and replacement.** Create a one-connection pool, read its own PostgreSQL backend PID, and terminate **only that session** through the isolated database account. Verify the old connection fails, close it, and poll for a valid replacement without exceeding pool capacity. This is not a full database outage or network partition experiment.
3. **Two independent Java processes.** Start two separate JVMs, each with its own two-connection Hikari pool and the same PostgreSQL configuration. Synchronize their start with temporary filesystem barrier files. Each JVM concurrently submits eight identical requests for one shared idempotency key, then four distinct-key requests. The disposable database has capacity five. Validate exactly one shared-key creation, fifteen replays with the **same job ID in both JVMs**, four unique creations, four unique full-capacity rejections, and exactly five durable rows. Confirm that replay at capacity returns 200, conflicting reuse returns 409 and new unique work returns 503.

The child JVM outcome files include an internal job ID solely to compare identity. They are **not uploaded**. The published JSON report contains only boolean invariants and small integer totals; no keys, job IDs, payloads, credentials, SQL statements or JDBC URLs.

## Assertions and limitations

The test fails closed if a child process exits unsuccessfully, the start barrier times out, a pool fails recovery, the durable row count differs from capacity, or a replay changes identity. The Python validator rejects missing, false, mistyped or unexpected report fields. The full existing Java/PostgreSQL CI must continue to pass.

The scenario uses one disposable PostgreSQL instance on a single GitHub-hosted runner. It does **not** simulate database failover, independent physical hosts, network partitions, a killed process halfway through a transaction, distributed PostgreSQL capacity management, production pool shutdown, HTTP-layer response behavior or arbitrary downstream side effects. Its small load is a correctness exercise, not a throughput benchmark.

## Release decision

A green result permits merging **the isolated experiment only**. Keep the normal production constructor on fresh JDBC. A future production-pooling PR needs explicitly bounded connection budgets across API and worker instances, connection acquisition timeouts and HTTP failure mapping, health/readiness behavior under DB outages, shutdown semantics, and clean-database repeated performance comparisons. Do not use the PR #6 throughput measurements alone as a deployment guarantee.
