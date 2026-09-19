# Learn the persistence milestone

Start the database and apply migration 001 using the README. Submit a job with key `lesson-1` and save the response ID. Stop Java with Ctrl+C, start it again, and repeat that request. You should receive 200 with the original ID. Change only the text and retry: you should receive 409.

Trace the code in this order:

1. `JobIntakeServer.accept` validates HTTP input before touching storage.
2. `JobStore` defines the storage contract; HTTP does not need SQL details.
3. `PostgresJobStore.submit` disables auto-commit and locks the configuration row.
4. Its lookup distinguishes replay from conflicting reuse.
5. Its count/insert occurs while the lock is held.
6. `commit()` makes acceptance durable before the HTTP response is written.

**Why not just use a ConcurrentHashMap?** A Java collection coordinates threads in one process. It does not survive a restart or coordinate two service processes.

**Why isn't the unique index enough?** It prevents duplicate keys. Two different keys could still race past a global capacity check without further coordination.

**What if the HTTP response is lost?** Repeat the original key and payload. A committed row returns the original ID.

**What if the database connection fails during commit?** The client cannot infer whether the job was accepted from a 503 alone. Retrying the same key resolves that uncertainty once storage is reachable.

**Does this execute a job exactly once?** No. The separate worker can recompute its deterministic hash after a crash. See the worker walkthrough for lease and recovery semantics.

**Does readiness mean new work will be accepted?** No. The store may be readable but full. `/ready` checks storage access; POST enforces capacity.

To inspect your local learning database:

```sh
docker compose exec postgres psql -U jobs -d jobs -c 'SELECT id, idempotency_key, status, created_at FROM jobs;'
```

The database owns the durable state; HTTP responses intentionally omit stored payload text.
