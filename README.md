# Job Intake Service

A Java HTTP reference project demonstrating durable job acceptance, idempotent retries, bounded storage, and concurrent integration tests. PostgreSQL stores accepted jobs and request keys so retries can return the same ID after the Java service restarts.

This is a standalone portfolio exercise, with no employer code or production data. It **accepts jobs but does not execute them**.

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

`migrate.sh` explicitly bootstraps migration 001 in a transaction. Re-running it preserves existing jobs and configured capacity. It is an initial bootstrap, not a general migration-version framework; future schema changes should be separate, tracked migrations. The application does not create tables at startup. Missing or inaccessible storage produces `503 storage_unavailable` on storage-dependent requests.

The HTTP server and Compose database port bind to **127.0.0.1**. `docker compose stop` stops the database while retaining its named volume. Do not delete that volume if you want to retain jobs. Changing the shell password after the database is initialized does not change the existing database user's password.

## Submit and retry

In another terminal:

```sh
curl -i -X POST http://127.0.0.1:8080/jobs \
  -H 'Content-Type: text/plain' \
  -H 'Idempotency-Key: daily-report-001' \
  --data 'Generate the daily report'
```

The response is `201 Created`, a generated job ID with status `accepted`, and `Location: /jobs/<id>`.

- Repeat the same key and exact payload: `200 OK`, original ID.
- Reuse the key with different text: `409 Conflict`.
- Stop and restart Java against the same database, then retry: `200 OK`, original ID.
- Use another key with the same text: a separate job is accepted.

Payloads are not echoed in HTTP responses. Keys currently have a global scope within the database schema; there are no tenant accounts or key-expiry rules.

## API contract

| Endpoint | Behavior |
|---|---|
| `GET /health` | Process liveness; does not check PostgreSQL |
| `GET /ready` | `200` when the store tables/configuration are readable; otherwise `503` |
| `POST /jobs` | `201` new acceptance; `200` replay; `409` conflicting reuse |
| `GET /jobs/<id>` | `200` accepted job; `404` unknown ID |
| `GET /metrics` | Process-local counters for creation, replay, conflict, and capacity rejection |

The key must contain 1–80 ASCII letters, digits, dots, underscores, or hyphens. Bodies must be nonblank UTF-8 text, at most 8,192 bytes, without NUL characters, with `Content-Type: text/plain`. Payload comparison is exact, including whitespace. Invalid input returns `400`, unsupported media type `415`, oversized payload `413`, and wrong method `405`.

The initial database capacity is 1,000 jobs. `503 capacity_exhausted` rejects new keys when full, while matching keys remain replayable and conflicting reuse still returns `409`. Capacity is stored in `intake_config`, shared across service instances. It is not reset by restarting Java or re-running migration 001. There is no deletion or automatic eviction API.

## How persistence works

1. Start a database transaction using READ COMMITTED isolation.
2. Lock the single configuration row with `SELECT ... FOR UPDATE`.
3. Look up the idempotency key; return its existing job or a conflict if present.
4. Check capacity, then insert a job with a database-enforced unique key.
5. Commit before returning acceptance to the client.

All submissions serialize on that row, including replays. This is deliberately simple and keeps the global capacity check correct across instances; it is a throughput bottleneck. Lookups do not take this lock. Each operation opens and closes a JDBC connection; connection pooling is a later improvement.

If a connection fails during commit, the client may receive `503` even though the commit succeeded. Retry with the **same key and payload** to resolve that ambiguity. Retention of committed jobs depends on retaining the database and its normal durability settings; application restarts alone do not erase them.

## Verification

```sh
./test.sh             # original in-memory HTTP contract tests
./test.sh --postgres  # additionally requires DATABASE_* and a running PostgreSQL server
```

The PostgreSQL tests create a uniquely named schema, apply the real SQL, and remove only that test schema afterward. The test database user needs permission to create schemas. Never point tests at a production database.

Tests cover 24 matching requests across two server instances, concurrent conflicting payloads, database uniqueness and length constraints, rollback after failed insertion, shared-capacity races, exact Unicode payload replay, complete child-JVM restarts, repeatable bootstrap, and unavailable-schema responses. GitHub Actions runs them against PostgreSQL 17 on JDK 21.

For the original non-durable learning mode only:

```sh
JOB_STORE=memory ./run.sh 8080
```

This mode loses jobs on restart. There is no automatic fallback from PostgreSQL to memory.

## Scope and next milestone

There is no worker, durable queue, authentication, TLS, multi-tenant isolation, key expiration, or production deployment. `/ready` checks basic read access, not every possible write permission or available capacity. Metrics describe the current process and reset on restart, unlike jobs. JDBC connection/socket and statement/lock timeouts bound database waits; HTTP slow-client handling, bounded executor queues, and full graceful request draining remain future work.

Next: define job execution and recovery semantics before adding a worker. See [design notes](docs/design.md) for the reasoning and [a restart exercise](docs/persistence-walkthrough.md) to learn the flow.
