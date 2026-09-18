# Failure semantics

## Decision: atomic in-process idempotency

The key lookup, capacity check, insertion into both maps, and counter updates share one monitor. HTTP response writing happens after the monitor is released. If a client loses a response after acceptance, retrying the same key and payload retrieves the same job while the process remains alive.

## Decision: no silent expiration

A new key receives 503 when capacity is exhausted. Existing matching keys still replay; conflicting payloads still receive 409. Restarting clears every key, so a retry after restart may produce a new ID. This limitation is explicit rather than claiming exactly-once delivery.

## Decision: text payloads

The learning goal is concurrency and HTTP semantics. Text avoids introducing an external JSON parser; response JSON only contains server-generated IDs and fixed strings. A production API would define a versioned schema and validation rules.

## Verification boundaries

The concurrent test proves the tested single-process implementation accepts one job for 24 simultaneous identical submissions. It does not prove durability, linearizability across instances, load tolerance, or security under hostile traffic. Those require a different storage and deployment design.

## Potential next milestone

Use a transactional database with a unique constraint on (tenant, idempotency key), store a request hash and response record, define expiry, and test crash/retry boundaries. Add a worker and durable job transitions only when job execution becomes part of the scope.
