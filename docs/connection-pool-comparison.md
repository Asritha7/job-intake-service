# JDBC connection strategy comparison — experiment only

This experiment compares the current **fresh JDBC connection per submission** against **bounded HikariCP pools of four and eight connections**. The goal is to determine whether borrowing already-established connections is worth adding to Job Intake Service. This is **not a production configuration** or authorization to promote a pool.

## Why a separate experiment?

Earlier direct-store phase timing found elevated `jdbc_connect` duration under concurrent submissions alongside configuration-row lock contention. It is not yet clear whether pooling improves throughput, merely shifts waiting from JDBC connection establishment to pool acquisition, or increases pressure on the serialized capacity-row lock.

Do not use the PostgreSQL JDBC driver's deprecated `PGPoolingDataSource` as a production model. This experiment uses HikariCP **6.3.3** plus SLF4J API **2.0.17** in `src/perf/` only. Both jars are fetched from Maven Central **only in the isolated CI runner** and checked against pinned SHA-256 values. They are not required by `./run.sh`, `./worker.sh`, `./test.sh`, or the normal Java compilation.

## What changes in the application?

`PostgresJobStore` has a package-private connection-opener constructor used only by the experiment. Its public constructor continues using `DatabaseConfig.connect()`, with identical PostgreSQL transaction logic, SQL, constraints, lock semantics, statement/lock timeouts, idempotency behavior and capacity accounting. No runtime configuration selects pooling. Existing Java integration tests must remain green.

## Experimental protocol

The dedicated workflow `.github/workflows/connection-pool-comparison.yml` provides a **fresh disposable PostgreSQL 17.11 service** and Java 21. The comparison script checks the GitHub Actions marker, exact loopback URL, CI schema/user and CI password before migrating or writing data.

For each concurrency level (1, 4, 8), each mode (`fresh`, `pool4`, `pool8`) is measured three times. Per trial, the same real `PostgresJobStore.submit` path creates 20 distinct jobs and replays 10 identical requests. After timed work, it also checks a 409 conflicting-key reuse, exact replay ID stability, SQL constraint error rollback, successful operation after error, the exact persisted job count, and a clean reusable pooled connection. These checks are not timed. All 27 trials create 567 jobs (20 timed creates + 1 post-error recovery job per trial), below the service's existing lifetime capacity of 1,000.

Pool connections are pre-established before the timed work. The *pool acquisition* timer includes time waiting for a free connection; the fresh mode's same timer includes opening a new physical JDBC connection. Thus the measurement evaluates a deliberate warm-pool comparison, **not pool startup cost**. Pool size 4 with 8 clients includes pool contention by design.

Each trial is a new Java process; the database gradually fills. Mode order is rotated across rounds to reduce—but not eliminate—order bias. The CI job archives per-trial JSON and a summary for later review.

## Metrics and limitations

Reports include direct-store successful create throughput, create p95, acquisition p95, and configuration-row lock-statement p95 for each mode/concurrency setting. The displayed value is the median of three trial-level observations, not a pooled p95.

The phase recorder adds overhead. Tests share an evolving database on a noisy GitHub Actions host. Fresh connections may allow more concurrent PostgreSQL backends than a bounded pool. Results must not be described as sustainable production capacity, an HTTP SLO, or an apples-to-apples isolated-database causal comparison.

The experiment **does not** prove production readiness, tenant security, worker throughput, DB failover behavior, sustained capacity, or performance under multiple Java API instances.

## Merge / promotion gates

The PR may be merged as isolated research if standard Java/Postgres CI and comparison CI both pass, all report files are complete and checksums are verified, and no production default changes.

**Do not turn pooling on in the main app based on this experiment alone.** Before promotion, independently test:
- Multiple JVMs sharing bounded PostgreSQL connection capacity;
- Pool exhaustion, timeouts, validation, stale/broken connections, and shutdown;
- Concurrent same-key replay, full-capacity rejection, and ambiguous commits;
- Comparable clean-database workloads repeated on more than one runner;
- Real HTTP performance and integration with API readiness/worker behavior.

Negative findings should be preserved rather than hidden.
