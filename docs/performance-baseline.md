# Performance baseline v1 — measurement, not optimization

This experiment measures the existing Java intake HTTP contract **without changing** the store, worker, schema, acceptance semantics, or resource limits. It is intentionally small: its primary purpose is to establish a reproducible baseline before proposing changes to the global PostgreSQL capacity-row lock.

## Safety and setup

- **Requires Python 3**, using only the standard library. The offline client tests are part of `./test.sh`.
- The benchmark requires `--allow-writes`; otherwise it prints the plan and makes **no HTTP requests**.
- It accepts only an explicit loopback `http://127.0.0.1:PORT` target; HTTPS, remote hosts, credentials, URL paths and query strings are rejected. The client disables ambient proxies and redirects.
- `--allow-writes` **permanently consumes job slots**. Use a disposable, migrated PostgreSQL database; do not benchmark the working/persistent database. The tool cannot prove which storage configuration a running server uses.
- The tool neither starts PostgreSQL nor workers nor deletes data. Start the service separately and inspect its environment before opting in.
- Hard limits: 1–200 new jobs; 1–32 client threads; HTTP timeout at most 10 seconds. This is a modest baseline, not a stress test or denial-of-service test.
- No remote URLs or production environments are supported. Avoid running other clients/workers during an initial comparison.

## Minimal local demonstration

From the repository root, use a **new disposable PostgreSQL database/volume** configured with `DATABASE_URL`, `DATABASE_USER`, and `DATABASE_PASSWORD`. Run `./migrate.sh`, then `./run.sh 8080` using that disposable database. **Do not reuse an existing PostgreSQL volume if it contains valuable jobs.**

First inspect the dry-run plan:

```sh
python3 scripts/performance_baseline.py --url http://127.0.0.1:8080 \
  --new-jobs 30 --concurrency 4 --replay-percent 50
```

After verifying that the target is isolated, run one explicitly authorized benchmark:

```sh
mkdir -p .local/performance
python3 scripts/performance_baseline.py --url http://127.0.0.1:8080 \
  --allow-writes --new-jobs 30 --concurrency 4 --replay-percent 50 \
  --output .local/performance/baseline-01.json
```

The JSON report path is created exclusively: an existing report is never overwritten. Keep experimental outputs under `.local/`, not in committed fixtures. `./test.sh` runs offline fake-server tests and the existing Java test suite. `./test.sh --postgres` additionally runs existing database integration tests.

## Definitions

- **Create phase:** sends distinct random-prefix idempotency keys with small text bodies, expecting HTTP `201` and a `Location: /jobs/...` header.
- **Replay phase:** chooses only successfully created keys, submits the identical payload again, and expects HTTP `200` with the same `Location`.
- **Client throughput:** number of completed requests divided by wall-clock time for each phase. Do not read this as sustainable system capacity.
- **HTTP round-trip latency:** elapsed client time, including local transport and scheduling. p50 uses the median and p95/p99 use nearest-rank percentiles. These small samples give noisy high percentiles.
- **Unexpected results:** unexpected status codes, missing/mismatched Location, or transport failures. The status histogram makes `503 capacity_exhausted` and saturation failures visible rather than hiding them.
- **Storage:** unverified by the client; the benchmark cannot infer whether the target is running memory mode or PostgreSQL. Report provenance must come from operator notes, not guessed metadata.

The benchmark has two ordered phases and does not measure simultaneously mixed new/replay traffic, worker throughput, database locks, or end-to-end completion. The requested replay percentage applies to the number of *new jobs* and does not describe replay share of all requests. Client concurrency uses Python threads and is not an independently rate-controlled load generator.

## Follow-up experiment plan

For a future PR: run at least three repetitions for each chosen concurrency level (e.g. 1, 4, 8, 16), with a fresh isolated database or adequate separate capacity each time. Capture host CPU/memory, JDK version, PostgreSQL version, database size, API instance count, network locality, workload, worker count, and raw reports. Compare p50/p95/p99 latency, accepted requests/s, errors, and observed PostgreSQL lock waits under the **same** workload.

Before changing the admission transaction, investigate whether the global capacity-row lock is actually the bottleneck. Preserve concurrent same-key replay, conflicting reuse, full-capacity behavior, ambiguous-commit recovery, and transactional capacity correctness. A new design is not better simply because it avoids a Java synchronized block.

## Acceptance gates for a later tuning PR

1. The baseline and alternative run on identical workload and resources.
2. All existing Java/PostgreSQL tests pass.
3. Acceptance and idempotency invariants remain true with multiple service processes.
4. Load experiments include error rates, saturation behavior and reproducible raw data.
5. A measured improvement exceeds the cost and complexity of the proposed change.

No throughput or latency improvement is claimed by this PR.
