# Admission transaction-phase timing — isolated CI experiment

This experiment investigates *where time is spent* during a PostgreSQL-backed submission, without changing the admission algorithm, capacity policy, or HTTP response contract.

The ordinary `PostgresJobStore(DatabaseConfig)` constructor supplies no observer; optional timing collection is only enabled by a package-private constructor from the CI-only `AdmissionPhaseProbe` test source. No system property, environment variable, public endpoint, or runtime configuration activates collection in the deployed application. The default path does not call the timing clock.

## What is measured

The same `PostgresJobStore.submit` code reports elapsed monotonic-clock time around:

| Stage | Boundary | Important qualification |
| --- | --- | --- |
| `jdbc_connect` | `DatabaseConfig.connect()` | Includes opening a JDBC connection and setting transaction isolation, not network latency alone |
| `capacity_lock` | Prepare/execute/read `SELECT capacity ... FOR UPDATE` | Includes SQL round trip and actual row-lock wait; **not an exact lock-wait duration** |
| `key_lookup` | Idempotency-key query | One event for both new jobs and replays |
| `capacity_count` | `SELECT count(*) FROM jobs` | New-job path only; includes client and database work |
| `insert` | UUID insertion statement | New-job path only; does not include committing |
| `commit` | `Connection.commit()` | Includes JDBC round trip and commit completion |

These are synchronous stage timings recorded in nanoseconds and exported as p50/p95/sum milliseconds. The aggregation stores **only fixed stage names, counts and timings**: no payloads, job IDs, idempotency keys, SQL strings or credentials.

## Benchmark design

The separate test-only `AdmissionPhaseProbe` uses the same real PostgreSQL database as the existing isolated CI performance workflow, but calls the store directly rather than sending HTTP requests. For each of 1, 4, and 8 concurrent clients, three repeated probe runs create 30 distinct jobs and replay 15 of them.

The nine phase probes add 270 accepted jobs to the existing 273 benchmark jobs, leaving 543 in the disposable database, below the current 1,000-job limit. Workers are not started. The probe refuses non-CI execution, unfamiliar database endpoints, unsupported workloads and report overwrites.

The observer records times within the actual production store's transaction code, but **phase results are not an additive breakdown of HTTP latency**: they omit HTTP parsing, executor queueing, job serialization, connection closure and more. The optional observer and concurrent aggregation add overhead; phase durations may also overlap across requests. Stages are recorded separately for create and replay operations, while the original HTTP and wait-event benchmark outputs remain separate.

## Acceptance checks

- The full Java integration suite, including the existing duplicate-key, capacity, restart, worker and row-lock tests, must pass.
- The offline Java and Python summary tests must pass.
- Every trial must return 30 HTTP-independent store creates and 15 replays; each required stage must have the expected number of finite nonnegative samples.
- No changes to the database schema, Java API response, worker state machine, retry policy or idempotency contract.
- The per-trial phase reports are archived as short-lived GitHub Actions artifacts alongside previous wait and HTTP reports.

## Interpreting results

Compare the observed per-stage latency distributions under each concurrency setting, noting the growing database population, fixed phase order, shared CI hardware and limited sample size. A rise in `capacity_lock` duration along with PostgreSQL `pg_stat_activity` wait samples is **evidence consistent with lock contention**, but this still does not prove that server-side lock waiting accounts for all increased client latency. If necessary, follow with a dedicated test comparing same-host monitored and unmonitored paths on fresh databases before optimizing transaction logic.

**Do not replace the global lock, add pooling, or claim a speedup in this diagnostic milestone.**
