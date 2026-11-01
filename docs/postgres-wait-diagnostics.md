# PostgreSQL lock-wait diagnostics — isolated experiment

This extends the existing [isolated CI benchmark](performance-ci.md) by observing PostgreSQL backend wait events *during* the real HTTP workload. It does not change the API, `PostgresJobStore`, JDBC transactions, migrations, workers, or default runtime.

## Implementation and controls

- `PgWaitSampler` is a **test-source-only** Java utility, invoked from the existing isolated GitHub Actions benchmark. It is not an API endpoint or an operator-facing metric.
- It refuses execution outside the dedicated GitHub Actions job and its exact loopback JDBC/database configuration.
- It runs only the known bounded HTTP benchmark child command, uses the existing JDBC connection settings, reads `pg_stat_activity` in the same disposable database/user, and stores category counts rather than query strings, credentials, job IDs, payloads or raw SQL.
- Every trial receives a separate `round-N-clients-N.wait.json` report. The output path must be in `.local/performance-ci`, and existing reports are rejected before starting the child.
- The collector checks the child exit code and SQL sampling failures; either failure causes CI to fail. The summarizer rejects missing, invalid and failed sampler reports.
- Previous baseline data remains untouched. The workflow still runs three sets of 30 accepted jobs, with 15 idempotent replays per trial.

## What a wait sample means

The observer polls `pg_stat_activity` approximately every **10 milliseconds** while the benchmark child is alive. For other sessions of the same database user/database it classifies:

| Category | Observation |
| --- | --- |
| `capacity_lock_wait` | Backend's current statement is the configuration-row `SELECT ... FOR UPDATE` and its wait event is of type `Lock` |
| `other_lock_wait` | Waiting for a PostgreSQL lock while executing something else |
| `io_wait`, `lightweight_lock_wait`, `client_wait` | Other PostgreSQL wait types |
| `not_waiting` | No wait event reported |
| `other_wait` | Other wait-event types |

Counts represent **backend observations across snapshots**, not distinct transactions, lock acquisitions, wait durations or percentages of HTTP time. The sampler may miss short waits, poll non-job database sessions, and alter the workload slightly through its own monitoring. The PostgreSQL wait event may represent a transaction-ID wait while contending for a row; it need not have a literal `tuple` event name.

A zero count is **not proof that the lock is uncontended**. A positive count is evidence of observed waiting, **not proof that waiting dominates end-to-end latency**. The fixed create-before-replay phase order means the aggregate includes both workloads; separate phase-specific samples may be needed for a stronger conclusion. The observation cannot by itself attribute every millisecond to connection creation, server scheduling, SQL execution or contention.

## Interpretation and next gate

Compare the wait-event counts and client p95 across concurrency settings. If the new reports show consistent `capacity_lock_wait` observations rising with load, design a follow-up **separate** experiment that measures transaction phases directly (connection acquisition, lock acquisition, key lookup, capacity check, commit) behind explicit test-only instrumentation. Do not optimize the admission lock or claim a speedup before that evidence is available.

If capacity-lock samples are rare or absent, inspect connection setup, client scheduling, `count(*)` cost and the thread pool before tuning the transaction.

All reports are private short-lived GitHub Actions artifacts. No production performance claims should be derived from them.
