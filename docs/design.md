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

`migrate.sh` applies the initial schema explicitly and transactionally, with an advisory lock to serialize bootstrap. `CREATE TABLE IF NOT EXISTS` and an insert-on-conflict for configuration make this specific initial bootstrap repeatable. It does not validate arbitrary existing schemas or track future migration versions. Do not edit an already deployed migration to change its schema; introduce a tracked migration mechanism for later evolution.

## Verification boundaries

Tests use real PostgreSQL, real HTTP requests, independent store instances, and separately launched JVMs. They exercise key/capacity contention, SQL constraints, rollback and restarts. They do not establish high-load performance, authentication security, backup recovery, or exactly-once job execution. There is still no job executor.
