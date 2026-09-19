# Durable acceptance: decisions and failure semantics

## One transaction per acceptance

The job ID, key, payload, status, and timestamp live in one PostgreSQL row. A unique index enforces one row per key. The service commits before returning 201. A response lost after commit is recoverable by retrying the same key; the request does not need to create another job.

## Why lock one configuration row?

A unique index protects key uniqueness, but it does not make `count jobs → check capacity → insert` atomic for different keys. Every service instance locks the same configuration row before looking up a key and counting jobs. This serializes writers and gives the capacity check a fresh READ COMMITTED view after the preceding transaction commits.

The transaction lock is released on commit, rollback, or connection termination. It replaces the old JVM monitor for PostgreSQL operations; Java synchronization alone would not coordinate two processes. Direct database writers that bypass this protocol can violate the capacity rule, so application access must be controlled in any deployment.

This is intentionally conservative. A higher-throughput design could atomically reserve capacity and use per-key conflict handling. That adds complexity and needs its own contention and failure tests.

## PostgreSQL constraints and input

UUID is the primary key; the ASCII request key has a unique constraint and C collation; payload bytes are bounded. The HTTP layer additionally rejects blank text and NUL. Prepared statements keep text separate from SQL. Exact payload equality preserves the initial API semantics.

Database exceptions become a fixed 503 response rather than leaking driver messages, JDBC URLs, or payloads. A lost connection during commit has an ambiguous outcome; the application does not assume rollback succeeded or retry with a new key.

## What survives a restart?

Committed jobs and configured capacity survive a Java restart and normal PostgreSQL restarts when its data volume is retained. Per-process counters do not. The explicitly selected memory backend remains non-durable. Losing the database volume, restoring an older backup, or modifying stored keys changes the guarantee.

## Migration boundary

`migrate.sh` applies schema migrations explicitly and transactionally, with an advisory lock to serialize migration runners. `CREATE TABLE IF NOT EXISTS` and an insert-on-conflict for configuration make this specific initial bootstrap repeatable. The worker milestone adds a `schema_migrations` ledger with checksums and migration 002. The runner adopts an existing migration-001 schema, applies outstanding SQL in order, and rejects drift in recorded files. It does not validate arbitrary hand-edited schemas. Never edit an already recorded migration to change the schema; add a new one.

## Verification boundaries

Tests use real PostgreSQL, real HTTP requests, independent store instances, and separately launched JVMs. They exercise key/capacity contention, SQL constraints, rollback and restarts. They do not establish high-load performance, authentication security, backup recovery, or exactly-once job execution. The worker executes only a deterministic hash task; external side-effect guarantees are outside this design.

## Leased execution

Workers take row locks using `FOR UPDATE SKIP LOCKED`, commit a lease, and release the transaction before computing. See the [PostgreSQL locking reference](https://www.postgresql.org/docs/current/sql-select.html#SQL-FOR-UPDATE-SHARE). A token and unexpired lease are required to persist completion. Expiry allows retries but does not stop an old process from running; fencing protects database state only.

Execution claims consume a bounded attempt budget. Explicit failure schedules exponential backoff; crashed workers are recovered after lease expiry. Final-attempt expiry becomes terminal failure when a worker next polls. Terminal jobs are retained for key deduplication and still count toward capacity.
