# Optional bounded JDBC connection pool

**Default behavior is unchanged.** The Job Intake HTTP API opens a fresh PostgreSQL JDBC connection for each operation unless you explicitly enable a bounded HikariCP pool. The worker remains on the existing fresh-connection path.

## Enable only after migrating

Start an isolated local PostgreSQL database, apply migrations, and then set:

```sh
export JOB_DB_POOL_SIZE=2
./run.sh 8080
```

The setting accepts `0` (default, disabled) or an integer from `1` through `8`. A malformed or out-of-range setting fails startup with a generic configuration error. `JOB_STORE=memory` cannot be combined with pooling. The pool is not automatically activated by the presence of HikariCP jars.

For comparison, use `unset JOB_DB_POOL_SIZE` or `JOB_DB_POOL_SIZE=0` to use the original fresh-connection implementation. Pooling is **per Java API process**, not a global database-wide limit.

## Guarantees and failure behavior

- **Same SQL and transactions:** Both modes use `PostgresJobStore` with READ COMMITTED, the same `SELECT ... FOR UPDATE` capacity lock, database uniqueness constraint, idempotency contract and commit-before-acknowledge behavior.
- **Connection bounds:** Each pooled API process permits at most `JOB_DB_POOL_SIZE` physical PostgreSQL connections via Hikari. `minimumIdle=0` avoids requiring a permanently warm pool. A request can wait up to three seconds to borrow a connection; that time includes any queueing for a free pool slot.
- **SQL limits:** Pool sessions receive the same existing PostgreSQL driver properties: three-second connection timeout, eight-second socket timeout, five-second statement timeout, three-second lock timeout, and selected schema. Hikari validates connections within one second.
- **Outage:** Pool construction does not require successful initial PostgreSQL connectivity. `GET /health` remains process liveness, while `GET /ready` and database-dependent routes fail with sanitized `503 storage_unavailable` on connection errors. Exhaustion is also a storage-unavailable condition, not proof of dropped durable jobs. Clients must use their original idempotency key on retries.
- **Shutdown:** The HTTP server stops accepting work and drains with its existing five-second grace period, then the owned pool is closed. The worker process and its connections are separate.
- **Isolation:** No tenant-specific quotas, authentication or TLS are added; this remains a loopback-only reference implementation.

### Plan the whole connection budget

Two API JVMs each configured with `JOB_DB_POOL_SIZE=4` permit up to **eight pooled API connections**, *plus* worker JDBC connections, migration/administration sessions, monitoring sessions and PostgreSQL's reserved connections. Under heavy load, smaller pools can improve stability by bounding concurrency, while larger pools may increase contention on the shared configuration-row lock. Do not assume eight is faster or safer than two.

## Verification

```sh
./test.sh --postgres
```

The pooled HTTP integration suite uses a unique, disposable PostgreSQL schema. It starts two API servers with independent pools, races twenty identical requests, checks the original ID across replays, verifies 409 conflicts, tests recovery after a rejected SQL write, saturates a shared four-job capacity, and asserts pool closure. It also checks that an unmigrated schema returns 503 on readiness and acceptance while liveness stays 200. It never truncates existing job data.

Historical experimental evidence remains in [connection pooling performance](connection-pool-comparison.md), [pool failure/multi-JVM checks](pool-resilience.md), [phase timing](admission-phase-timing.md), and [wait-event sampling](postgres-wait-diagnostics.md). The earlier Hikari benchmark compared prewarmed connections against fresh physical connections and **does not imply a production speedup**.

## Not yet claimed

This feature does not establish production readiness, distributed tracing, tenant isolation, dynamic pool sizing, multi-host partitions/failover behavior, sustained HTTP service-level objectives, or exactly-once external side effects. Long-running workers still use the existing no-heartbeat lease design. The shared capacity-row lock still serializes submissions, including idempotent replays.

A production deployment would require a combined DB connection budget across all replicas, connection failure/error-rate alerts, independent load tests at the intended traffic profile, TLS, authentication, and resource/worker scaling decisions.
