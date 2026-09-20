# Job Intake Service

A Java HTTP reference project demonstrating durable job acceptance, idempotent retries, bounded storage, background execution, and concurrent integration tests. PostgreSQL stores accepted jobs and request keys so retries can return the same ID after the Java service restarts.

This is a standalone portfolio exercise, with no employer code or production data. A separate worker executes one deliberately small task: computing the SHA-256 digest of each accepted text payload. It does not interpret payload text as instructions or call external services.

## Start with PostgreSQL

Requires JDK 21+, `curl`, `shasum`, and Docker Compose (or an existing PostgreSQL 17+ database). The scripts fetch a pinned pgJDBC driver from Maven Central and verify its SHA-256 checksum. No Maven or Gradle installation is required.

```sh
export DATABASE_URL='jdbc:postgresql://127.0.0.1:5432/jobs'
export DATABASE_USER=jobs
# Choose a password for your local database (input is not echoed).
read -r -s -p 'Local database password: ' DATABASE_PASSWORD; echo
export DATABASE_PASSWORD

docker compose up -d --wait
./migrate.sh
./run.sh 8080
```

The password input command above is for Bash. Other shells can use their own hidden-input facility. `.env.example` lists the variables, but Java does not automatically load `.env` files. An existing database must use UTF-8 encoding. `DATABASE_SCHEMA` defaults to `public`; a custom schema must already exist.

`migrate.sh` applies ordered, checksum-verified migrations in one transaction. Migration 001 is unchanged; migration 002 adds execution state. Existing databases from the persistence milestone are adopted into the migration ledger without deleting jobs. Re-running migrations preserves state and rejects changes to recorded SQL files. Stop old API/worker processes before upgrading, migrate, then start the new versions. The application does not create tables at startup. Missing or inaccessible storage produces `503 storage_unavailable` on storage-dependent requests.

The HTTP server and Compose database port bind to **127.0.0.1**. `docker compose stop` stops the database while retaining its named volume. Do not delete that volume if you want to retain jobs. Changing the shell password after the database is initialized does not change the existing database user's password.

## Submit and retry

In another terminal:

```sh
curl -i -X POST http://127.0.0.1:8080/jobs \
  -H 'Content-Type: text/plain' \
  -H 'Idempotency-Key: daily-report-001' \
  --data 'Generate the daily report'
```

The response is `201 Created`, a generated job ID with status `accepted` (queued), zero attempts, and `Location: /jobs/<id>`.

- Repeat the same key and exact payload: `200 OK`, original ID and current execution state. The ID is stable; status, attempts and result may change as work progresses.
- Reuse the key with different text: `409 Conflict`.
- Stop and restart Java against the same database, then retry: `200 OK`, original ID.
- Use another key with the same text: a separate job is accepted.

Payloads are not echoed in HTTP responses. Keys currently have a global scope within the database schema; there are no tenant accounts or key-expiry rules.

## Execute queued jobs

In another terminal with the same `DATABASE_*` environment:

```sh
./worker.sh          # continuous polling; Ctrl+C stops the process
./worker.sh --once   # alternatively, process at most one eligible job
```

A job moves from `accepted` to `running`, then `succeeded` or `failed`. Processor failures with attempts remaining return to `accepted` with a retry delay. GET responses include `attempts`, nullable `result_sha256`, and nullable `last_error`. Neither payload text nor internal lease tokens are returned.

The worker uses a 30-second lease, a fresh ownership token per attempt, and up to three attempts. Expired leases can be reclaimed; stale owners cannot overwrite newer attempts. Two workers can process different jobs concurrently. See the [worker experiment and state diagram](docs/worker-walkthrough.md).

## API contract

| Endpoint | Behavior |
|---|---|
| `GET /health` | Process liveness; does not check PostgreSQL |
| `GET /ready` | `200` when the store tables/configuration are readable; otherwise `503` |
| `POST /jobs` | `201` new acceptance; `200` replay; `409` conflicting reuse |
| `GET /jobs/<id>` | `200` current job state/result; `404` unknown ID |
| `GET /metrics` | Process-local HTTP, executor, storage-error, and acceptance metrics |

The key must contain 1–80 ASCII letters, digits, dots, underscores, or hyphens. Bodies must be nonblank UTF-8 text, at most 8,192 bytes, without NUL characters, with `Content-Type: text/plain`. Payload comparison is exact, including whitespace. Invalid input returns `400`, unsupported media type `415`, oversized payload `413`, and wrong method `405`.

The initial database capacity is 1,000 jobs. `503 capacity_exhausted` rejects new keys when full, while matching keys remain replayable and conflicting reuse still returns `409`. Capacity is stored in `intake_config`, shared across service instances. It is not reset by restarting Java or re-running migration 001. There is no deletion or automatic eviction API. Completed and failed jobs still occupy capacity and retain their idempotency keys.

## How persistence works

1. Start a database transaction using READ COMMITTED isolation.
2. Lock the single configuration row with `SELECT ... FOR UPDATE`.
3. Look up the idempotency key; return its existing job or a conflict if present.
4. Check capacity, then insert a job with a database-enforced unique key.
5. Commit before returning acceptance to the client.

All submissions serialize on that row, including replays. This is deliberately simple and keeps the global capacity check correct across instances; it is a throughput bottleneck. Lookups do not take this lock. Each operation opens and closes a JDBC connection; connection pooling is a later improvement.

If a connection fails during commit, the client may receive `503` even though the commit succeeded. Retry with the **same key and payload** to resolve that ambiguity. Retention of committed jobs depends on retaining the database and its normal durability settings; application restarts alone do not erase them.

## HTTP resource limits

The API uses eight handler threads and at most 64 queued executor tasks. When both are occupied, excess connections are closed by the JDK before a handler can generate an HTTP response. Clients should back off and retry submissions with the same key and payload.

The built-in JDK provider is configured before startup for 128 open connections, 16 idle connections, 32 headers, a 16 KiB header-section limit, a 10-second request-receive budget, and a 15-second response budget. Timeout enforcement is periodic, not an exact deadline. Unread rejected bodies have an 8 KiB drain budget, also subject to the request timer. Shutdown stops accepting connections and gives current exchanges up to five seconds before closing them and interrupting executor tasks.

`/health`, `/ready`, and `/metrics` share the same executor, so they can become unreachable during saturation. These controls do not replace a production edge proxy or deployment-specific load testing.

## Verification

```sh
./test.sh             # HTTP contract and operational socket tests
./test.sh --postgres  # additionally requires DATABASE_* and a running PostgreSQL server
```

The PostgreSQL tests create a uniquely named schema, apply the real SQL, and remove only that test schema afterward. The test database user needs permission to create schemas. Never point tests at a production database.

Acceptance tests cover 24 matching requests across two server instances, concurrent conflicting payloads, database uniqueness and length constraints, rollback after failed insertion, shared-capacity races, exact Unicode payload replay, complete child-JVM restarts, repeatable bootstrap, and unavailable-schema responses. Worker tests additionally cover competing claims, known digest results, stale-token rejection, scheduled retries, retry exhaustion, a child process that halts after claiming, and upgrading an existing migration-001 database. GitHub Actions runs all suites against PostgreSQL 17 on JDK 21.

For the original non-durable learning mode only:

```sh
JOB_STORE=memory ./run.sh 8080
```

This mode loses jobs on restart and has no worker; workers always use PostgreSQL. There is no automatic fallback from PostgreSQL to memory.

## Scope and next milestone

The worker is a local reference implementation, not a general execution platform. It uses a deterministic hash task, with no external side effects. Execution can repeat after a crash; the design does not promise exactly-once execution. It has no authentication, TLS, tenant isolation, key expiration, worker heartbeats, or production deployment. There is no arbitrary-task execution deadline; adding slower processors requires additional controls.

`/ready` checks basic read access, not every possible write permission or available capacity. Metrics describe HTTP acceptance in the current process and reset on restart; jobs and execution attempts persist. JDBC connection/socket and statement/lock timeouts bound database waits. HTTP handling uses bounded threads and a bounded queue, JDK transport limits, and a five-second shutdown grace period; see the operational guide for the exact boundaries.

See [design notes](docs/design.md), the [persistence exercise](docs/persistence-walkthrough.md), and the [worker exercise](docs/worker-walkthrough.md). See the [operational guide](docs/operations.md) for limits, metric meanings, overload behavior, and shutdown. Further work could add durable worker metrics, connection pooling, and a measured load baseline.
